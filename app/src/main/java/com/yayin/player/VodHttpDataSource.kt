package com.yayin.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/**
 * Film/dizi dosyaları için kendi HTTP okuyucumuz.
 *
 * Bazı IPTV sunucuları, ExoPlayer'ın kendi okuyucusunun gönderdiği ek başlıklar yüzünden
 * "dosyanın şu konumundan gönder" (Range) isteğini yok sayıp dosyayı baştan gönderiyor. Sonu "moov"
 * ile biten MP4'ler bu yüzden açılmıyordu. Bu okuyucu isteği teşhis aracımızla aynı, sade biçimde
 * gönderir (yalnızca User-Agent + Range):
 *
 *  - Dosyayı küçük, sınırlı parçalar hâlinde ister (64 KB'tan başlayıp 2 MB'a kadar). Her parça sonuna
 *    kadar okunduğu için sunucuyla aynı bağlantı tekrar kullanılır (tek bağlantı sınırına takılmaz).
 *  - Sunucu yanlış konum, boş yanıt ya da hata gönderirse bekleyip tekrar dener.
 *  - Sunucu biraz geriden başlatırsa aradaki baytları atlar.
 */
@OptIn(UnstableApi::class)
class VodHttpDataSource(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val onNote: (String) -> Unit,
) : BaseDataSource(true) {

    private var spec: DataSpec? = null
    private var uri: Uri? = null
    private var response: Response? = null
    private var stream: InputStream? = null
    private var headers: Map<String, List<String>> = emptyMap()

    private var pos = 0L
    private var end = -1L          // istenen bitiş (hariç), -1 = dosya sonu
    private var total = -1L        // dosyanın toplam boyu
    private var chunkLeft = 0L     // bu parçada okunacak kalan bayt; -1 = sınırsız (sunucu aralık desteklemiyor)
    private var rampIdx = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        spec = dataSpec
        uri = dataSpec.uri
        pos = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.position + dataSpec.length else -1L
        total = -1L
        rampIdx = 0
        fetch(pos)
        opened = true
        transferStarted(dataSpec)
        return when {
            end >= 0 -> end - pos
            total > 0 -> total - pos
            else -> C.LENGTH_UNSET.toLong()
        }
    }

    private fun mbs(b: Long) = "${b / 1048576} MB"

    private fun closeResponse() {
        try { stream?.close() } catch (_: Exception) {}
        try { response?.close() } catch (_: Exception) {}
        stream = null; response = null
    }

    /** p konumundan bir parça ister; başarılı olunca stream hazırdır. */
    private fun fetch(p: Long) {
        val size = RAMP[minOf(rampIdx, RAMP.size - 1)]
        rampIdx++
        var lastErr: IOException? = null
        val tries = if (p > 0) 4 else 2
        for (a in 0 until tries) {
            if (a > 0) {
                try { Thread.sleep(1_000L * a) } catch (_: InterruptedException) { throw InterruptedIOException() }
            }
            var want = size
            if (end >= 0) want = minOf(want, end - p)
            if (total > 0) want = minOf(want, total - p)
            if (want <= 0) { stream = null; chunkLeft = 0; return }
            val req = Request.Builder().url(uri.toString())
                .header("User-Agent", userAgent)
                .header("Range", "bytes=$p-${p + want - 1}")
                .build()
            try {
                val r = http.newCall(req).execute()
                response = r
                headers = r.headers.toMultimap()
                val code = r.code
                val cr = r.header("Content-Range") ?: ""
                val m = Regex("bytes\\s+(?:(\\d+)-(\\d+)|\\*)/(\\d+|\\*)").find(cr)
                val crStart = m?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                val crTotal = m?.groupValues?.get(3)?.toLongOrNull() ?: -1L
                if (crTotal > 0) total = crTotal
                if (code == 416) { closeResponse(); stream = null; chunkLeft = 0; return }   // dosya sonu
                if (code !in 200..299) {
                    closeResponse()
                    onNote("${mbs(p)}: HTTP $code")
                    val err = IOException("HTTP $code")
                    lastErr = err
                    if (code in 400..499 && code != 403 && code != 429) throw err
                    continue
                }
                val body = r.body ?: run { closeResponse(); onNote("${mbs(p)}: gövde yok"); null }
                if (body == null) continue
                val inp = body.byteStream()
                if (code == 200) {
                    // Sunucu aralık desteklemiyor: dosya baştan geliyor
                    if (p == 0L) {
                        if (total < 0 && body.contentLength() > 0) total = body.contentLength()
                        stream = inp; chunkLeft = -1L; return
                    }
                    onNote("${mbs(p)}: sunucu aralığı yok saydı (HTTP 200)")
                    closeResponse(); lastErr = IOException("Sunucu konumdan okumayı desteklemiyor"); continue
                }
                // 206
                var skip = 0L
                if (crStart >= 0 && crStart != p) {
                    if (crStart < p && p - crStart <= SKIP_MAX) skip = p - crStart
                    else {
                        onNote("${mbs(p)} istendi → '$cr'")
                        closeResponse(); lastErr = IOException("Sunucu yanlış konum gönderdi: $cr"); continue
                    }
                }
                if (skip > 0) {
                    val buf = ByteArray(32 * 1024); var s = 0L
                    while (s < skip) { val n = inp.read(buf, 0, minOf(buf.size.toLong(), skip - s).toInt()); if (n < 0) break; s += n }
                    if (s < skip) { closeResponse(); onNote("${mbs(p)}: atlama yarıda kaldı"); continue }
                }
                val bodyLen = body.contentLength()
                chunkLeft = if (bodyLen > 0) bodyLen - skip else want
                stream = inp
                return
            } catch (e: InterruptedIOException) {
                closeResponse(); throw e
            } catch (e: IOException) {
                closeResponse()
                lastErr = e
                onNote("${mbs(p)}: " + (e.javaClass.simpleName + " " + (e.message ?: "")).trim().take(60))
            }
        }
        throw lastErr ?: IOException("Sunucu ${mbs(p)} konumundan veri göndermedi")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (end in 0..pos) return C.RESULT_END_OF_INPUT
        if (total in 1..pos) return C.RESULT_END_OF_INPUT
        val inp = stream ?: return C.RESULT_END_OF_INPUT
        var len = length.toLong()
        if (chunkLeft >= 0) len = minOf(len, chunkLeft)
        if (end >= 0) len = minOf(len, end - pos)
        if (len <= 0) {
            // Parça bitti: kalanı (varsa) tüket, aynı bağlantıyla sıradakini iste
            closeResponse()
            if (chunkLeft == 0L) {
                fetch(pos)
                if (stream == null) return C.RESULT_END_OF_INPUT
                return read(buffer, offset, length)
            }
            return C.RESULT_END_OF_INPUT
        }
        val n = inp.read(buffer, offset, len.toInt())
        if (n < 0) {
            if (chunkLeft > 0 && (total < 0 || pos < total)) {
                // Parça beklenenden kısa geldi: kaldığı yerden yeniden iste
                closeResponse()
                chunkLeft = 0
                fetch(pos)
                if (stream == null) return C.RESULT_END_OF_INPUT
                return read(buffer, offset, length)
            }
            return C.RESULT_END_OF_INPUT
        }
        pos += n
        if (chunkLeft > 0) chunkLeft -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = headers

    override fun close() {
        // Parçanın kalanı azsa okuyup at: yanıt tamamlanınca bağlantı havuza döner, yeni bağlantı açılmaz
        val inp = stream
        if (inp != null && chunkLeft in 1..DRAIN_MAX) {
            try {
                val buf = ByteArray(32 * 1024)
                while (true) { if (inp.read(buf) < 0) break }
            } catch (_: Exception) {}
        }
        closeResponse()
        if (opened) { opened = false; transferEnded() }
    }

    private companion object {
        val RAMP = longArrayOf(64L * 1024, 256L * 1024, 1024L * 1024, 2L * 1024 * 1024)
        const val DRAIN_MAX = 2L * 1024 * 1024
        const val SKIP_MAX = 16L * 1024 * 1024
    }
}
