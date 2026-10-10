package com.yayin.player

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import org.json.JSONObject

/**
 * Google TV / Android TV ana ekranındaki "İzlemeye devam et" satırı.
 * Yarım kalan film ve bölümler buraya eklenir; tıklanınca uygulama kaldığı yerden açar.
 */
class WatchNext(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("watch_next", Context.MODE_PRIVATE)
    private val supported = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun update(json: String) {
        if (!supported) return
        try {
            val o = JSONObject(json)
            val id = o.getString("id")
            val link = Uri.parse("yayin://open").buildUpon().appendQueryParameter("u", id).build()
            val b = WatchNextProgram.Builder()
                .setType(if (o.optString("type") == "episode") TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
                .setTitle(o.optString("title"))
                .setDescription(o.optString("sub"))
                .setIntentUri(link)
                .setInternalProviderId(id)
                .setLastPlaybackPositionMillis(o.optLong("pos").coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                .setDurationMillis(o.optLong("dur").coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            o.optString("poster").takeIf { it.startsWith("http") }?.let { b.setPosterArtUri(Uri.parse(it)) }
            val values = b.build().toContentValues()
            val existing = prefs.getLong(id, -1L)
            if (existing >= 0) {
                val n = ctx.contentResolver.update(TvContractCompat.buildWatchNextProgramUri(existing), values, null, null)
                if (n > 0) return
            }
            val uri = ctx.contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values) ?: return
            prefs.edit().putLong(id, ContentUris.parseId(uri)).apply()
        } catch (_: Exception) { /* TV sağlayıcısı olmayan cihazlar */ }
    }

    fun remove(id: String) {
        if (!supported) return
        val existing = prefs.getLong(id, -1L)
        if (existing < 0) return
        try { ctx.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(existing), null, null) } catch (_: Exception) {}
        prefs.edit().remove(id).apply()
    }
}
