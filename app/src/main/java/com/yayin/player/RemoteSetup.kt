package com.yayin.player

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

/**
 * Telefonla kurulum: TV'de aynı Wi-Fi ağından erişilebilen küçük bir web sunucusu açar.
 * Telefon QR kodu okutunca bir form açılır; gönderilen bilgiler TV'deki uygulamaya iletilir.
 * Bağlantı tek kullanımlık bir anahtarla korunur ve iş bitince sunucu kapanır.
 */
class RemoteSetup(private val onData: (String) -> Unit) {

    private var server: Server? = null
    private var token = ""

    /** Sunucuyu başlatır; {"url": "...", "qr": "data:image/png;base64,..."} döner. */
    fun start(): String {
        stop()
        val ip = localIp() ?: return "{}"
        token = UUID.randomUUID().toString().take(8)
        for (port in 8765..8775) {
            try {
                val s = Server(port)
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = s
                val url = "http://$ip:$port/?t=$token"
                return JSONObject().put("url", url).put("qr", qrDataUrl(url)).toString()
            } catch (_: Exception) { /* port meşgul, bir sonrakini dene */ }
        }
        return "{}"
    }

    fun stop() {
        server?.stop()
        server = null
    }

    private inner class Server(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            if (session.parameters["t"]?.firstOrNull() != token) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain; charset=utf-8", "Bu bağlantının süresi doldu. TV'deki QR kodu tekrar okutun.")
            }
            if (session.method == Method.POST) {
                val body = HashMap<String, String>()
                try { session.parseBody(body) } catch (_: Exception) {}
                val json = body["postData"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"ok\":false}")
                try { JSONObject(json) } catch (_: Exception) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"ok\":false}")
                }
                onData(json)
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}")
            }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", PAGE.replace("__TOKEN__", token))
        }
    }

    private fun localIp(): String? {
        val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
        val sorted = all.sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
        for (ni in sorted) for (a in ni.inetAddresses) {
            if (a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress
        }
        return null
    }

    private fun qrDataUrl(text: String): String {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 480, 480, mapOf(EncodeHintType.MARGIN to 1))
        val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565)
        for (x in 0 until m.width) for (y in 0 until m.height) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private val PAGE = """
<!DOCTYPE html><html lang="tr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>PersetTV · Telefonla kurulum</title>
<style>
*{box-sizing:border-box}body{margin:0;font:16px/1.5 system-ui,-apple-system,sans-serif;background:#140A0D;color:#F5ECEE;padding:24px 18px}
h1{font-size:26px;margin:0 0 4px}p{color:#B39CA1;margin:0 0 18px}
.tabs{display:flex;gap:4px;background:#0c0507;padding:4px;border-radius:12px;margin-bottom:16px}
.tabs button{flex:1;border:0;background:none;color:#B39CA1;padding:10px;border-radius:9px;font-weight:700;font-size:15px}
.tabs button.on{background:#2A161A;color:#fff}
label{display:block;font-weight:600;font-size:14px;margin:12px 0 6px}
input{width:100%;font-size:17px;padding:13px 14px;border-radius:12px;border:1px solid #3E2228;background:#0c0507;color:#fff}
.go{width:100%;margin-top:22px;padding:15px;border:0;border-radius:12px;background:#E3242B;color:#fff;font-size:17px;font-weight:800}
.ok{display:none;text-align:center;padding:40px 0}.ok b{font-size:60px;display:block}
.err{color:#FF6B70;margin-top:10px;min-height:1em}
</style></head><body>
<div id="form">
<h1>📺 PersetTV</h1><p>Bilgileri yazıp gönderin, TV'deki uygulama hemen giriş yapar.</p>
<div class="tabs"><button id="tx" class="on" type="button">Xtream Codes</button><button id="tm" type="button">M3U listesi</button></div>
<div id="px"><label>Sunucu adresi</label><input id="s" placeholder="http://ornek.com:8080" autocapitalize="off" autocorrect="off" spellcheck="false">
<label>Kullanıcı adı</label><input id="u" autocapitalize="off" autocorrect="off" spellcheck="false">
<label>Şifre</label><input id="pw" type="password"></div>
<div id="pm" style="display:none"><label>M3U liste bağlantısı</label><input id="m" placeholder="https://..." autocapitalize="off" autocorrect="off" spellcheck="false"></div>
<button class="go" id="go" type="button">TV'ye gönder</button><div class="err" id="err"></div>
</div>
<div class="ok" id="ok"><b>✅</b><h1>Gönderildi</h1><p>TV'ye bakabilirsiniz. Bu sayfayı kapatabilirsiniz.</p></div>
<script>
var mode='x',q=function(i){return document.getElementById(i)};
q('tx').onclick=function(){mode='x';q('tx').className='on';q('tm').className='';q('px').style.display='';q('pm').style.display='none'};
q('tm').onclick=function(){mode='m';q('tm').className='on';q('tx').className='';q('pm').style.display='';q('px').style.display='none'};
q('go').onclick=function(){
  var d=mode==='x'?{type:'xtream',server:q('s').value.trim(),user:q('u').value.trim(),pass:q('pw').value}:{type:'m3u',url:q('m').value.trim()};
  if(mode==='x'&&(!d.server||!d.user||!d.pass)){q('err').textContent='Üç alanı da doldurun.';return}
  if(mode==='m'&&!d.url){q('err').textContent='Liste bağlantısını yazın.';return}
  q('go').disabled=true;q('go').textContent='Gönderiliyor…';
  fetch('/send?t=__TOKEN__',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(d)})
   .then(function(r){if(!r.ok)throw 0;q('form').style.display='none';q('ok').style.display='block'})
   .catch(function(){q('err').textContent='Gönderilemedi. TV\'deki QR kodu yeniden okutun.';q('go').disabled=false;q('go').textContent='TV\'ye gönder'});
};
</script></body></html>
"""
    }
}
