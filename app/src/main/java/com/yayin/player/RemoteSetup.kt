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
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", PAGE.replace("__TOKEN__", token).replace("__LANG__", ""))
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
<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>PersetTV</title>
<style>
*{box-sizing:border-box}body{margin:0;font:16px/1.5 system-ui,-apple-system,sans-serif;background:#140A0D;color:#F5ECEE;padding:24px 18px}
h1{font-size:26px;margin:0 0 4px}p{color:#B39CA1;margin:0 0 18px}
.tabs{display:flex;gap:4px;background:#0c0507;padding:4px;border-radius:12px;margin-bottom:16px}
.tabs button{flex:1;border:0;background:none;color:#B39CA1;padding:10px;border-radius:9px;font-weight:700;font-size:15px}
.tabs button.on{background:#2A161A;color:#fff}
label{display:block;font-weight:600;font-size:14px;margin:12px 0 6px}
input{width:100%;font-size:17px;padding:13px 14px;border-radius:12px;border:1px solid #3E2228;background:#0c0507;color:#fff}
.go{width:100%;margin-top:22px;padding:15px;border:0;border-radius:12px;background:#D00D33;color:#fff;font-size:17px;font-weight:800}
.ok{display:none;text-align:center;padding:40px 0}.ok b{font-size:60px;display:block}
.err{color:#FF6B70;margin-top:10px;min-height:1em}
.tv{color:#D00D33}
</style></head><body>
<div id="form">
<h1>📺 Perset<span class="tv">TV</span></h1><p data-k="sub"></p>
<div class="tabs"><button id="tx" class="on" type="button">Xtream Codes</button><button id="tm" type="button" data-k="m3u"></button></div>
<div id="px"><label data-k="srv"></label><input id="s" placeholder="http://example.com:8080" autocapitalize="off" autocorrect="off" spellcheck="false">
<label data-k="usr"></label><input id="u" autocapitalize="off" autocorrect="off" spellcheck="false">
<label data-k="pw"></label><input id="pw" type="password"></div>
<div id="pm" style="display:none"><label data-k="link"></label><input id="m" placeholder="https://..." autocapitalize="off" autocorrect="off" spellcheck="false"></div>
<button class="go" id="go" type="button" data-k="send"></button><div class="err" id="err"></div>
</div>
<div class="ok" id="ok"><b>✅</b><h1 data-k="sent"></h1><p data-k="sentSub"></p></div>
<script>
var L={
 tr:{sub:"Bilgileri yazıp gönderin, TV'deki uygulama hemen giriş yapar.",m3u:"M3U listesi",srv:"Sunucu adresi",usr:"Kullanıcı adı",pw:"Şifre",link:"M3U liste bağlantısı",send:"TV'ye gönder",sending:"Gönderiliyor…",sent:"Gönderildi",sentSub:"TV'ye bakabilirsiniz. Bu sayfayı kapatabilirsiniz.",fill3:"Üç alanı da doldurun.",fillLink:"Liste bağlantısını yazın.",fail:"Gönderilemedi. TV'deki QR kodu yeniden okutun."},
 de:{sub:"Daten eingeben und senden – die App auf dem Fernseher meldet sich sofort an.",m3u:"M3U-Liste",srv:"Serveradresse",usr:"Benutzername",pw:"Passwort",link:"M3U-Listen-Link",send:"An den Fernseher senden",sending:"Wird gesendet…",sent:"Gesendet",sentSub:"Schauen Sie auf den Fernseher. Sie können diese Seite schließen.",fill3:"Bitte alle drei Felder ausfüllen.",fillLink:"Bitte den Listen-Link eingeben.",fail:"Senden fehlgeschlagen. Scannen Sie den QR-Code auf dem Fernseher erneut."},
 en:{sub:"Enter your details and send – the TV app signs in right away.",m3u:"M3U list",srv:"Server address",usr:"Username",pw:"Password",link:"M3U list link",send:"Send to TV",sending:"Sending…",sent:"Sent",sentSub:"Check your TV. You can close this page.",fill3:"Please fill in all three fields.",fillLink:"Please enter the list link.",fail:"Couldn't send. Scan the QR code on the TV again."},
 es:{sub:"Escribe tus datos y envíalos: la app de la TV inicia sesión al instante.",m3u:"Lista M3U",srv:"Dirección del servidor",usr:"Usuario",pw:"Contraseña",link:"Enlace de la lista M3U",send:"Enviar a la TV",sending:"Enviando…",sent:"Enviado",sentSub:"Mira la TV. Puedes cerrar esta página.",fill3:"Rellena los tres campos.",fillLink:"Escribe el enlace de la lista.",fail:"No se pudo enviar. Vuelve a escanear el QR de la TV."},
 fr:{sub:"Saisissez vos informations et envoyez : l'app TV se connecte aussitôt.",m3u:"Liste M3U",srv:"Adresse du serveur",usr:"Identifiant",pw:"Mot de passe",link:"Lien de la liste M3U",send:"Envoyer à la TV",sending:"Envoi…",sent:"Envoyé",sentSub:"Regardez la TV. Vous pouvez fermer cette page.",fill3:"Remplissez les trois champs.",fillLink:"Saisissez le lien de la liste.",fail:"Échec de l'envoi. Rescannez le QR code de la TV."},
 it:{sub:"Inserisci i dati e invia: l'app sulla TV accede subito.",m3u:"Lista M3U",srv:"Indirizzo server",usr:"Nome utente",pw:"Password",link:"Link lista M3U",send:"Invia alla TV",sending:"Invio…",sent:"Inviato",sentSub:"Guarda la TV. Puoi chiudere questa pagina.",fill3:"Compila tutti e tre i campi.",fillLink:"Inserisci il link della lista.",fail:"Invio non riuscito. Inquadra di nuovo il QR sulla TV."},
 ru:{sub:"Введите данные и отправьте — приложение на ТВ сразу войдёт.",m3u:"Список M3U",srv:"Адрес сервера",usr:"Имя пользователя",pw:"Пароль",link:"Ссылка на список M3U",send:"Отправить на ТВ",sending:"Отправка…",sent:"Отправлено",sentSub:"Посмотрите на ТВ. Эту страницу можно закрыть.",fill3:"Заполните все три поля.",fillLink:"Введите ссылку на список.",fail:"Не удалось отправить. Снова отсканируйте QR-код на ТВ."},
 sq:{sub:"Shkruaj të dhënat dhe dërgoji – aplikacioni në TV hyn menjëherë.",m3u:"Lista M3U",srv:"Adresa e serverit",usr:"Emri i përdoruesit",pw:"Fjalëkalimi",link:"Lidhja e listës M3U",send:"Dërgo në TV",sending:"Po dërgohet…",sent:"U dërgua",sentSub:"Shiko TV-në. Mund ta mbyllësh këtë faqe.",fill3:"Plotëso të tria fushat.",fillLink:"Shkruaj lidhjen e listës.",fail:"Dërgimi dështoi. Skano sërish kodin QR në TV."}};
var lang='__LANG__';
if(!L[lang]){var n=(navigator.language||'de').slice(0,2).toLowerCase();lang=L[n]?n:'de';}
var T=L[lang];document.documentElement.lang=lang;
var els=document.querySelectorAll('[data-k]');for(var i=0;i<els.length;i++){els[i].textContent=T[els[i].getAttribute('data-k')]||'';}
var mode='x',q=function(i){return document.getElementById(i)};
q('tx').onclick=function(){mode='x';q('tx').className='on';q('tm').className='';q('px').style.display='';q('pm').style.display='none'};
q('tm').onclick=function(){mode='m';q('tm').className='on';q('tx').className='';q('pm').style.display='';q('px').style.display='none'};
q('go').onclick=function(){
  var d=mode==='x'?{type:'xtream',server:q('s').value.trim(),user:q('u').value.trim(),pass:q('pw').value}:{type:'m3u',url:q('m').value.trim()};
  if(mode==='x'&&(!d.server||!d.user||!d.pass)){q('err').textContent=T.fill3;return}
  if(mode==='m'&&!d.url){q('err').textContent=T.fillLink;return}
  q('go').disabled=true;q('go').textContent=T.sending;
  fetch('/send?t=__TOKEN__',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(d)})
   .then(function(r){if(!r.ok)throw 0;q('form').style.display='none';q('ok').style.display='block'})
   .catch(function(){q('err').textContent=T.fail;q('go').disabled=false;q('go').textContent=T.send});
};
</script></body></html>
"""
    }
}
