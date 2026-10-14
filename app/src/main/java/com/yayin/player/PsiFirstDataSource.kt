package com.yayin.player

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Canlı MPEG-TS kanallarında hızlı açılış: akışın başında okunan veride PAT+PMT'yi bulup en başa koyar.
 * Böylece sunucunun kanal açılınca hemen gönderdiği ilk tam kare atılmaz (bkz. TsPsi).
 * Bulamazsa okuduğu veriyi hiç değiştirmeden aynen geçirir.
 */
@OptIn(UnstableApi::class)
class PsiFirstDataSource(
    private val upstream: DataSource,
    private val shouldFix: (Uri) -> Boolean,
) : DataSource {

    private var pending: ByteArray? = null
    private var pendingLen = 0
    private var pendingPos = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        pending = null; pendingLen = 0; pendingPos = 0
        if (dataSpec.position != 0L || length != C.LENGTH_UNSET.toLong() || !shouldFix(dataSpec.uri)) return length

        var data = ByteArray(256 * 1024)
        var len = 0
        var nextScan = 32 * 1024
        var prefix: ByteArray? = null
        val deadline = SystemClock.elapsedRealtime() + SCAN_MS
        while (len < MAX_SCAN && SystemClock.elapsedRealtime() < deadline) {
            if (len == data.size) data = data.copyOf(data.size * 2)
            val n = upstream.read(data, len, minOf(32 * 1024, data.size - len))
            if (n == C.RESULT_END_OF_INPUT) break
            if (n > 0) len += n
            if (len >= nextScan) {
                prefix = TsPsi.findPatPmt(data, len)
                if (prefix != null) break
                if (len >= 4 * TsPsi.PACKET && TsPsi.syncOffset(data, len) < 0) break   // MPEG-TS değil
                nextScan = len + 64 * 1024
            }
        }
        val p = prefix
        if (p != null) {
            val out = ByteArray(p.size + len)
            System.arraycopy(p, 0, out, 0, p.size)
            System.arraycopy(data, 0, out, p.size, len)
            pending = out; pendingLen = out.size
        } else {
            pending = data; pendingLen = len
        }
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val p = pending
        if (p != null) {
            if (pendingPos < pendingLen) {
                val n = minOf(length, pendingLen - pendingPos)
                System.arraycopy(p, pendingPos, buffer, offset, n)
                pendingPos += n
                return n
            }
            pending = null
        }
        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        pending = null
        upstream.close()
    }

    private companion object {
        const val MAX_SCAN = 3 * 1024 * 1024     // en fazla 3 MB ileri bak
        const val SCAN_MS = 2_500L               // en fazla 2,5 sn bekle (sonra olduğu gibi devam)
    }
}
