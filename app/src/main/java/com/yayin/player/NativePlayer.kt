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
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
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
        private set(v) { field = v; YayinRenderers.offsetActive = v == Mode.LIVE; updateOrientation() }

    private val main = Handler(Looper.getMainLooper())
    private var vodItems = JSONArray()
    private var liveRetries = 0
    private var triedAsHls = false
    private var wasPlayingOnPause = false

    // Bağlantıları açık tutan ortak HTTP istemcisi: komşu kanallara ön bağlantı
    // ve oynatıcı aynı bağlantı havuzunu kullanır, böylece geçişte TCP/TLS kurulumu beklenmez.
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .eventListenerFactory(object : okhttp3.EventListener.Factory {
            override fun create(call: okhttp3.Call): okhttp3.EventListener = netTimer
        })
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    // ---- Kanal açılış süresi ölçümü: sunucu yanıtı / ilk görüntü ----
    @Volatile private var psiUrl: String? = null
    @Volatile var psiFixEnabled = true
    @Volatile private var tStart = 0L
    @Volatile private var tHeaders = 0L
    private var timingSent = true
    private val netTimer = object : okhttp3.EventListener() {
        override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) {
            if (tStart > 0L && tHeaders == 0L && call.request().method == "GET" && response.code in 200..299)
                tHeaders = android.os.SystemClock.elapsedRealtime()
        }
    }

    private val defaultUserAgent = "Yayin/${BuildConfig.VERSION_NAME} (Linux; Android) ExoPlayer"
    private var userAgent = defaultUserAgent

    // Ayarlar ekranından gelen değerler
    private var bufferProfile = "fast"
    private var ffmpegMode = "on"
    private var audioLang = ""
    private var subLang = ""
    private var subScale = 1f
    private var quality = "max"
    private var tsEnabled = false
    private var tsKeepMin = 30

    var player: ExoPlayer = buildPlayer()
        private set

    /** Verilen User-Agent ile istek yapan kaynak fabrikası (film sunucusu belirli oynatıcı isteyebilir). */
    private fun sourceFactory(ua: String, tolerant: Boolean = false): DefaultMediaSourceFactory {
        val http0 = OkHttpDataSource.Factory(http).setUserAgent(ua)
        // Canlı MPEG-TS kanalında PAT+PMT'yi başa al: ilk tam kare atılmasın, görüntü hemen gelsin
        // Film/dizide dosyanın ortasından/sonundan okuma isteklerini güvenceye al (boş yanıtta tekrar dene, gerekirse parçalı indir)
        // Film/dizi: kendi sade HTTP okuyucumuz (bazı sunucular ExoPlayer'ın isteklerinde konumu yok sayıyor)
        val ds = DataSource.Factory {
            if (mode == Mode.VOD && vodChunked) VodHttpDataSource(http, ua) { note -> addVodNote(note) }
            else PsiFirstDataSource(http0.createDataSource()) { uri -> psiFixEnabled && uri.toString() == psiUrl }
        }
        val ex = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            .setConstantBitrateSeekingEnabled(true)
        if (tolerant) {
            // Standart dışı yazılmış dosyalar (diğer oynatıcıların açtığı ama "yapı bozuk" denen MP4/MKV'ler):
            // düzenleme listesini yok say, bozuk dizin (cue) bilgisine göre atlama yapma
            ex.setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
            ex.setFragmentedMp4ExtractorFlags(FragmentedMp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
            ex.setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
        }
        return DefaultMediaSourceFactory(ds, ex)
    }

    private fun buildPlayer(handleAudioFocus: Boolean = true): ExoPlayer {

        // Önce cihazın donanım çözücüsü; desteklemediği ses biçimleri (AC3, E-AC3, DTS,
        // MP2, TrueHD...) için FFmpeg devreye girer. Çözücü açılamazsa bir sonrakine geçer.
        // Cihazın çözücüleri + gerekirse FFmpeg ses (önceki çökmede FFmpeg suçluysa kapalı kalır)
        val ffmpegAllowed = ffmpegMode != "off" &&
            !activity.getSharedPreferences("yayin", android.content.Context.MODE_PRIVATE).getBoolean("ffmpegBroken", false)
        val renderers = YayinRenderers(activity, ffmpegAllowed, applyOffset = handleAudioFocus)

        // Arabellek profili: hızlı başlangıçta 0,5 sn veri gelince oynatmaya başlar
        val (minB, maxB, startB, rebufB) = when (bufferProfile) {
            "stable" -> listOf(30_000, 120_000, 2_500, 5_000)
            "balanced" -> listOf(20_000, 60_000, 1_000, 3_000)
            else -> listOf(12_000, 50_000, 500, 2_000)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minB, maxB, startB, rebufB)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Bağlantı hızı ölçülene kadar düşük kaliteyle başlamasın: başlangıç tahmini yüksek
        val bandwidth = DefaultBandwidthMeter.Builder(activity).setInitialBitrateEstimate(30_000_000L).build()

        val built = ExoPlayer.Builder(activity, renderers)
            .setBandwidthMeter(bandwidth)
            .setMediaSourceFactory(sourceFactory(userAgent))
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
        if (handleAudioFocus) mainFfmpegIndex = renderers.ffmpegIndex
        return built
    }

    // Film/dizi 40 sn'de açılmazsa sonsuz beklemek yerine teşhisli hata penceresi göster
    @Volatile private var vodStarted = false
    private val vodTimeout = Runnable {
        if (mode != Mode.VOD || vodStarted) return@Runnable
        val url = urlAt(player.currentMediaItemIndex)
        val ua = vodUa
        player.stop()
        Thread {
            val pr0 = probe(url, ua)
            val pr = if (pr0.kind == "mp4") pr0.copy(boxes = mp4Boxes(url, ua)) else pr0
            main.post {
                if (mode != Mode.VOD) return@post
                lastProbe = pr
                showVodError(PlaybackException("40 sn içinde açılamadı", null, PlaybackException.ERROR_CODE_TIMEOUT))
            }
        }.start()
    }
    private fun armVodTimeout() { main.removeCallbacks(vodTimeout); if (!vodStarted) main.postDelayed(vodTimeout, 40_000) }

    // Film/dizi: parçalı indirme modu ve bağlantı notları (hata penceresinde gösterilir)
    @Volatile private var vodChunked = false
    private val vodNotes = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private fun addVodNote(n: String) { if (mode == Mode.VOD) vodNotes.add(n) }

    private var mainFfmpegIndex = -1
    private var audioWarned = false

    /** Filmde ses parçası var ama hiçbir çözücü oynatamıyorsa kullanıcıya söyle ve arayüze bildir. */
    private fun checkAudio(tracks: Tracks) {
        if (mode != Mode.VOD || audioWarned) return
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audio.isEmpty() || audio.any { it.isSelected }) return
        audioWarned = true
        val f = audio[0].getTrackFormat(0)
        val desc = (f.sampleMimeType ?: "?").substringAfter('/') + (if (f.channelCount > 0) " " + f.channelCount + "ch" else "")
        val ff = mainFfmpegIndex >= 0
        android.widget.Toast.makeText(activity,
            "Ses biçimi çözülemedi: " + desc + (if (ff) "" else " (FFmpeg bu cihazda kapalı)"), android.widget.Toast.LENGTH_LONG).show()
        emit("noAudio", JSONObject().put("fmt", desc).put("ffmpeg", ff))
    }

    /** FFmpeg durumu: ok / off (ayardan kapalı) / crash (çökme sonrası kapatıldı) / missing (kütüphane yok) */
    fun ffmpegState(): String {
        val prefs = activity.getSharedPreferences("yayin", android.content.Context.MODE_PRIVATE)
        return when {
            ffmpegMode == "off" -> "off"
            prefs.getBoolean("ffmpegBroken", false) -> "crash"
            !YayinRenderers.ffmpegUsable() -> "missing"
            else -> "ok"
        }
    }

    /** Çökme sonrası kapatılan FFmpeg'i yeniden etkinleştir. */
    fun ffmpegRetry() {
        activity.getSharedPreferences("yayin", android.content.Context.MODE_PRIVATE).edit().remove("ffmpegBroken").apply()
        rebuild()
    }

    /** Yalnızca bu kanal için FFmpeg ses çözücüsünü kapat/aç (oynatıcıyı yeniden kurmadan). */
    private fun setFfmpegForChannel(off: Boolean) {
        val i = mainFfmpegIndex; if (i < 0) return
        val ts = player.trackSelector as? DefaultTrackSelector ?: return
        try { ts.setParameters(ts.buildUponParameters().setRendererDisabled(i, off)) } catch (_: Exception) {}
    }

    /** JS'ten: kanalı belirli bir yöntemle aç (noFfmpeg: bu kanalda FFmpeg'siz dene). */
    fun playLiveEx(url: String, noFfmpeg: Boolean, retry: Boolean) {
        setFfmpegForChannel(noFfmpeg)
        if (retry && liveVlcOn && vlcLive?.url == url) vlcLive?.stop()
        // Kurtarma denemesi: aynı kanal takılıp kaldıysa "zaten açık" sayılmasın, baştan yükle
        if (retry && mode == Mode.LIVE && liveReqUrl == url) player.stop()
        playLive(url)
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
                if (if (vlcActive) vlc?.isPlaying == true else player.isPlaying) emitProgress()
                main.postDelayed(this, 5_000)
            }
        }
    }

    private val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (mode == Mode.VOD) { vodStarted = true; main.removeCallbacks(vodTimeout) }
                if (timingSent || mode != Mode.LIVE || tStart == 0L) return
                timingSent = true
                val now = android.os.SystemClock.elapsedRealtime()
                emit("timing", JSONObject().put("total", now - tStart).put("server", if (tHeaders > 0L) tHeaders - tStart else -1L))
            }
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        // Takılma kaydı: izlerken veri bitip yükleme başladıysa say, o anki arabelleği sakla
                        if (mode == Mode.LIVE && hadPlayed) { stallCount++; lastStallBuf = lastTickBuf; lastStallAt = System.currentTimeMillis() }
                        emit("buffering")
                    }
                    Player.STATE_READY -> { liveRetries = 0; if (player.playWhenReady) emit("playing") }
                    // Canlı yayında "bitti" = sağlayıcı bağlantıyı kapattı (çoğu 5-15 dk'da bir yapar): aynı kanala sessizce yeniden bağlan
                    Player.STATE_ENDED -> if (mode == Mode.VOD) closeVod() else if (mode == Mode.LIVE) reconnectLive() else emit("ended")
                    else -> {}
                }
            }

            // Yayının çözünürlük / biçim bilgisi arayüze gitsin
            override fun onVideoSizeChanged(videoSize: VideoSize) { emitInfo() }
            override fun onTracksChanged(tracks: Tracks) { emitInfo(); checkAudio(tracks) }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && mode == Mode.LIVE) { hadPlayed = true; lastPos = -1L; bufSince = 0L }
                emit(if (isPlaying) "playing" else "paused")
                if (mode == Mode.VOD && !isPlaying) emitProgress()
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) triedAsHls = false
                if (mode != Mode.VOD) return
                val i = player.currentMediaItemIndex
                updateTitle(i)
                emit("item", JSONObject().put("index", i).put("url", urlAt(i)))
            }

            override fun onPlayerError(error: PlaybackException) {
                // Biçim tanınmadıysa: bazı sağlayıcılar .mp4/.mkv adresinden HLS yayını verir → bir kez HLS olarak dene
                if (mode == Mode.VOD) { recoverVod(error); return }
                if (mode == Mode.LIVE && ts != null) {
                    // Geri sarma kopyasıyla oynatılamadı: bu kanalı normal yoldan oynat
                    val u = tsUrl
                    stopTimeshift()
                    if (u != null) { tsBlocked.add(u); playLive(u) }
                    return
                }
                if (!triedAsHls && (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)) {
                    val item = player.currentMediaItem
                    if (item != null && mode != Mode.NONE) {
                        triedAsHls = true
                        val idx = player.currentMediaItemIndex
                        val pos = player.currentPosition.coerceAtLeast(0)
                        player.replaceMediaItem(idx, item.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build())
                        player.seekTo(idx, pos)
                        player.prepare()
                        player.playWhenReady = true
                        return
                    }
                }
                if (mode == Mode.LIVE && seamlessActive && !hadPlayed && liveReqUrl != null && toHls(liveReqUrl!!) != null) {
                    // Sağlayıcı bu kanalı HLS olarak vermiyor: normal yoldan aç
                    val u = liveReqUrl!!
                    seamlessFailed.add(u)
                    main.post { forceLive(u) }
                    return
                }
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
        aspectMode = mode
        vlc?.setAspect(mode)
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
        vodEngine = o.optString("vodEngine", "vlc")
        matchRate = o.optBoolean("matchRate", true)
        if (!matchRate) resetDisplay()
        val newSeamless = o.optBoolean("seamless", false)
        if (newSeamless != seamless) {
            seamless = newSeamless
            val u = if (liveVlcOn) vlcLive?.url else liveReqUrl
            if (mode == Mode.LIVE && u != null) main.post { forceLive(u) }
        }
        // Canlı TV her zaman ExoPlayer: VLC kanal değişiminde siyah ekran ve ses kaybı yapıyordu (VLC filmlerde kalıyor)
        val newLive = "exo"
        if (newLive != liveEngine) {
            liveEngine = newLive
            // Motor değiştiyse açık kanalı yeni motorla yeniden aç
            val u = if (liveVlcOn) vlcLive?.url else liveReqUrl
            if (mode == Mode.LIVE && u != null) main.post { forceLive(u) }
        }
        quality = o.optString("quality", "max")
        tsEnabled = false   // Canlı geri sarma kaldırıldı (kanal açılışını yavaşlatıyordu)
        psiFixEnabled = o.optBoolean("psiFix", true)
        tsKeepMin = o.optInt("tsMinutes", 30).coerceIn(5, 120)
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
            // "En yüksek": birden fazla kalitesi olan yayınlarda (HLS) daima en iyi kaliteyi seç
            .setForceHighestSupportedBitrate(quality == "max")
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
        triedAsHls = false
        if (mode == Mode.VOD) closeVod(notify = false)
        if (liveEngine == "vlc" && !seamless && !vlcLiveBroken && url !in vlcLiveFailed && playLiveVlc(url)) return
        if (liveVlcOn) stopLiveVlc()
        if (mode == Mode.LIVE && ts != null && tsUrl == url) {
            view.visibility = View.VISIBLE
            player.playWhenReady = true
            return
        }
        // Aynı kanal zaten oynuyorsa (ör. ana menü arka planından Canlı TV'ye geçiş) yeniden yükleme
        if (mode == Mode.LIVE && player.playbackState != Player.STATE_IDLE && liveReqUrl == url) {
            startLiveWatch()
            view.visibility = View.VISIBLE
            player.playWhenReady = true
            if (player.playbackState == Player.STATE_READY) emit("playing")
            return
        }
        mode = Mode.LIVE
        liveRetries = 0
        hadPlayed = false; reconnects.clear(); vStuckFired = 0; startLiveWatch()
        slowed = false; player.playbackParameters = PlaybackParameters.DEFAULT
        stallCount = 0; lastStallBuf = -1.0; lastStallAt = 0L; lastTickBuf = -1.0; minBuf = -1.0; watchStart = 0L
        view.visibility = View.VISIBLE
        if (tsEnabled && !isAdaptive(url) && url !in tsBlocked) { startTimeshift(url); return }
        stopTimeshift()
        tStart = android.os.SystemClock.elapsedRealtime(); tHeaders = 0L; timingSent = false
        // Kesintisiz mod: aynı kanalı HLS olarak 30 sn geriden aç; 30 sn'lik pay açılışta hazır olur
        liveReqUrl = url
        val hlsUrl = if (seamless && url !in seamlessFailed) toHls(url) else null
        seamlessActive = seamless && (hlsUrl != null || isAdaptive(url))
        val playUrl = hlsUrl ?: url
        psiUrl = if (isAdaptive(playUrl)) null else playUrl
        // Canlı yayında Fire TV'den kare hızı değişikliği isteme: "Orijinal kare hızına uyarla"
        // açıkken her kanal açılışında ekran modu yeniden kuruluyor ve 1-3 sn gecikme oluyordu.
        player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        player.setMediaItem(if (seamlessActive) seamlessItem(playUrl) else mediaItem(playUrl))
        player.prepare()
        player.playWhenReady = true
    }

    // ---------------- Canlı yayını durdur / geri sar ----------------

    private var ts: Timeshift? = null
    private var tsUrl: String? = null
    private var tsSourceWall = 0L
    private var tsResumeUrl: String? = null
    private val tsBlocked = HashSet<String>()

    private fun isAdaptive(url: String) = url.lowercase().let { "m3u8" in it || ".mpd" in it }

    // ---------------- Kesintisiz mod ----------------
    private var seamless = false
    private var seamlessActive = false
    private var liveReqUrl: String? = null
    private val seamlessFailed = HashSet<String>()

    /** Xtream canlı adresi .../live/kullanıcı/şifre/123.ts → .../123.m3u8 (HLS); dönüştürülemezse null */
    private fun toHls(url: String): String? {
        if (isAdaptive(url)) return null
        val m = Regex("^(.*/live/[^?#]+?)\\.(ts|mpegts)([?#].*)?$", RegexOption.IGNORE_CASE).find(url) ?: return null
        return m.groupValues[1] + ".m3u8" + m.groupValues[3]
    }

    private fun seamlessItem(url: String): MediaItem = MediaItem.Builder().setUri(url)
        .setMimeType(MimeTypes.APPLICATION_M3U8)
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(30_000).setMinOffsetMs(15_000).setMaxOffsetMs(90_000).build())
        .build()

    private fun startTimeshift(url: String) {
        stopTimeshift()
        val t = Timeshift(File(activity.cacheDir, "timeshift"), http, userAgent, tsKeepMin * 60_000L) {
            main.post { if (mode == Mode.LIVE && tsUrl == url) emit("error", JSONObject().put("code", "NO_DATA")) }
        }
        ts = t; tsUrl = url
        t.start(url)
        playTsFrom(0L, System.currentTimeMillis())
        main.removeCallbacks(tsTick)
        main.postDelayed(tsTick, 1000)
    }

    private fun playTsFrom(offset: Long, wall: Long) {
        val t = ts ?: return
        tsSourceWall = wall
        val factory = ProgressiveMediaSource.Factory(DataSource.Factory { TimeshiftDataSource(t) }, sourceExtractors())
        player.setMediaSource(factory.createMediaSource(MediaItem.fromUri("timeshift://live?o=$offset")))
        player.prepare()
        player.playWhenReady = true
    }

    private fun stopTimeshift() {
        main.removeCallbacks(tsTick)
        ts?.stop()
        ts = null; tsUrl = null
    }

    /** JS'ten: toggle / pause / play / seek(ms) / live */
    fun tsCmd(cmd: String, arg: Long) {
        val t = ts
        if (t == null) { if (cmd == "toggle") toggle(); return }
        val now = System.currentTimeMillis()
        when (cmd) {
            "toggle" -> player.playWhenReady = !player.playWhenReady
            "pause" -> player.playWhenReady = false
            "play" -> player.playWhenReady = true
            "seek" -> {
                val cur = tsSourceWall + player.currentPosition
                val target = (cur + arg).coerceIn(t.oldestWall(), now - 1500)
                if (arg > 0 && target >= now - 4000) { val o = t.offsetAt(now - 1500); playTsFrom(o, t.wallAt(o)) }
                else { val o = t.offsetAt(target); playTsFrom(o, t.wallAt(o)) }
            }
            "live" -> { val o = t.offsetAt(now - 1500); playTsFrom(o, t.wallAt(o)) }
        }
        emitTs()
    }

    private val tsTick = object : Runnable {
        override fun run() {
            if (ts != null && mode == Mode.LIVE) { emitTs(); main.postDelayed(this, 1000) }
        }
    }

    private fun emitTs() {
        val t = ts ?: return
        val now = System.currentTimeMillis()
        val behind = (now - (tsSourceWall + player.currentPosition)).coerceAtLeast(0)
        emit("ts", JSONObject()
            .put("behind", behind / 1000)
            .put("avail", (now - t.oldestWall()) / 1000)
            .put("paused", !player.playWhenReady))
    }

    private fun sourceExtractors() = DefaultExtractorsFactory()
        .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)

    /** Web arayüzündeki oynatıcı alanının konumu (CSS piksel). */
    private var lastRect: IntArray? = null
    private fun rectLp(): FrameLayout.LayoutParams {
        val r = lastRect ?: return FrameLayout.LayoutParams(0, 0)
        return FrameLayout.LayoutParams(r[2], r[3]).apply { leftMargin = r[0]; topMargin = r[1] }
    }
    fun setRect(x: Float, y: Float, w: Float, h: Float) {
        if (mode == Mode.VOD) return
        val d = activity.resources.displayMetrics.density
        lastRect = intArrayOf((x * d).toInt(), (y * d).toInt(), (w * d).toInt(), (h * d).toInt())
        view.layoutParams = rectLp()
        vlcLive?.setLayout(rectLp())
    }

    // ---------------- Canlı TV: VLC motoru ----------------
    // Ayarlar → Oynatma → "Canlı TV oynatıcı: VLC". XCIPTV'nin kullandığı motor; ExoPlayer'da takılan
    // yayınlarda akıcı oynatır. Açılamazsa ExoPlayer'a geri dönülür.
    private var liveEngine = "exo"
    private var vlcLive: VlcLive? = null
    private var vlcLiveBroken = false
    var liveVlcOn = false
        private set
    private val liveVlcReconnects = ArrayDeque<Long>()

    private val liveCb = object : VlcLive.Callbacks {
        override fun onLiveBuffering() {
            if (!liveVlcOn) return
            if (vlcLiveStarted) vlcStalls++
            emit("buffering")
        }
        override fun onLivePlaying() {
            if (!liveVlcOn) return
            if (!vlcLiveStarted) { vlcLiveStarted = true; vlcWatchStart = System.currentTimeMillis() }
            emit("playing")
        }
        override fun onLiveEnded() {
            // Sağlayıcı bağlantıyı kapattı: aynı kanala sessizce yeniden bağlan (2 dk'da en fazla 4 kez)
            val u = vlcLive?.url ?: return
            val now = android.os.SystemClock.elapsedRealtime()
            while (liveVlcReconnects.isNotEmpty() && now - liveVlcReconnects.first() > 120_000) liveVlcReconnects.removeFirst()
            if (liveVlcReconnects.size >= 4) { vlcFallback(u); return }
            liveVlcReconnects.addLast(now)
            emit("buffering")
            vlcLive?.play(u, liveUa(), lastVolume, YayinRenderers.audioOffsetUs)
        }
        override fun onLiveError() { val u = vlcLive?.url; if (liveVlcOn && u != null) vlcFallback(u) }
        override fun onLiveInfo(w: Int, h: Int, fps: Double, codec: String) {
            if (!liveVlcOn) return
            val o = JSONObject().put("w", w).put("h", h).put("engine", "VLC")
            if (fps > 0) { o.put("fps", fps); matchDisplay(fps.toFloat()) }
            if (codec.isNotBlank()) o.put("vcodec", codec)
            emit("info", o)
        }
    }

    private fun liveUa() = if (userAgent != defaultUserAgent) userAgent else ""

    // VLC takılma sayacı (bilgi panelinde gösterilir)
    private var vlcStalls = 0
    private var vlcLiveStarted = false
    private var vlcWatchStart = 0L
    private val vlcStatTick: Runnable = object : Runnable {
        override fun run() {
            if (!liveVlcOn) return
            val lost = vlcLive?.lostPictures() ?: -1
            emit("lstat", JSONObject().put("stalls", vlcStalls).apply { if (lost >= 0) put("drop", lost) }
                .put("watched", if (vlcWatchStart > 0) (System.currentTimeMillis() - vlcWatchStart) / 1000 else 0))
            main.postDelayed(this, 2_000)
        }
    }

    // ---------------- Ekranı yayının kare hızına eşle ----------------
    // 50 kare/sn'lik yayın 60 Hz ekranda düzenli tökezler (bazı kareler iki kez gösterilir). Kare hızı
    // bilinince TV'yi uygun moda (ör. 50 Hz) geçirir; aynı kare hızındaki kanallar arasında tekrar geçiş olmaz.
    private var matchRate = true
    private fun matchDisplay(fps: Float) {
        if (!matchRate || !isTvDevice || fps < 10f || android.os.Build.VERSION.SDK_INT < 23) return
        try {
            @Suppress("DEPRECATION") val disp = activity.windowManager.defaultDisplay
            val cur = disp.mode
            fun err(rate: Float): Float { val r = rate / fps; val k = Math.round(r); return if (k < 1) 99f else Math.abs(r - k) }
            if (err(cur.refreshRate) < 0.01f) return          // şu anki mod zaten uygun (ör. 25 kare → 50 Hz)
            // Yalnızca 25/50 kare yayınlar için 50 Hz'e geç; bir kez geçtikten sonra 30/60 karelik kanallar için geri dönme.
            // Her kanal değişiminde ekranın kararmasını önler.
            val fifty = Math.abs(fps - 25f) < 0.6f || Math.abs(fps - 50f) < 1f
            if (!fifty) return
            val best = disp.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight && err(it.refreshRate) < 0.01f }
                .minByOrNull { Math.abs(it.refreshRate - cur.refreshRate) } ?: return
            val lp = activity.window.attributes
            if (lp.preferredDisplayModeId == best.modeId) return
            lp.preferredDisplayModeId = best.modeId
            activity.window.attributes = lp
        } catch (_: Throwable) {}
    }
    /** Uygulamadan çıkınca TV kendi varsayılan moduna dönsün */
    private fun resetDisplay() {
        try {
            val lp = activity.window.attributes
            if (lp.preferredDisplayModeId != 0) { lp.preferredDisplayModeId = 0; activity.window.attributes = lp }
        } catch (_: Throwable) {}
    }

    // VLC bu kanalı açamadıysa hata gösterme: kanalı ExoPlayer ile aç ve bu oturumda o kanal için VLC'yi deneme
    private val vlcLiveFailed = HashSet<String>()
    private fun vlcFallback(url: String) {
        vlcLiveFailed.add(url)
        stopLiveVlc()
        mode = Mode.NONE
        main.post { playLive(url) }
    }

    /** VLC ile aç. Motor yüklenemezse false döner (ExoPlayer kullanılır). */
    private fun playLiveVlc(url: String): Boolean {
        val v = vlcLive ?: try { VlcLive(activity, root, liveCb).also { vlcLive = it } } catch (t: Throwable) {
            vlcLiveBroken = true; return false
        }
        // Aynı kanal zaten açıksa yeniden yükleme
        if (mode == Mode.LIVE && liveVlcOn && v.url == url) { emit("playing"); return true }
        // ExoPlayer'ı durdur, görüntüsünü gizle
        player.stop(); player.clearMediaItems(); view.visibility = View.GONE
        stopTimeshift()
        mode = Mode.LIVE
        liveVlcOn = true
        liveVlcReconnects.clear()
        main.removeCallbacks(liveWatch)
        v.setLayout(rectLp())
        try { v.play(url, liveUa(), lastVolume, YayinRenderers.audioOffsetUs) } catch (t: Throwable) {
            vlcLiveBroken = true; stopLiveVlc(); return false
        }
        vlcStalls = 0; vlcLiveStarted = false; vlcWatchStart = 0L
        main.removeCallbacks(vlcStatTick); main.postDelayed(vlcStatTick, 2_000)
        emit("buffering")
        return true
    }

    private fun stopLiveVlc() {
        liveVlcOn = false
        vlcLive?.stop()
    }

    /** Motor değişince aynı kanalı baştan aç */
    private fun forceLive(url: String) {
        if (liveVlcOn) stopLiveVlc() else { player.stop(); player.clearMediaItems() }
        mode = Mode.NONE
        playLive(url)
    }

    // ---------------- Canlı yayın bekçisi ----------------
    // Sağlayıcılar canlı yayın bağlantısını bir süre sonra (çoğu 5-15 dk) sessizce kapatabiliyor ya da
    // akış durabiliyor. Görüntü 8 sn ilerlemezse ya da yayın ortasında 6 sn yükleniyorsa aynı kanalı
    // arka planda yeniden açar; izleyen kişi en fazla bir iki saniyelik duraksama görür.

    private var hadPlayed = false
    private var lastPos = -1L
    private var stuckSince = 0L
    private var bufSince = 0L
    private var lastBufPos = -1L
    private var lastBufEmit = 0L
    private val reconnects = ArrayDeque<Long>()

    // Görüntü donuk ama ses akıyor: çözücü takıldı. Arayüze hata bildir → başka yöntem (HLS / FFmpeg'siz) denenir
    private var lastRendered = -1
    private var vStuckSince = 0L
    private var vStuckFired = 0

    private fun checkVideoStuck(now: Long) {
        if (player.videoFormat == null || vStuckFired >= 2) return
        val vc = player.videoDecoderCounters ?: return
        vc.ensureUpdated()
        val r = vc.renderedOutputBufferCount
        if (r != lastRendered) { lastRendered = r; vStuckSince = now; return }
        if (now - vStuckSince > 5_000) {
            vStuckFired++
            lastRendered = -1; vStuckSince = now
            emit("error", JSONObject().put("code", "VIDEO_STUCK"))
        }
    }

    private fun startLiveWatch() {
        main.removeCallbacks(liveWatch)
        lastRendered = -1; vStuckSince = android.os.SystemClock.elapsedRealtime()
        lastPos = -1L; bufSince = 0L
        main.postDelayed(liveWatch, 2_000)
    }

    private val liveWatch = object : Runnable {
        override fun run() {
            if (mode != Mode.LIVE) return
            val now = android.os.SystemClock.elapsedRealtime()
            val wants = player.playWhenReady && hadPlayed &&
                player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
            if (!wants) { lastPos = -1L; bufSince = 0L }
            else when (player.playbackState) {
                Player.STATE_READY -> {
                    bufSince = 0L
                    val p = player.currentPosition
                    if (p != lastPos) { lastPos = p; stuckSince = now }
                    else if (now - stuckSince > 8_000) reconnectLive()
                }
                Player.STATE_BUFFERING -> {
                    lastPos = -1L
                    // Veri gelmeye devam ediyorsa (yavaş internet) bağlantıyı KESME: biriken veri boşa gider,
                    // yayın hiç yürümez. Yalnızca hiç veri gelmiyorsa (bağlantı ölü) yeniden bağlan.
                    val buf = player.bufferedPosition
                    if (buf > lastBufPos) {
                        lastBufPos = buf; bufSince = now
                        // Arayüzün 10 sn'lik "takıldı → başka biçim dene" sayacını da ertele (veri geliyor, bekle)
                        if (now - lastBufEmit > 5_000) { lastBufEmit = now; emit("buffering") }
                    }
                    else if (bufSince == 0L) bufSince = now
                    else if (now - bufSince > 10_000) reconnectLive()
                }
                else -> { lastPos = -1L; bufSince = 0L }
            }
            if (mode == Mode.LIVE) { smartBuffer(); liveStats() }
            main.postDelayed(this, 2_000)
        }
    }

    // ---------------- Akıllı arabellek ----------------
    // Kanal hızlı açılsın diye oynatma çok az veriyle başlar; canlı yayında bu pay kendiliğinden büyümez ve
    // küçük bir ağ aksamasında görüntü takılır. Çözüm: arabellek 3 sn'den azken yayın fark edilmeyecek kadar
    // yavaş (%96, ses perdesi korunur) oynatılır; arka planda pay birikir. 3,5 sn'yi geçince normal hıza döner.
    private val SLOW_SPEED = 0.95f
    private var slowed = false
    private fun setSpeed(slow: Boolean) {
        if (slow == slowed) return
        slowed = slow
        player.playbackParameters = if (slow) PlaybackParameters(SLOW_SPEED) else PlaybackParameters.DEFAULT
    }
    private fun smartBuffer() {
        if (!player.isPlaying || seamlessActive) return
        val buf = player.totalBufferedDuration
        // Hedef pay ~7 sn: bu sağlayıcı yayını düzenli olarak 1-3 sn duraklatıyor
        if (!slowed && buf < 5_000) setSpeed(true)
        else if (slowed && buf >= 7_000) setSpeed(false)
    }

    // Takılma kaydı (kanal başına): kaç kez yükleme başladı, son takılmadan önce arabellek ne kadardı,
    // en düşük arabellek ve kaç saniyedir izleniyor
    private var stallCount = 0
    private var lastStallBuf = -1.0
    private var lastStallAt = 0L
    private var lastTickBuf = -1.0
    private var minBuf = -1.0
    private var watchStart = 0L

    /** Bilgi paneli için ölçüm: arabellek (sn), atlanan kare, takılma kaydı */
    private fun liveStats() {
        val vc = player.videoDecoderCounters
        vc?.ensureUpdated()
        val buf = player.totalBufferedDuration / 1000.0
        if (player.isPlaying) {
            lastTickBuf = buf
            if (minBuf < 0 || buf < minBuf) minBuf = buf
            if (watchStart == 0L) watchStart = System.currentTimeMillis()
        }
        emit("lstat", JSONObject()
            .put("buf", buf)
            .put("drop", vc?.droppedBufferCount ?: 0)
            .put("slow", slowed)
            .put("stalls", stallCount)
            .put("lastStallBuf", lastStallBuf)
            .put("lastStallAgo", if (lastStallAt > 0) (System.currentTimeMillis() - lastStallAt) / 1000 else -1)
            .put("minBuf", minBuf)
            .put("watched", if (watchStart > 0) (System.currentTimeMillis() - watchStart) / 1000 else 0))
    }

    /** Aynı kanalı baştan bağlar. 2 dakikada 4'ten fazla gerekirse arayüzün kurtarma adımlarına bırakır. */
    private fun reconnectLive() {
        val item = player.currentMediaItem ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        while (reconnects.isNotEmpty() && now - reconnects.first() > 120_000) reconnects.removeFirst()
        lastPos = -1L; bufSince = 0L; stuckSince = now; lastBufPos = -1L
        if (reconnects.size >= 4) {
            hadPlayed = false
            emit("error", JSONObject().put("code", "LIVE_RECONNECT"))
            return
        }
        reconnects.addLast(now)
        emit("buffering")   // arayüzün 10 sn'lik takılma sayacı yeniden başlasın
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    fun stop() {
        if (mode == Mode.VOD) return
        liveReqUrl = null
        if (liveVlcOn) stopLiveVlc()
        stopTimeshift()
        mode = Mode.NONE
        player.stop()
        player.clearMediaItems()
        view.visibility = View.GONE
    }

    // ---------------- Telefon: ekran yönü ----------------
    // Telefonda film/dizi ve tam ekran canlı yayın yatay açılır; liste ekranlarında telefon serbest döner.
    // TV'de hiçbir şey değişmez.
    val isTvDevice: Boolean by lazy {
        val ui = activity.getSystemService(android.content.Context.UI_MODE_SERVICE) as android.app.UiModeManager
        ui.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    }
    private var jsLandscape = false
    fun setJsLandscape(on: Boolean) { jsLandscape = on; updateOrientation() }
    private fun updateOrientation() {
        if (isTvDevice) return
        val want = if (mode == Mode.VOD || jsLandscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                   else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (activity.requestedOrientation != want) activity.requestedOrientation = want
    }

    // ---------------- Bildirim bandı (film/dizi açıkken gol bildirimi) ----------------
    private var noteView: android.widget.TextView? = null
    private val hideNote = Runnable { noteView?.visibility = View.GONE }
    fun notify(text: String) {
        val tv = noteView ?: android.widget.TextView(activity).also { t ->
            val d = activity.resources.displayMetrics.density
            t.setTextColor(Color.WHITE); t.textSize = 18f; t.setPadding((18 * d).toInt(), (12 * d).toInt(), (18 * d).toInt(), (12 * d).toInt())
            t.background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 14 * d; setColor(Color.argb(235, 200, 16, 46)) }
            t.elevation = 30 * d
            root.addView(t, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.END).apply { topMargin = (28 * d).toInt(); rightMargin = (28 * d).toInt() })
            noteView = t
        }
        tv.text = text
        tv.visibility = View.VISIBLE
        tv.bringToFront()
        main.removeCallbacks(hideNote); main.postDelayed(hideNote, 9_000)
    }

    /** JS'ten: bu kanal için ses gecikmesi (ms). Pozitif: ses geciktirilir. */
    fun setAudioDelay(ms: Int) { YayinRenderers.audioOffsetUs = ms.coerceIn(-3000, 5000) * 1000L; if (liveVlcOn) vlcLive?.setAudioDelay(YayinRenderers.audioOffsetUs) }

    fun toggle() { if (vlcActive) { vlc?.toggle(); return }; if (liveVlcOn) { vlcLive?.toggle(); return }; player.playWhenReady = !player.playWhenReady }
    fun setMuted(m: Boolean) { player.volume = if (m) 0f else lastVolume; if (vlcActive) vlc?.setVolume(if (m) 0f else lastVolume); if (liveVlcOn) vlcLive?.setVolume(if (m) 0f else lastVolume) }
    private var lastVolume = 1f
    fun setVolume(v: Float) { lastVolume = v.coerceIn(0f, 1f); player.volume = lastVolume; if (vlcActive) vlc?.setVolume(lastVolume); if (liveVlcOn) vlcLive?.setVolume(lastVolume) }

    private val warmed = HashMap<String, Long>()

    /**
     * Sunucuya önceden bağlan (DNS + TCP + TLS hazır olsun), ama YAYINI AÇMADAN:
     * yalnızca sunucunun ana adresine kısa bir HEAD isteği gider. Böylece hesabın
     * eşzamanlı bağlantı sınırı aşılmaz ve izlenen yayın kesilmez.
     */
    fun preconnect(url: String) {
        val u = url.toHttpUrlOrNull() ?: return
        val origin = "${u.scheme}://${u.host}:${u.port}/"
        val now = System.currentTimeMillis()
        synchronized(warmed) {
            val last = warmed[origin]
            if (last != null && now - last < 8_000) return
            warmed[origin] = now
        }
        val req = try {
            Request.Builder().url(origin).head().header("User-Agent", userAgent).build()
        } catch (_: Exception) { return }
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    // ---------------- Film / dizi ----------------

    fun playVod(json: String, index: Int, startMs: Long) {
        vodItems = try { JSONArray(json) } catch (_: Exception) { return }
        stopTimeshift()
        // Canlı kanal için FFmpeg kapatılmış olabilir: filmlerde AC3/DTS ses için her zaman yeniden aç
        setFfmpegForChannel(false)
        // Ana ekrandaki arka plan kanalı sessize alınmış olabilir: film kendi ses seviyesiyle başlasın
        player.volume = lastVolume
        // Filmlerde kare hızı uyarlaması açık kalsın (daha akıcı hareket; açılıştaki kısa bekleme sorun değil)
        player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
        audioWarned = false
        triedAsHls = false
        vodTried.clear(); vodUa = userAgent; lastProbe = null; vodChunked = true; vodNotes.clear(); vodStarted = false
        if (vodItems.length() == 0) return
        if (liveVlcOn) stopLiveVlc()
        mode = Mode.VOD
        web.visibility = View.INVISIBLE
        vlcTried = false; exoTried = false
        main.removeCallbacks(progressTick)
        main.postDelayed(progressTick, 5_000)
        // Varsayılan: VLC (standart dışı dosyaları da açar). Açamazsa ExoPlayer'a geçilir.
        if (vodEngine == "vlc" && !vlcBroken) { startVlc(index, startMs); return }
        startExo(index, startMs)
    }

    /** Film/diziyi ExoPlayer ile oynat (eski yöntem; VLC açamazsa yedek). */
    private fun startExo(index: Int, startMs: Long) {
        exoTried = true
        slowed = false; player.playbackParameters = PlaybackParameters.DEFAULT
        stallCount = 0; lastStallBuf = -1.0; lastStallAt = 0L; lastTickBuf = -1.0; minBuf = -1.0; watchStart = 0L
        vodStarted = false
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
        armVodTimeout()
    }

    // ---------------- Film / dizi: VLC motoru ----------------

    private var vodEngine = "vlc"
    private var vlc: VlcVod? = null
    var vlcActive = false
        private set
    private var vlcIndex = 0
    private var vlcTried = false
    private var exoTried = false
    private var vlcBroken = false
    private var aspectMode = "fit"

    private val vlcCb = object : VlcVod.Callbacks {
        override fun onVlcStarted() { vodStarted = true }
        override fun onVlcEnded() {
            val v = vlc ?: return
            // Görüntü hiç başlamadan "bitti": VLC dosyayı açamadı demektir → hata say, yedek yolları dene
            if (!v.hasStarted) { onVlcError("dosya açılamadı (hemen bitti)"); return }
            // Film ortasında "bitti": bağlantı koptu → kaldığı yerden devam et (en fazla 3 kez)
            val t = v.timeMs; val len = v.lengthMs
            if (len > 0 && t < len - 120_000 && vlcResumes < 3) {
                vlcResumes++
                addVodNote("bağlantı koptu, devam ediliyor")
                playVlcItem(vlcIndex, t, vlcCurOverride)
                return
            }
            emitProgress()
            if (vlcIndex + 1 < vodItems.length()) playVlcItem(vlcIndex + 1, 0L) else closeVod()
        }
        override fun onVlcError(msg: String) {
            if (!vlcActive) return
            val pos = vlc?.timeMs ?: 0L
            val idx = vlcIndex
            addVodNote("VLC: $msg")
            val alt = altUrl(urlAt(idx), "m3u8")
            if (!vlcAltTried && alt != null) {
                vlcAltTried = true
                addVodNote("VLC .m3u8 adresiyle deneniyor")
                playVlcItem(idx, pos, alt)
                return
            }
            releaseVlc()
            if (!exoTried) {
                android.widget.Toast.makeText(activity, "VLC açamadı, diğer oynatıcıyla deneniyor…", android.widget.Toast.LENGTH_SHORT).show()
                startExo(idx, pos)
            } else {
                showVodError(PlaybackException(msg, null, PlaybackException.ERROR_CODE_UNSPECIFIED), idx)
            }
        }
        override fun onVlcClose() { closeVod() }
        override fun onVlcStall() {
            val v = vlc ?: return
            if (vlcResumes >= 5) { onVlcError("film sürekli donuyor"); return }
            vlcResumes++
            addVodNote("donma, yeniden bağlanıldı")
            playVlcItem(vlcIndex, v.timeMs, vlcCurOverride)
        }
        override fun onVlcNext(delta: Int) {
            val ni = vlcIndex + delta
            if (ni !in 0 until vodItems.length()) return
            emitProgress()
            playVlcItem(ni, 0L)
        }
    }

    private fun startVlc(index: Int, startMs: Long) {
        vlcTried = true
        vlcAltTried = false; vlcResumes = 0; vlcCurOverride = null
        main.removeCallbacks(vodTimeout)
        // ExoPlayer'ı durdur (arka plandaki canlı yayın ya da açılamayan film) ve görünümünü gizle
        val wasBusy = player.playbackState != Player.STATE_IDLE
        player.stop(); player.clearMediaItems()
        aux.forEach { it?.let { a -> a.stop(); a.clearMediaItems() } }   // önizleme / çoklu ekran bağlantıları da kapansın
        view.hideController()
        view.visibility = View.GONE
        val v = vlc ?: try { VlcVod(activity, root, vlcCb, touch = !isTvDevice) } catch (t: Throwable) {
            vlcBroken = true; addVodNote("VLC yüklenemedi"); startExo(index, startMs); return
        }
        vlc = v; vlcActive = true
        val i = index.coerceIn(0, vodItems.length() - 1)
        // Sağlayıcının tek bağlantı sınırı varsa kapanan canlı yayın bağlantısının serbest kalmasını bekle
        if (wasBusy) main.postDelayed({ if (mode == Mode.VOD && vlcActive && vlc === v) playVlcItem(i, startMs) }, 1_500)
        else playVlcItem(i, startMs)
    }

    private var vlcAltTried = false

    private var vlcResumes = 0
    private var vlcCurOverride: String? = null

    private fun playVlcItem(i: Int, startMs: Long, urlOverride: String? = null) {
        val v = vlc ?: return
        if (i != vlcIndex || startMs == 0L) vlcResumes = 0
        if (urlOverride == null && (i != vlcIndex || startMs == 0L)) vlcAltTried = false
        vlcCurOverride = urlOverride
        vlcIndex = i
        val o = vodItems.optJSONObject(i) ?: return
        updateTitle(i)
        // Kullanıcı özel bir kimlik ayarladıysa onu kullan; yoksa VLC kendi kimliğiyle istesin
        val ua = if (vodUa != defaultUserAgent) vodUa else ""
        try {
            v.play(urlOverride ?: o.optString("url"), o.optString("title"), o.optString("sub"), startMs, ua, audioLang, subLang, lastVolume, aspectMode)
        } catch (t: Throwable) {
            vlcBroken = true
            vlcCb.onVlcError("VLC başlatılamadı (" + t.javaClass.simpleName + ")")
            return
        }
        emit("item", JSONObject().put("index", i).put("url", urlAt(i)))
    }

    private fun releaseVlc() {
        vlcActive = false
        vlc?.release()
        vlc = null
    }

    /** MainActivity'den: VLC açıkken kumanda tuşları */
    fun vlcKey(e: android.view.KeyEvent): Boolean = vlc?.onKey(e) ?: false

    fun closeVod(notify: Boolean = true) {
        if (mode != Mode.VOD) return
        emitProgress()
        main.removeCallbacks(progressTick)
        main.removeCallbacks(vodTimeout)
        releaseVlc()
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
        if (vlcActive) { vlc?.seekBy(ms); return }
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + ms).coerceIn(0, dur))
        view.showController()
    }

    /** Telefondan film/dizi komutları */
    fun vodCmd(cmd: String) {
        if (mode != Mode.VOD) return
        when (cmd) {
            "playpause" -> { toggle(); if (vlcActive) vlc?.showOverlay() else view.showController() }
            "ff" -> seekBy(30_000); "rw" -> seekBy(-30_000)
            "ff10" -> seekBy(10_000); "rw10" -> seekBy(-10_000)
            "next" -> next(); "prev" -> previous()
            "back" -> closeVod()
        }
    }

    fun next() { if (vlcActive) { vlcCb.onVlcNext(1); return }; if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
    fun previous() { if (vlcActive) { vlcCb.onVlcNext(-1); return }; if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() }

    private fun updateTitle(i: Int) {
        val o = vodItems.optJSONObject(i) ?: return
        titleText.text = o.optString("title")
        subText.text = o.optString("sub")
    }

    private fun urlAt(i: Int) = vodItems.optJSONObject(i)?.optString("url") ?: ""

    private fun emitInfo() {
        val vs = player.videoSize
        val vf = player.videoFormat
        val af = player.audioFormat
        if (vs.width <= 0 && vf == null && af == null) return
        val o = JSONObject().put("engine", if (mode == Mode.LIVE && seamlessActive) "ExoPlayer · kesintisiz (30 sn)" else "ExoPlayer")
            .put("w", if (vs.width > 0) vs.width else (vf?.width ?: 0))
            .put("h", if (vs.height > 0) vs.height else (vf?.height ?: 0))
        vf?.let {
            if (it.frameRate > 0) { o.put("fps", it.frameRate.toDouble()); if (mode == Mode.LIVE) matchDisplay(it.frameRate) }
            o.put("vmime", it.sampleMimeType ?: "")
            val br = if (it.bitrate > 0) it.bitrate else it.peakBitrate
            if (br > 0) o.put("vbr", br)
        }
        af?.let {
            o.put("amime", it.sampleMimeType ?: "")
            if (it.channelCount > 0) o.put("ch", it.channelCount)
        }
        emit("info", o)
    }

    private fun emitProgress() {
        val v = vlc
        if (vlcActive && v != null) {
            val d = v.lengthMs
            if (d <= 0) return
            emit("progress", JSONObject().put("url", urlAt(vlcIndex)).put("pos", v.timeMs).put("dur", d))
            return
        }
        val dur = player.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        emit("progress", JSONObject()
            .put("url", urlAt(player.currentMediaItemIndex))
            .put("pos", player.currentPosition)
            .put("dur", dur))
    }

    // ---------------- Film / dizi açılmazsa: teşhis ve otomatik çözüm ----------------

    private data class Probe(val code: Int, val type: String, val kind: String, val detail: String, val boxes: String = "")

    private val vodTried = mutableSetOf<String>()
    private var vodUa = defaultUserAgent
    private var lastProbe: Probe? = null
    private val vlcUa = "VLC/3.0.20 LibVLC/3.0.20"

    /** Adresin ilk 4 KB'ını indirip sunucunun gerçekte ne gönderdiğine bakar. */
    private fun probe(url: String, ua: String): Probe {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", ua).header("Range", "bytes=0-4095").build()
            http.newCall(req).execute().use { r ->
                val type = (r.header("Content-Type") ?: "").substringBefore(';').trim()
                val buf = ByteArray(4096); var n = 0
                r.body?.byteStream()?.use { inp -> while (n < buf.size) { val k = inp.read(buf, n, buf.size - n); if (k < 0) break; n += k } }
                val b = buf.copyOf(n)
                val head = String(b, 0, minOf(n, 512), Charsets.ISO_8859_1)
                fun at(i: Int, vararg v: Int) = n > i + v.size - 1 && v.indices.all { (b[i + it].toInt() and 0xFF) == v[it] }
                val kind = when {
                    n == 0 -> "empty"
                    head.trimStart().startsWith("#EXTM3U") -> "hls"
                    at(0, 0x1A, 0x45, 0xDF, 0xA3) -> "mkv"
                    n > 8 && head.substring(4, 8) == "ftyp" -> "mp4"
                    at(0, 0x47) && (n < 189 || at(188, 0x47)) -> "ts"
                    head.startsWith("RIFF") && head.contains("AVI") -> "avi"
                    at(0, 0x30, 0x26, 0xB2, 0x75) -> "asf"
                    head.startsWith("FLV") -> "flv"
                    head.trimStart().startsWith("<") || head.contains("<html", true) -> "html"
                    head.trimStart().startsWith("{") || head.trimStart().startsWith("[") -> "json"
                    head.all { it.code in 9..126 } -> "text"
                    else -> "unknown"
                }
                val detail = when (kind) {
                    "html", "json", "text" -> head.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(140)
                    "unknown" -> b.take(8).joinToString(" ") { "%02X".format(it) }
                    else -> ""
                }
                Probe(r.code, type, kind, detail)
            }
        } catch (e: Exception) {
            Probe(0, "", "neterr", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun mb(b: Long): String = when {
        b < 0 -> "?"
        b < 1024 -> "$b B"
        b < 1024L * 1024 -> "${b / 1024} KB"
        b < 1024L * 1024 * 1024 -> String.format(java.util.Locale.GERMANY, "%.1f MB", b / 1048576.0)
        else -> String.format(java.util.Locale.GERMANY, "%.2f GB", b / 1073741824.0)
    }

    /**
     * MP4 dosyasının ana kutularını (ftyp, moov, mdat...) küçük parça istekleriyle dolaşır.
     * Sonuç örn. "ftyp 32 B, moov 2,1 MB, mdat 1,20 GB". Sunucu istenen konumu yanlış gönderirse
     * ya da dosya eksikse bunu da yazar. Yalnızca hata penceresinde teşhis için kullanılır.
     */
    private fun mp4Boxes(url: String, ua: String): String {
        val out = mutableListOf<String>()
        var pos = 0L; var total = -1L; var sawMoov = false; var complete = false
        try {
            for (i in 0 until 14) {
                if (total in 0..pos) { complete = true; break }
                val req = Request.Builder().url(url).header("User-Agent", ua).header("Range", "bytes=$pos-${pos + 15}").build()
                var code = 0; var cr = ""; var hdr = ByteArray(0)
                http.newCall(req).execute().use { r ->
                    code = r.code; cr = r.header("Content-Range") ?: ""
                    val b = ByteArray(16); var n = 0
                    r.body?.byteStream()?.use { inp -> while (n < 16) { val k = inp.read(b, n, 16 - n); if (k < 0) break; n += k } }
                    hdr = b.copyOf(n)
                }
                val m = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)").find(cr)
                if (m != null) {
                    m.groupValues[3].toLongOrNull()?.let { total = it }
                    if (m.groupValues[1].toLongOrNull() != pos) { out += "⚠ sunucu ${mb(pos)} konumunu yanlış gönderdi"; break }
                } else if (pos > 0) { out += "⚠ sunucu ${mb(pos)} konumundan okumaya izin vermedi (HTTP $code)"; break }
                if (hdr.size < 8) { out += "⚠ dosya ${mb(pos)} konumunda bitiyor"; break }
                fun u32(o: Int) = ((hdr[o].toLong() and 0xFF) shl 24) or ((hdr[o + 1].toLong() and 0xFF) shl 16) or
                    ((hdr[o + 2].toLong() and 0xFF) shl 8) or (hdr[o + 3].toLong() and 0xFF)
                var size = u32(0)
                val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
                if (!type.all { it.code in 32..126 }) { out += "⚠ ${mb(pos)} konumunda bozuk veri"; break }
                if (size == 1L && hdr.size >= 16) size = (u32(8) shl 32) or u32(12)
                else if (size == 0L) size = if (total > 0) total - pos else -1L
                if (type == "moov") sawMoov = true
                out += "$type ${mb(size)}"
                if (size < 8) { out += "⚠ geçersiz kutu boyutu"; break }
                pos += size
            }
        } catch (e: Exception) {
            out += "⚠ okunamadı: " + (e.message ?: e.javaClass.simpleName).take(60)
        }
        if (total > 0 && pos > total) out += "⚠ dosya eksik: ${mb(total)} var, ${mb(pos)} olmalı"
        if (complete && !sawMoov) out += "⚠ moov (içindekiler) bölümü yok"
        return (if (total > 0) "toplam ${mb(total)} — " else "") + out.joinToString(", ")
    }

    private fun recoverVod(error: PlaybackException) {
        val idx = player.currentMediaItemIndex
        val pos = player.currentPosition.coerceAtLeast(0)
        val url = urlAt(idx)
        if (url.isBlank() || vodTried.size >= 6) { showVodError(error); return }
        // "Dosya yapısı bozuk": önce toleranslı okumayla aynı yerden yeniden dene (diğer oynatıcılar bu dosyaları açabiliyor)
        if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED) {
            // Önce sunucunun HLS (.m3u8) adresi: Xtream sunucuları dosyayı kendisi yayına çevirip düzgün gönderir
            val hlsUrl = altUrl(url, "m3u8")
            if (hlsUrl != null && "m3u8" !in vodTried) { vodTried += "m3u8"; restartVod(idx, pos, vodUa, true, false, hlsUrl); return }
            // "Yükleme hazırlık bitmeden tamamlandı": sunucu dosyanın sonunu (moov) göndermedi → parçalı indir
            if ("tol" !in vodTried) { vodTried += "tol"; restartVod(idx, pos, vodUa, false, true); return }
        }
        val ua = vodUa
        Thread {
            val pr0 = probe(url, ua)
            // MP4 ise dosyanın iç yapısına da bak (moov var mı, sunucu istenen yeri doğru gönderiyor mu)
            val pr = if (pr0.kind == "mp4") pr0.copy(boxes = mp4Boxes(url, ua)) else pr0
            main.post {
                if (mode != Mode.VOD) return@post
                lastProbe = pr
                val blocked = pr.kind in setOf("html", "json", "text", "empty") || pr.code in 400..499
                when {
                    pr.kind == "hls" && "hls" !in vodTried -> { vodTried += "hls"; restartVod(idx, pos, vodUa, true) }
                    blocked && "ua" !in vodTried -> { vodTried += "ua"; vodUa = vlcUa; restartVod(idx, pos, vlcUa, false) }
                    pr.kind == "unknown" && "hls" !in vodTried -> { vodTried += "hls"; restartVod(idx, pos, vodUa, true) }
                    pr.kind in setOf("mp4", "mkv") && "tol" !in vodTried -> { vodTried += "tol"; restartVod(idx, pos, vodUa, false, true) }
                    // Toleranslı okuma da yetmediyse: sunucu bazı oynatıcılara farklı dosya veriyor olabilir → VLC kimliğiyle dene
                    pr.kind in setOf("mp4", "mkv") && "ua" !in vodTried -> { vodTried += "ua"; vodUa = vlcUa; restartVod(idx, pos, vlcUa, false) }
                    altUrl(url, "m3u8") != null && "m3u8" !in vodTried -> { vodTried += "m3u8"; restartVod(idx, pos, vodUa, true, false, altUrl(url, "m3u8")) }
                    altUrl(url, "ts") != null && "ts" !in vodTried -> { vodTried += "ts"; restartVod(idx, pos, vodUa, false, false, altUrl(url, "ts")) }
                    else -> showVodError(error)
                }
            }
        }.start()
    }

    /** Film listesini yeni ayarlarla (User-Agent / HLS) aynı bölüm ve konumdan yeniden başlat. */
    /** Aynı içeriğin sunucudaki başka biçimli adresi: .../123.mp4 → .../123.m3u8 (ya da .ts) */
    private fun altUrl(url: String, ext: String): String? {
        val r = Regex("\\.(mp4|mkv|avi|m4v|mov|webm|flv|wmv|mpg|mpeg|ts)(\\?|$)", RegexOption.IGNORE_CASE)
        val m = r.find(url) ?: return null
        if (m.groupValues[1].equals(ext, true)) return null
        return url.replaceRange(m.range, ".$ext" + m.groupValues[2])
    }

    private fun restartVod(idx: Int, pos: Long, ua: String, forceHls: Boolean, tolerant: Boolean = false, urlOverride: String? = null) {
        // HLS'te ExoPlayer'ın kendi okuyucusu (yönlendirmeleri ve parça adreslerini doğru çözer); dosyalarda bizim parçalı okuyucu
        vodChunked = !forceHls
        val factory = sourceFactory(ua, tolerant || "tol" in vodTried)
        val sources = (0 until vodItems.length()).map { i ->
            val o = vodItems.getJSONObject(i)
            var item = mediaItem(if (i == idx && urlOverride != null) urlOverride else o.getString("url")).buildUpon()
                .setMediaMetadata(MediaMetadata.Builder().setTitle(o.optString("title")).setSubtitle(o.optString("sub")).build())
            if (forceHls && i == idx) item = item.setMimeType(MimeTypes.APPLICATION_M3U8)
            factory.createMediaSource(item.build())
        }
        player.setMediaSources(sources, idx.coerceIn(0, sources.size - 1), pos)
        player.prepare()
        player.playWhenReady = true
        armVodTimeout()
    }

    private fun showVodError(error: PlaybackException, index: Int = player.currentMediaItemIndex) {
        if (mode != Mode.VOD) return
        if (!vlcTried && !vlcBroken) {
            android.widget.Toast.makeText(activity, "Açılamadı, VLC ile deneniyor…", android.widget.Toast.LENGTH_SHORT).show()
            startVlc(index, player.currentPosition.coerceAtLeast(0))
            return
        }
        val url = urlAt(index)
        val pr = lastProbe
        try {
            val o = vodItems.optJSONObject(index)
            emit("vodErr", JSONObject().put("code", error.errorCodeName).put("title", o?.optString("title") ?: "")
                .put("sub", o?.optString("sub") ?: "").put("probe", pr?.kind ?: "").put("http", pr?.code ?: 0))
        } catch (_: Exception) {}
        val what = when (pr?.kind) {
            "mkv" -> "MKV video"; "mp4" -> "MP4 video"; "ts" -> "MPEG-TS video"; "avi" -> "AVI video"; "flv" -> "FLV video"
            "asf" -> "WMV/ASF video – bu biçim Android'de desteklenmiyor"
            "hls" -> "HLS yayın listesi"; "html" -> "web sayfası (sunucu hata ya da erişim reddi gönderdi)"
            "json" -> "JSON yanıtı (sunucu hata gönderdi)"; "text" -> "düz metin (sunucu hata gönderdi)"
            "empty" -> "boş yanıt"; "neterr" -> "bağlantı kurulamadı"; "unknown" -> "tanınmayan biçim"; else -> null
        }
        val diag = if (pr == null) "" else buildString {
            append("\n\nSunucu yanıtı: ")
            append(if (pr.code > 0) "HTTP ${pr.code}" else "—")
            if (pr.type.isNotBlank()) append(" · ").append(pr.type)
            if (what != null) append("\n").append(what)
            if (pr.detail.isNotBlank()) append("\n“").append(pr.detail).append("”")
            if (pr.boxes.isNotBlank()) append("\nDosya yapısı: ").append(pr.boxes)
            if (vodTried.isNotEmpty()) append("\nDenenenler: ").append(vodTried.joinToString {
                when (it) { "ua" -> "VLC kimliği"; "tol" -> "toleranslı okuma"; "chunk" -> "parçalı indirme"; "m3u8" -> ".m3u8 adresi"; "ts" -> ".ts adresi"; else -> "HLS" } })
            val notes = synchronized(vodNotes) { vodNotes.toList() }
            if (notes.isNotEmpty()) append("\nBağlantı: ").append(notes.joinToString(", "))
        }
        // ExoPlayer'ın asıl hata açıklaması (hangi kutuda/neden takıldığını söyler)
        val cause = generateSequence(error.cause) { it.cause }.mapNotNull { it.message?.trim() }.firstOrNull { it.isNotBlank() }
        val more = if (cause != null) "\nAyrıntı: " + cause.take(160) else ""
        AlertDialog.Builder(activity)
            .setTitle("Bu içerik oynatılamıyor")
            .setMessage("Dosya kaldırılmış olabilir ya da biçimi bu cihazda desteklenmiyor.\n(${error.errorCodeName})$diag$more")
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
        resetDisplay()
        aux.forEach { it?.playWhenReady = false }
        if (vlcActive) {
            wasPlayingOnPause = vlc?.isPlaying == true
            emitProgress()
            vlc?.pause()
            return
        }
        if (liveVlcOn) { wasPlayingOnPause = true; vlcLive?.pause(); return }
        wasPlayingOnPause = player.playWhenReady
        // Uygulama arka plana geçince canlı yayını indirmeyi bırak (bant genişliği / bağlantı)
        if (ts != null && mode == Mode.LIVE) { tsResumeUrl = tsUrl; stopTimeshift() }
        if (mode == Mode.VOD) emitProgress()
        player.playWhenReady = false
    }

    fun onResume() {
        if (vlcActive) { if (wasPlayingOnPause) vlc?.resume(); return }
        if (liveVlcOn) { vlcLive?.let { v -> val u = v.url; if (u != null) v.play(u, liveUa(), lastVolume, YayinRenderers.audioOffsetUs) }; return }
        val ru = tsResumeUrl
        if (ru != null) {
            tsResumeUrl = null
            if (mode == Mode.LIVE && wasPlayingOnPause) { startTimeshift(ru); return }
        }
        if (mode != Mode.NONE && wasPlayingOnPause) {
            if (mode == Mode.LIVE) player.seekToDefaultPosition()
            player.playWhenReady = true
        }
        aux.forEach { if (it != null && it.mediaItemCount > 0) it.playWhenReady = true }
    }

    fun release() {
        releaseVlc()
        vlcLive?.release(); vlcLive = null; liveVlcOn = false
        stopTimeshift()
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
