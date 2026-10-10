package com.yayin.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.webkit.WebResourceResponse
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Kanal logolarını ve film/dizi afişlerini indirip küçültür.
 * Bazı sağlayıcılar 2000–4000 piksellik görseller verir; Fire Stick gibi az bellekli
 * cihazlarda bunlar görüntü motorunu çökertir. Burada her görsel en fazla ~2×MAX piksele
 * indirilir ve 60 MB'lık disk önbelleğinde saklanır (sonraki açılışlar daha hızlı olur).
 */
class ImageShrinker(cacheDir: File) {

    private val http = OkHttpClient.Builder()
        .cache(Cache(File(cacheDir, "images"), 60L * 1024 * 1024))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun load(url: String): WebResourceResponse? {
        return try {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android) Yayin").build()).execute().use { r ->
                if (!r.isSuccessful) return empty(r.code)
                val bytes = r.body?.bytes() ?: return null
                val type = r.header("Content-Type") ?: "image/jpeg"

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ok(bytes, type) // SVG vb.

                var sample = 1
                while (bounds.outWidth / (sample * 2) >= MAX && bounds.outHeight / (sample * 2) >= MAX) sample *= 2
                if (sample == 1 && bytes.size < 300_000) return ok(bytes, type)

                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample }) ?: return ok(bytes, type)
                val out = ByteArrayOutputStream()
                @Suppress("DEPRECATION")
                val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                bmp.compress(fmt, 82, out)
                bmp.recycle()
                ok(out.toByteArray(), "image/webp")
            }
        } catch (_: OutOfMemoryError) {
            empty(500)
        } catch (_: Exception) {
            null // görüntü motoru kendisi denesin
        }
    }

    private fun ok(b: ByteArray, type: String) =
        WebResourceResponse(type.substringBefore(';'), null, ByteArrayInputStream(b))

    private fun empty(code: Int) =
        WebResourceResponse("image/png", null, if (code in 100..599 && code != 200) code else 404, "Yok",
            mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))

    private companion object { const val MAX = 360 }
}
