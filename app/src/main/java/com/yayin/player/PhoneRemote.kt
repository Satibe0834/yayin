package com.yayin.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

/** Ev ağı yardımcıları: yerel IP ve QR kod. */
object Lan {
    fun localIp(): String? {
        val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
        val sorted = all.sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
        for (ni in sorted) for (a in ni.inetAddresses) if (a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress
        return null
    }

    fun qrDataUrl(text: String): String {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 480, 480, mapOf(EncodeHintType.MARGIN to 1))
        val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565)
        for (x in 0 until m.width) for (y in 0 until m.height) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

/**
 * Telefon kumandası: TV'de yalnızca ev ağından erişilen küçük bir sunucu.
 * Telefon "/" adresinden kumanda sayfasını alır, "/api" ile komut gönderir.
 * Her istek kalıcı bir bağlantı koduyla (token) korunur; kod yenilenince eski telefonlar bağlanamaz.
 */
class PhoneRemote(private val ctx: Context, private val handle: (String) -> String) {

    private val prefs = ctx.getSharedPreferences("yayin", Context.MODE_PRIVATE)
    private var server: Srv? = null
    @Volatile var token: String = prefs.getString("phoneToken", null) ?: newTokenInternal()
        private set

    private fun newTokenInternal(): String {
        val t = UUID.randomUUID().toString().replace("-", "").take(10)
        prefs.edit().putString("phoneToken", t).apply()
        return t
    }

    fun newToken(): String { token = newTokenInternal(); return info() }

    /** Sunucuyu başlat (zaten çalışıyorsa dokunma); {"url","qr"} döner. */
    fun start(): String {
        if (server == null) {
            for (port in 8780..8790) {
                try {
                    val s = Srv(port)
                    s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                    server = s
                    break
                } catch (_: Exception) {}
            }
        }
        return info()
    }

    private fun info(): String {
        val s = server ?: return "{}"
        val ip = Lan.localIp() ?: return "{}"
        val url = "http://$ip:${s.listeningPort}/?t=$token"
        return JSONObject().put("url", url).put("qr", Lan.qrDataUrl(url)).toString()
    }

    fun stop() {
        server?.stop()
        server = null
    }

    private inner class Srv(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            return when (session.uri) {
                "/", "/index.html" -> {
                    val html = try { ctx.assets.open("remote.html").bufferedReader().use { it.readText() } } catch (_: Exception) { "Yayın" }
                    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html).also { it.addHeader("Cache-Control", "no-store") }
                }
                "/api" -> {
                    if (session.parameters["t"]?.firstOrNull() != token) {
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"token\"}")
                    }
                    val body = HashMap<String, String>()
                    try { session.parseBody(body) } catch (_: Exception) {}
                    val raw = body["postData"] ?: "{}"
                    val safe = try { JSONObject(raw).toString() } catch (_: Exception) { "{}" }
                    newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", handle(safe)).also { it.addHeader("Cache-Control", "no-store") }
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
            }
        }
    }
}
