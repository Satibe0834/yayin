package com.yayin.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Canlı yayını durdur / geri sar.
 *
 * Canlı MPEG-TS yayını TEK bağlantıyla indirilir ve cihazda 8 MB'lık parçalar halinde tutulur
 * (eski parçalar silinir). Oynatıcı yayını sunucudan değil bu yerel kopyadan okur; böylece
 * duraklatılabilir ve geri sarılabilir, sağlayıcıya ikinci bağlantı açılmaz.
 */
class Timeshift(
    private val dir: File,
    private val http: OkHttpClient,
    private val ua: String,
    private val keepMs: Long,
    private val onNoData: () -> Unit,
) {
    private val segSize = 8L * 1024 * 1024
    private val maxBytes = 1_500L * 1024 * 1024
    private val minFree = 400L * 1024 * 1024
    private val lock = Object()

    @Volatile var written = 0L; private set
    @Volatile var startOffset = 0L; private set
    @Volatile private var stopped = false
    @Volatile private var call: Call? = null
    @Volatile var gotData = false; private set

    private var out: FileOutputStream? = null
    private var curSeg = -1L
    private var firstSeg = 0L
    private val idxTime = ArrayList<Long>()
    private val idxOff = ArrayList<Long>()

    fun start(url: String) {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        Thread({ record(url) }, "yayin-timeshift").start()
    }

    fun stop() {
        stopped = true
        try { call?.cancel() } catch (_: Exception) {}
        synchronized(lock) { lock.notifyAll() }
        Thread {
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
            try { out?.close() } catch (_: Exception) {}
            dir.listFiles()?.forEach { it.delete() }
        }.start()
    }

    fun wake() { synchronized(lock) { lock.notifyAll() } }

    private fun record(url: String) {
        var fails = 0
        val buf = ByteArray(64 * 1024)
        while (!stopped) {
            try {
                val c = http.newCall(Request.Builder().url(url).header("User-Agent", ua).build())
                call = c
                c.execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val inp = r.body?.byteStream() ?: throw IOException("Boş yanıt")
                    while (!stopped) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        if (n > 0) { write(buf, n); fails = 0 }
                    }
                }
            } catch (_: Exception) {
                if (stopped) break
                fails++
            }
            if (stopped) break
            if (!gotData && fails >= 3) { onNoData(); break }
            try { Thread.sleep(if (fails > 0) 1000L else 200L) } catch (_: InterruptedException) { break }
        }
    }

    private fun write(b: ByteArray, n: Int) {
        var off = 0
        while (off < n) {
            val seg = written / segSize
            if (seg != curSeg) {
                try { out?.close() } catch (_: Exception) {}
                out = FileOutputStream(File(dir, "s$seg"))
                curSeg = seg
            }
            val room = (segSize - written % segSize).toInt()
            val k = minOf(room, n - off)
            out!!.write(b, off, k)
            off += k
            synchronized(lock) { written += k; lock.notifyAll() }
        }
        gotData = true
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (idxTime.isEmpty() || now - idxTime[idxTime.size - 1] >= 500) { idxTime.add(now); idxOff.add(written) }
            prune(now)
        }
    }

    /** Süresi dolan ya da yer kaplayan en eski parçaları sil. lock tutulurken çağrılır. */
    private fun prune(now: Long) {
        var keepFrom = startOffset
        var i = 0
        while (i < idxTime.size - 1 && now - idxTime[i] > keepMs) i++
        if (i > 0) keepFrom = maxOf(keepFrom, idxOff[i])
        if (written - keepFrom > maxBytes) keepFrom = written - maxBytes
        val lowSpace = try { dir.usableSpace < minFree } catch (_: Exception) { false }
        if (lowSpace) keepFrom = maxOf(keepFrom, (curSeg - 1).coerceAtLeast(0) * segSize)
        while (firstSeg < curSeg && (firstSeg + 1) * segSize <= keepFrom) {
            File(dir, "s$firstSeg").delete()
            firstSeg++
        }
        startOffset = firstSeg * segSize
        while (idxOff.size > 1 && idxOff[0] < startOffset) { idxOff.removeAt(0); idxTime.removeAt(0) }
    }

    fun oldestWall(): Long = synchronized(lock) { if (idxTime.isEmpty()) System.currentTimeMillis() else idxTime[0] }

    /** Verilen saate denk gelen dosya konumu (TS paket sınırına hizalı). */
    fun offsetAt(wall: Long): Long = synchronized(lock) {
        var off = startOffset
        for (i in idxTime.indices) { if (idxTime[i] <= wall) off = idxOff[i] else break }
        off = off.coerceIn(startOffset, written)
        off - off % 188
    }

    fun wallAt(offset: Long): Long = synchronized(lock) {
        for (i in idxOff.indices) if (idxOff[i] >= offset) return@synchronized idxTime[i]
        if (idxTime.isEmpty()) System.currentTimeMillis() else idxTime[idxTime.size - 1]
    }

    /**
     * pos konumundan oku; veri yoksa gelene kadar bekle.
     * Dönüş: (gerçek okuma konumu, okunan bayt). Konum -2 ise akış bitti / kapatıldı.
     */
    fun readAt(p0: Long, dst: ByteArray, off: Int, len: Int, closed: () -> Boolean): Pair<Long, Int> {
        var p = p0
        synchronized(lock) {
            try {
                while (!stopped && !closed() && written <= p) lock.wait(500)
            } catch (_: InterruptedException) {
                return -2L to 0
            }
            if (closed() || (stopped && written <= p)) return -2L to 0
            if (p < startOffset) p = startOffset
        }
        val avail = written - p
        if (avail <= 0) return p to 0
        val seg = p / segSize
        val inSeg = p % segSize
        val k = minOf(len.toLong(), avail, segSize - inSeg).toInt()
        return try {
            RandomAccessFile(File(dir, "s$seg"), "r").use { raf ->
                raf.seek(inSeg)
                val n = raf.read(dst, off, k)
                p to maxOf(n, 0)
            }
        } catch (_: IOException) {
            startOffset to 0
        }
    }
}

/** Oynatıcının yerel kopyadan okumasını sağlayan veri kaynağı: timeshift://live?o=<konum> */
@OptIn(UnstableApi::class)
class TimeshiftDataSource(private val ts: Timeshift) : DataSource {
    private var uri: Uri? = null
    private var pos = 0L
    @Volatile private var closed = false

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        closed = false
        val base = dataSpec.uri.getQueryParameter("o")?.toLongOrNull() ?: 0L
        pos = base + dataSpec.position
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (closed || Thread.currentThread().isInterrupted) return C.RESULT_END_OF_INPUT
            val (p, n) = ts.readAt(pos, buffer, offset, length) { closed }
            if (p == -2L) return C.RESULT_END_OF_INPUT
            pos = p
            if (n > 0) { pos += n; return n }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closed = true
        ts.wake()
    }
}
