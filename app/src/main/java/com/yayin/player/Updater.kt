package com.yayin.player

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Uygulama içi güncelleme:
 *  1) Kurulum izni var mı? Yoksa önce izin ekranı açılır (bir kerelik).
 *  2) APK indirilir (yarım kalan indirme kullanılmaz, tamamlanan dosya saklanır).
 *  3) Android'in kurulum ekranı açılır; açılmazsa uygulamadaki "Kur" düğmesiyle tekrar açılır.
 */
class Updater(private val act: MainActivity, private val emit: (String, JSONObject) -> Unit) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile private var busy = false
    private var ready: File? = null
    private var waitingPermission = false
    private var pendingUrl: String? = null
    private var pendingVersion = 0

    fun info(): String = JSONObject()
        .put("code", BuildConfig.VERSION_CODE)
        .put("name", BuildConfig.VERSION_NAME)
        .put("repo", BuildConfig.UPDATE_REPO)
        .put("canInstall", canInstall())
        .toString()

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || act.packageManager.canRequestPackageInstalls()

    private fun dir(): File? =
        (if (Build.VERSION.SDK_INT < 24) act.externalCacheDir else act.cacheDir)?.let { File(it, "updates").apply { mkdirs() } }

    /** İzin ekranını aç. Dönünce (onResume) izin verildiyse işlem kaldığı yerden devam eder. */
    fun requestPermission() {
        waitingPermission = true
        emit("upd", JSONObject().put("perm", true))
        if (Build.VERSION.SDK_INT < 26) return
        try {
            act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
        } catch (_: ActivityNotFoundException) {
            emit("upd", JSONObject().put("permManual", true))
        } catch (_: Exception) {
            emit("upd", JSONObject().put("permManual", true))
        }
    }

    fun download(url: String, version: Int) {
        if (busy) return
        if (!canInstall()) { pendingUrl = url; pendingVersion = version; requestPermission(); return }
        val d = dir() ?: run { emit("upd", JSONObject().put("err", "Depolama alanı yok")); return }
        val done = File(d, "yayin-$version.apk")
        if (done.exists() && done.length() > 500_000) { ready = done; emit("upd", JSONObject().put("ready", true)); install(); return }
        busy = true
        Thread {
            try {
                d.listFiles()?.forEach { if (it.name != done.name) it.delete() }   // eski indirmeleri temizle
                val part = File(d, "yayin-$version.part")
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("Sunucu ${r.code} hatası verdi")
                    val body = r.body ?: throw IllegalStateException("Boş yanıt")
                    val total = body.contentLength()
                    var got = 0L; var lastP = -1
                    body.byteStream().use { input ->
                        part.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                o.write(buf, 0, n); got += n
                                if (total > 0) {
                                    val p = (got * 100 / total).toInt()
                                    if (p != lastP && p % 5 == 0) { lastP = p; emit("upd", JSONObject().put("p", p)) }
                                }
                            }
                        }
                    }
                    if (total > 0 && got != total) throw IllegalStateException("İndirme yarım kaldı")
                }
                if (!part.renameTo(done)) throw IllegalStateException("Dosya kaydedilemedi")
                ready = done
                emit("upd", JSONObject().put("p", 100).put("ready", true))
                act.runOnUiThread { install() }
            } catch (e: Exception) {
                emit("upd", JSONObject().put("err", e.message ?: "İndirme başarısız"))
            } finally {
                busy = false
            }
        }.start()
    }

    /** İndirilen sürümün kurulum ekranını aç (uygulamadaki "Kur" düğmesi de bunu çağırır). */
    fun install() {
        val f = ready ?: dir()?.listFiles()?.filter { it.name.endsWith(".apk") }?.maxByOrNull { it.lastModified() }
        if (f == null || !f.exists()) { emit("upd", JSONObject().put("err", "Kurulacak dosya bulunamadı, tekrar indirin")); return }
        if (!canInstall()) { requestPermission(); return }
        val uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(act, "${act.packageName}.files", f) else Uri.fromFile(f)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            act.startActivity(i)
            emit("upd", JSONObject().put("installing", true))
        } catch (e: Exception) {
            emit("upd", JSONObject().put("err", "Kurulum ekranı açılamadı").put("ready", true))
        }
    }

    /** İzin ekranından ya da kurulum ekranından dönünce. */
    fun onResume() {
        if (waitingPermission) {
            if (!canInstall()) { emit("upd", JSONObject().put("permStill", true)); return }
            waitingPermission = false
            emit("upd", JSONObject().put("permOk", true))
            val u = pendingUrl
            if (u != null) { pendingUrl = null; download(u, pendingVersion) } else if (ready != null) install()
        } else if (ready != null) {
            emit("upd", JSONObject().put("back", true))
        }
    }
}
