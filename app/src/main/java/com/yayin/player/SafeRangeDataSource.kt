package com.yayin.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Film/dizi dosyalarında sunucunun "dosyanın ortasından/sonundan oku" isteklerine güvenilir yanıt
 * vermesini sağlar. Sonu "moov" ile biten MP4'lerde oynatıcı önce dosyanın sonuna atlar; bazı IPTV
 * sunucuları (özellikle tek bağlantı sınırı olanlar) bu ikinci isteğe boş yanıt veriyor ve oynatıcı
 * "dosya bozuk" sanıyor.
 *
 *  - Parçalı modda dosyayı küçük, sınırlı parçalar hâlinde ister ("bytes=a-b"; 64 KB'tan başlayıp 2 MB'a kadar).
 *    Her parça sonuna kadar okunduğu için sunucuyla aynı bağlantı tekrar kullanılır; oynatıcı dosyanın
 *    sonuna atlarken yeni bağlantı açılmaz (tek bağlantı sınırı olan sunucular yenisini reddediyor).
 *    Yarıda bırakılan parçanın kalanı kapatırken okunup atılır, yine bağlantı korunur.
 *  - Ortadan yapılan istek boş gelir, yanlış konumdan gelir ya da hata verirse kısa bekleyip tekrar dener.
 *
 * Dosyanın başından yapılan isteklere (canlı yayınlar dahil) dokunmaz.
 */
@OptIn(UnstableApi::class)
class SafeRangeDataSource(
    private val upstream: DataSource,
    private val chunked: () -> Boolean,
    private val onNote: (String) -> Unit,
) : DataSource {

    private val UNSET = C.LENGTH_UNSET.toLong()

    private var spec: DataSpec? = null
    private var pos = 0L            // sıradaki okunacak mutlak konum
    private var end = UNSET         // çağıranın istediği bitiş (hariç) ya da belirsiz
    private var total = -1L         // dosyanın toplam boyu (Content-Range'den)
    private var useChunks = false
    private var chunkReq = 0L       // bu parçada istenen bayt
    private var chunkGot = 0L       // bu parçada gelen bayt
    private var upstreamOpen = false
    private var rampIdx = 0

    private var pre: ByteArray? = null
    private var preLen = 0
    private var prePos = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        pos = dataSpec.position
        end = if (dataSpec.length != UNSET) dataSpec.position + dataSpec.length else UNSET
        total = -1L
        rampIdx = 0
        val http = dataSpec.uri.scheme?.startsWith("http") == true
        useChunks = http && chunked()
        val len = openAt(pos, if (http && pos > 0) 3 else 1)
        return when {
            end != UNSET -> dataSpec.length
            useChunks -> if (total > 0) total - pos else UNSET
            else -> len
        }
    }

    private fun headerValue(name: String): String? =
        upstream.responseHeaders.entries.firstOrNull { it.key != null && it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    /** Content-Range: "bytes 100-199/5000" ya da "bytes *\/5000" → (başlangıç, toplam) */
    private fun contentRange(): Pair<Long, Long> {
        val cr = headerValue("Content-Range") ?: return -1L to -1L
        val m = Regex("bytes\\s+(?:(\\d+)-\\d+|\\*)/(\\d+|\\*)").find(cr) ?: return -1L to -1L
        return (m.groupValues[1].toLongOrNull() ?: -1L) to (m.groupValues[2].toLongOrNull() ?: -1L)
    }

    private fun closeUpstreamQuietly() {
        if (!upstreamOpen) return
        upstreamOpen = false
        try { upstream.close() } catch (_: Exception) {}
    }

    private fun openAt(p: Long, attempts: Int): Long {
        val base = spec ?: throw IOException("no spec")
        var lastErr: IOException? = null
        val chunk = RAMP[minOf(rampIdx, RAMP.size - 1)]
        if (useChunks) rampIdx++
        for (a in 0 until attempts) {
            if (a > 0) {
                try { Thread.sleep(1_000L * a) } catch (_: InterruptedException) { throw InterruptedIOException() }
            }
            val reqLen = when {
                useChunks -> if (end != UNSET) minOf(chunk, end - p) else chunk
                end != UNSET -> end - p
                else -> UNSET
            }
            val s = base.buildUpon().setPosition(p).setLength(reqLen).build()
            try {
                val len = upstream.open(s)
                upstreamOpen = true
                val (crStart, crTotal) = contentRange()
                if (crTotal > 0) total = crTotal
                if (p > 0 && crStart >= 0 && crStart != p) {
                    onNote("sunucu yanlış konum gönderdi")
                    closeUpstreamQuietly(); continue
                }
                pre = null; preLen = 0; prePos = 0
                if (p > 0 && attempts > 1 && (total < 0 || p < total)) {
                    // Gerçekten veri geliyor mu? İlk parçayı okuyup sakla
                    val buf = ByteArray(16 * 1024)
                    val r = upstream.read(buf, 0, buf.size)
                    if (r == C.RESULT_END_OF_INPUT || r == 0) {
                        onNote("ortadan okumada boş yanıt")
                        closeUpstreamQuietly(); continue
                    }
                    pre = buf; preLen = r
                }
                chunkReq = if (useChunks) (if (len != UNSET) len else reqLen) else -1L
                chunkGot = 0L
                return len
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                lastErr = e
                upstreamOpen = true; closeUpstreamQuietly()
                if (p == 0L || attempts == 1 || e.responseCode == 416) throw e
                onNote("HTTP ${e.responseCode}")
            } catch (e: HttpDataSource.HttpDataSourceException) {
                lastErr = e
                upstreamOpen = true; closeUpstreamQuietly()
                if (p == 0L || attempts == 1) throw e
                onNote("bağlantı hatası")
            }
        }
        throw lastErr ?: IOException("Sunucu dosyanın ${p / 1048576} MB konumundan veri göndermedi")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val p = pre
        if (p != null) {
            if (prePos < preLen) {
                val n = minOf(length, preLen - prePos)
                System.arraycopy(p, prePos, buffer, offset, n)
                prePos += n; pos += n; chunkGot += n
                return n
            }
            pre = null
        }
        if (end != UNSET && pos >= end) return C.RESULT_END_OF_INPUT
        val r = upstream.read(buffer, offset, length)
        if (r == C.RESULT_END_OF_INPUT) {
            // Parçalı modda bu parça tam geldiyse sıradakini iste
            val more = useChunks && chunkReq > 0 && chunkGot >= chunkReq &&
                (end == UNSET || pos < end) && (total < 0 || pos < total)
            if (!more) return r
            closeUpstreamQuietly()
            openAt(pos, 3)
            return read(buffer, offset, length)
        }
        if (r > 0) { pos += r; chunkGot += r }
        return r
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        // Parçanın kalanı azsa okuyup at: yanıt tamamlanınca bağlantı havuza döner ve sıradaki istekte yeniden kullanılır
        if (upstreamOpen && useChunks && chunkReq > 0) {
            val unread = chunkReq - chunkGot - (if (pre != null) (preLen - prePos).toLong() else 0L)
            if (unread in 1..DRAIN_MAX) {
                try {
                    val buf = ByteArray(32 * 1024)
                    var left = unread
                    while (left > 0) {
                        val r = upstream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (r < 0) break
                        left -= r
                    }
                } catch (_: Exception) {}
            }
        }
        pre = null
        closeUpstreamQuietly()
    }

    private companion object {
        val RAMP = longArrayOf(64L * 1024, 256L * 1024, 1024L * 1024, 2L * 1024 * 1024)
        const val DRAIN_MAX = 2L * 1024 * 1024
    }
}
