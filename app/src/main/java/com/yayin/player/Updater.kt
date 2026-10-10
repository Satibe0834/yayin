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
 * Uygulama içi güncelleme: GitHub'daki en son APK'yı indirir ve Android'in kurulum ekranını açar.
 * Kurulum için kullanıcının bir kez "bilinmeyen uygulamaları yükleme" iznini vermesi gerekir.
 */
class Updater(private val act: MainActivity, private val emit: (String, JSONObject) -> Unit) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile private var busy = false
    private var pending: File? = null

    fun info(): String = JSONObject()
        .put("code", BuildConfig.VERSION_CODE)
        .put("name", BuildConfig.VERSION_NAME)
        .put("repo", BuildConfig.UPDATE_REPO)
        .toString()

    fun download(url: String) {
        if (busy) return
        busy = true
        Thread {
            try {
                val dir = (if (Build.VERSION.SDK_INT < 24) act.externalCacheDir else act.cacheDir)?.let { File(it, "updates") }
                    ?: throw IllegalStateException("Depolama alanı yok")
                dir.mkdirs()
                val out = File(dir, "yayin.apk")
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("Sunucu ${r.code} hatası verdi")
                    val body = r.body ?: throw IllegalStateException("Boş yanıt")
                    val total = body.contentLength()
                    var done = 0L
                    var lastP = -1
                    body.byteStream().use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val p = (done * 100 / total).toInt()
                                    if (p != lastP && p % 5 == 0) { lastP = p; emit("upd", JSONObject().put("p", p)) }
                                }
                            }
                        }
                    }
                }
                emit("upd", JSONObject().put("p", 100).put("downloaded", true))
                act.runOnUiThread { install(out) }
            } catch (e: Exception) {
                emit("upd", JSONObject().put("err", e.message ?: "İndirme başarısız"))
            } finally {
                busy = false
            }
        }.start()
    }

    private fun install(f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            pending = f
            emit("upd", JSONObject().put("perm", true))
            try {
                act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
            } catch (_: ActivityNotFoundException) {
                emit("upd", JSONObject().put("permManual", true))
            }
            return
        }
        val uri = if (Build.VERSION.SDK_INT >= 24)
            FileProvider.getUriForFile(act, "${act.packageName}.files", f)
        else Uri.fromFile(f)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            act.startActivity(i)
            emit("upd", JSONObject().put("installing", true))
        } catch (e: Exception) {
            emit("upd", JSONObject().put("err", "Kurulum ekranı açılamadı"))
        }
    }

    /** İzin ekranından dönünce, izin verildiyse kuruluma kaldığı yerden devam et. */
    fun onResume() {
        val f = pending ?: return
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) return
        pending = null
        install(f)
    }
}
