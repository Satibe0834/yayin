package com.yayin.player

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Tek bir ExoPlayer örneğiyle hem canlı TV hem film/dizi oynatır.
 *
 * Canlı TV: video, saydam WebView'in ARKASINDA, sayfadaki oynatıcı alanının
 * konumunda durur; kanal listesi ve OSD web arayüzünden üstte görünür.
 * Film/dizi: video tam ekran ÖNE gelir ve Media3'ün kendi kontrolleri
 * (sarma çubuğu, ses/altyazı seçimi, sonraki bölüm) kullanılır.
 */
@OptIn(UnstableApi::class)
class NativePlayer(
    private val activity: MainActivity,
    private val root: FrameLayout,
    private val web: WebView,
) {
    enum class Mode { NONE, LIVE, VOD }

    var mode = Mode.NONE
        private set

    private val main = Handler(Looper.getMainLooper())
    private var vodItems = JSONArray()
    private var liveRetries = 0
    private var wasPlayingOnPause = false

    // Bağlantıları açık tutan ortak HTTP istemcisi: komşu kanallara ön bağlantı
    // ve oynatıcı aynı bağlantı havuzunu kullanır, böylece geçişte TCP/TLS kurulumu beklenmez.
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val defaultUserAgent = "Yayin/${BuildConfig.VERSION_NAME} (Linux; Android) ExoPlayer"
    private var userAgent = defaultUserAgent

    // Ayarlar ekranından gelen değerler
    private var bufferProfile = "fast"
    private var ffmpegMode = "on"
    private var audioLang = ""
    private var subLang = ""
    private var subScale = 1f

    var player: ExoPlayer = buildPlayer()
        private set

    private fun buildPlayer(handleAudioFocus: Boolean = true): ExoPlayer {
        val dataSource = OkHttpDataSource.Factory(http).setUserAgent(userAgent)

        // MPEG-TS: anahtar kare beklemeden görüntüye başla (kanal geçişini hızlandırır)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            .setConstantBitrateSeekingEnabled(true)

        // Önce cihazın donanım çözücüsü; desteklemediği ses biçimleri (AC3, E-AC3, DTS,
        // MP2, TrueHD...) için FFmpeg devreye girer. Çözücü açılamazsa bir sonrakine geçer.
        val renderers = DefaultRenderersFactory(activity)
            .setExtensionRendererMode(
                if (ffmpegMode == "prefer") DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
            .setEnableDecoderFallback(true)

        // Arabellek profili: hızlı başlangıçta 0,5 sn veri gelince oynatmaya başlar
        val (minB, maxB, startB, rebufB) = when (bufferProfile) {
            "stable" -> listOf(30_000, 120_000, 2_500, 5_000)
            "balanced" -> listOf(20_000, 60_000, 1_000, 3_000)
            else -> listOf(15_000, 50_000, 500, 2_000)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minB, maxB, startB, rebufB)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(activity, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                handleAudioFocus
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    private val titleText = TextView(activity).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        paint.isFakeBoldText = true
    }
    private val subText = TextView(activity).apply {
        setTextColor(Color.argb(190, 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }
    private val titleBar = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(24)
        setPadding(pad, dp(18), pad, dp(40))
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(220, 0, 0, 0), Color.TRANSPARENT)
        )
        addView(titleText); addView(subText)
        visibility = View.GONE
    }

    val view: PlayerView = PlayerView(activity).apply {
        player = this@NativePlayer.player
        useController = false
        setKeepContentOnPlayerReset(true)        // geçişte siyah ekran yerine son kare
        setShutterBackgroundColor(Color.BLACK)
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        isFocusable = false
        visibility = View.GONE
        overlayFrameLayout?.addView(titleBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            titleBar.visibility = if (mode == Mode.VOD) v else View.GONE
        })
    }

    private val progressTick = object : Runnable {
        override fun run() {
            if (mode == Mode.VOD) {
                if (player.isPlaying) emitProgress()
                main.postDelayed(this, 5_000)
            }
        }
    }

    private val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> emit("buffering")
                    Player.STATE_READY -> { liveRetries = 0; if (player.playWhenReady) emit("playing") }
                    Player.STATE_ENDED -> if (mode == Mode.VOD) closeVod() else emit("ended")
                    else -> {}
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                emit(if (isPlaying) "playing" else "paused")
                if (mode == Mode.VOD && !isPlaying) emitProgress()
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if (mode != Mode.VOD) return
                val i = player.currentMediaItemIndex
                updateTitle(i)
                emit("item", JSONObject().put("index", i).put("url", urlAt(i)))
            }

            override fun onPlayerError(error: PlaybackException) {
                if (mode == Mode.LIVE) {
                    // Canlı yayında geride kalma ya da kısa ağ kopmasında sessizce yeniden bağlan
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW || liveRetries < 2) {
                        liveRetries++
                        main.postDelayed({
                            if (mode == Mode.LIVE) { player.seekToDefaultPosition(); player.prepare() }
                        }, if (liveRetries == 1) 300L else 1_500L)
                        return
                    }
                    emit("error", JSONObject().put("code", error.errorCodeName))
                } else if (mode == Mode.VOD) {
                    showVodError(error)
                }
            }
        }

    init {
        root.addView(view, 0, FrameLayout.LayoutParams(0, 0))
        player.addListener(listener)
    }

    // ---------------- Ayarlar ----------------

    fun setAspect(mode: String) {
        view.resizeMode = when (mode) {
            "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    fun applyConfig(json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        val newBuffer = o.optString("buffer", "fast")
        val newFfmpeg = o.optString("ffmpeg", "on")
        val newUa = o.optString("ua", "").trim().ifBlank { defaultUserAgent }
        audioLang = o.optString("audioLang", "")
        subLang = o.optString("subLang", "")
        subScale = when (o.optString("subSize", "normal")) { "small" -> 0.8f; "large" -> 1.3f; "xlarge" -> 1.6f; else -> 1f }

        val needRebuild = newBuffer != bufferProfile || newFfmpeg != ffmpegMode || newUa != userAgent
        bufferProfile = newBuffer; ffmpegMode = newFfmpeg; userAgent = newUa
        if (needRebuild && mode != Mode.VOD) rebuild()
        applyTracks()
        view.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subScale)
    }

    private fun applyTracks() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(audioLang.ifBlank { null })
            .setPreferredTextLanguage(if (subLang.isBlank() || subLang == "off") null else subLang)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subLang == "off")
            .build()
    }

    /** Arabellek / çözücü / User-Agent değişince oynatıcıyı yeniden kurar; canlı yayın kaldığı kanaldan devam eder. */
    private fun rebuild() {
        val liveUrl = if (mode == Mode.LIVE) player.currentMediaItem?.localConfiguration?.uri?.toString() else null
        val vol = player.volume
        player.removeListener(listener)
        player.release()
        player = buildPlayer()
        player.addListener(listener)
        player.volume = vol
        view.player = player
        if (liveUrl != null) playLive(liveUrl)
    }

    // ---------------- Canlı TV ----------------

    fun playLive(url: String) {
        if (mode == Mode.VOD) closeVod(notify = false)
        // Aynı kanal zaten oynuyorsa (ör. ana menü arka planından Canlı TV'ye geçiş) yeniden yükleme
        if (mode == Mode.LIVE && player.playbackState != Player.STATE_IDLE &&
            player.currentMediaItem?.localConfiguration?.uri?.toString() == url) {
            view.visibility = View.VISIBLE
            player.playWhenReady = true
            if (player.playbackState == Player.STATE_READY) emit("playing")
            return
        }
        mode = Mode.LIVE
        liveRetries = 0
        view.visibility = View.VISIBLE
        player.setMediaItem(mediaItem(url))
        player.prepare()
        player.playWhenReady = true
    }

    /** Web arayüzündeki oynatıcı alanının konumu (CSS piksel). */
    fun setRect(x: Float, y: Float, w: Float, h: Float) {
        if (mode == Mode.VOD) return
        val d = activity.resources.displayMetrics.density
        view.layoutParams = FrameLayout.LayoutParams((w * d).toInt(), (h * d).toInt()).apply {
            leftMargin = (x * d).toInt()
            topMargin = (y * d).toInt()
        }
    }

    fun stop() {
        if (mode == Mode.VOD) return
        mode = Mode.NONE
        player.stop()
        player.clearMediaItems()
        view.visibility = View.GONE
    }

    fun toggle() { player.playWhenReady = !player.playWhenReady }
    fun setMuted(m: Boolean) { player.volume = if (m) 0f else lastVolume }
    private var lastVolume = 1f
    fun setVolume(v: Float) { lastVolume = v.coerceIn(0f, 1f); player.volume = lastVolume }

    /** Komşu kanalın sunucusuna önceden bağlan: DNS + TCP + TLS hazır olsun. */
    fun preconnect(url: String) {
        val req = try {
            Request.Builder().url(url).header("User-Agent", userAgent).header("Range", "bytes=0-1023").build()
        } catch (_: Exception) { return }
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    // ---------------- Film / dizi ----------------

    fun playVod(json: String, index: Int, startMs: Long) {
        vodItems = try { JSONArray(json) } catch (_: Exception) { return }
        if (vodItems.length() == 0) return
        mode = Mode.VOD
        val items = (0 until vodItems.length()).map { i ->
            val o = vodItems.getJSONObject(i)
            mediaItem(o.getString("url")).buildUpon()
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(o.optString("title")).setSubtitle(o.optString("sub")).build()
                ).build()
        }
        // Tam ekran, öne al, kendi kontrolleriyle
        root.removeView(view)
        root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        web.visibility = View.INVISIBLE
        view.apply {
            visibility = View.VISIBLE
            useController = true
            controllerShowTimeoutMs = 3_500
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShowNextButton(items.size > 1)
            setShowPreviousButton(items.size > 1)
            isFocusable = true
            requestFocus()
            showController()
        }
        updateTitle(index)
        player.setMediaItems(items, index.coerceIn(0, items.size - 1), startMs)
        player.prepare()
        player.playWhenReady = true
        main.removeCallbacks(progressTick)
        main.postDelayed(progressTick, 5_000)
    }

    fun closeVod(notify: Boolean = true) {
        if (mode != Mode.VOD) return
        emitProgress()
        main.removeCallbacks(progressTick)
        mode = Mode.NONE
        player.stop()
        player.clearMediaItems()
        view.apply {
            hideController()
            useController = false
            isFocusable = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            visibility = View.GONE
        }
        titleBar.visibility = View.GONE
        root.removeView(view)
        root.addView(view, 0, FrameLayout.LayoutParams(0, 0))
        web.visibility = View.VISIBLE
        web.requestFocus()
        if (notify) emit("closed")
    }

    fun seekBy(ms: Long) {
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + ms).coerceIn(0, dur))
        view.showController()
    }

    fun next() { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
    fun previous() { if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() }

    private fun updateTitle(i: Int) {
        val o = vodItems.optJSONObject(i) ?: return
        titleText.text = o.optString("title")
        subText.text = o.optString("sub")
    }

    private fun urlAt(i: Int) = vodItems.optJSONObject(i)?.optString("url") ?: ""

    private fun emitProgress() {
        val dur = player.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        emit("progress", JSONObject()
            .put("url", urlAt(player.currentMediaItemIndex))
            .put("pos", player.currentPosition)
            .put("dur", dur))
    }

    private fun showVodError(error: PlaybackException) {
        val url = urlAt(player.currentMediaItemIndex)
        AlertDialog.Builder(activity)
            .setTitle("Bu içerik oynatılamıyor")
            .setMessage("Dosya kaldırılmış olabilir ya da görüntü biçimi bu cihazda desteklenmiyor.\n(${error.errorCodeName})")
            .setPositiveButton("Harici oynatıcıda aç") { _, _ -> closeVod(); activity.openExternal(url, "") }
            .setNegativeButton("Kapat") { _, _ -> closeVod() }
            .setOnCancelListener { closeVod() }
            .show()
    }

    // ---------------- Yaşam döngüsü ----------------

    // ---------------- Yardımcı oynatıcılar (önizleme + çoklu ekran) ----------------
    // Ses odağını almazlar, böylece ana oynatıcıyı ya da birbirlerini duraklatmazlar.

    private val aux = arrayOfNulls<ExoPlayer>(4)
    private val auxViews = arrayOfNulls<PlayerView>(4)

    fun auxPlay(slot: Int, url: String, muted: Boolean) {
        if (slot !in 0..3) return
        if (mode == Mode.VOD) closeVod(notify = false)
        val p = aux[slot] ?: buildPlayer(handleAudioFocus = false).also { np ->
            aux[slot] = np
            np.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    emit("aux-error", JSONObject().put("slot", slot).put("code", error.errorCodeName))
                }
            })
        }
        val v = auxViews[slot] ?: PlayerView(activity).also { nv ->
            nv.useController = false
            nv.setKeepContentOnPlayerReset(true)
            nv.setShutterBackgroundColor(Color.BLACK)
            nv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            nv.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            nv.isFocusable = false
            // Ana videonun üstünde görünsün (önizleme penceresi için)
            (nv.videoSurfaceView as? SurfaceView)?.setZOrderMediaOverlay(true)
            auxViews[slot] = nv
            root.addView(nv, 0, FrameLayout.LayoutParams(0, 0))
        }
        v.player = p
        v.visibility = View.VISIBLE
        p.volume = if (muted) 0f else 1f
        p.setMediaItem(mediaItem(url))
        p.prepare()
        p.playWhenReady = true
    }

    fun auxRect(slot: Int, x: Float, y: Float, w: Float, h: Float) {
        val v = auxViews.getOrNull(slot) ?: return
        val d = activity.resources.displayMetrics.density
        v.layoutParams = FrameLayout.LayoutParams((w * d).toInt(), (h * d).toInt()).apply {
            leftMargin = (x * d).toInt(); topMargin = (y * d).toInt()
        }
    }

    fun auxMute(slot: Int, muted: Boolean) { aux.getOrNull(slot)?.volume = if (muted) 0f else 1f }

    fun auxStop(slot: Int) {
        aux.getOrNull(slot)?.let { it.stop(); it.clearMediaItems() }
        auxViews.getOrNull(slot)?.visibility = View.GONE
    }

    fun onPause() {
        aux.forEach { it?.playWhenReady = false }
        wasPlayingOnPause = player.playWhenReady
        if (mode == Mode.VOD) emitProgress()
        player.playWhenReady = false
    }

    fun onResume() {
        if (mode != Mode.NONE && wasPlayingOnPause) {
            if (mode == Mode.LIVE) player.seekToDefaultPosition()
            player.playWhenReady = true
        }
        aux.forEach { if (it != null && it.mediaItemCount > 0) it.playWhenReady = true }
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        aux.forEach { it?.release() }
        player.release()
    }

    // ---------------- Yardımcılar ----------------

    private fun mediaItem(url: String): MediaItem {
        val lower = url.lowercase()
        val b = MediaItem.Builder().setUri(url)
        when {
            "m3u8" in lower -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            ".mpd" in lower -> b.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        return b.build()
    }

    private fun emit(evt: String, data: JSONObject = JSONObject()) {
        main.post {
            web.evaluateJavascript("window.yayinNative && window.yayinNative('$evt', $data)", null)
        }
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}
