package com.yayin.player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Canlı TV için VLC motoru (XCIPTV'nin kullandığı motor). ExoPlayer'da takılan yayınlarda akıcı oynatır.
 * Görüntü, ExoPlayer'ın görünümüyle aynı yere (küçük pencere / tam ekran) yerleştirilir.
 */
class VlcLive(
    private val activity: MainActivity,
    private val root: FrameLayout,
    private val cb: Callbacks,
) {
    interface Callbacks {
        fun onLiveBuffering()
        fun onLivePlaying()
        fun onLiveEnded()
        fun onLiveError()
        fun onLiveInfo(w: Int, h: Int, fps: Double, codec: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val layout: VLCVideoLayout = VLCVideoLayout(activity)
    private var lib: LibVLC? = null
    private var mp: MediaPlayer? = null
    private var released = false
    private var started = false
    private var buffering = false
    var url: String? = null
        private set

    init {
        layout.visibility = View.GONE
        root.addView(layout, 0, FrameLayout.LayoutParams(0, 0))
    }

    fun setLayout(lp: FrameLayout.LayoutParams) { layout.layoutParams = lp }

    private fun ensure(): MediaPlayer {
        mp?.let { return it }
        val l = lib ?: LibVLC(activity, arrayListOf(
            "--network-caching=6000",      // 6 sn pay: sağlayıcının düzenli kısa duraklamalarını örter
            "--live-caching=6000",
            "--no-stats",
        )).also { lib = it }
        return MediaPlayer(l).also { p ->
            mp = p
            p.attachViews(layout, null, false, false)
            p.setEventListener(MediaPlayer.EventListener { e -> main.post { onEvent(e) } })
        }
    }

    /** @param ua boşsa VLC'nin kendi kimliği */
    fun play(u: String, ua: String, volume: Float, audioDelayUs: Long) {
        val p = ensure()
        val l = lib ?: return
        url = u; started = false; buffering = true
        layout.visibility = View.VISIBLE
        val m = Media(l, Uri.parse(u))
        m.setHWDecoderEnabled(true, false)
        if (ua.isNotBlank()) m.addOption(":http-user-agent=$ua")
        p.media = m
        m.release()
        p.setVolume((volume * 100).toInt().coerceIn(0, 100))
        p.play()
        try { p.setAudioDelay(audioDelayUs) } catch (_: Exception) {}
    }

    private fun onEvent(e: MediaPlayer.Event) {
        if (released) return
        when (e.type) {
            MediaPlayer.Event.Buffering -> {
                if (e.buffering < 100f) { if (!buffering) { buffering = true; cb.onLiveBuffering() } }
                else if (buffering) { buffering = false; if (started) cb.onLivePlaying() }
            }
            MediaPlayer.Event.Vout, MediaPlayer.Event.Playing, MediaPlayer.Event.TimeChanged -> {
                if (!started && (e.type != MediaPlayer.Event.TimeChanged || e.timeChanged > 0)) {
                    started = true; buffering = false
                    cb.onLivePlaying()
                    main.postDelayed({ sendInfo() }, 800)
                }
            }
            MediaPlayer.Event.EndReached -> cb.onLiveEnded()
            MediaPlayer.Event.EncounteredError -> cb.onLiveError()
            else -> {}
        }
    }

    private fun sendInfo() {
        val p = mp ?: return
        try {
            val vt = p.currentVideoTrack ?: return
            val fps = if (vt.frameRateDen > 0) vt.frameRateNum.toDouble() / vt.frameRateDen else 0.0
            cb.onLiveInfo(vt.width, vt.height, fps, vt.codec ?: "")
        } catch (_: Throwable) {}
    }

    val isPlaying: Boolean get() = mp?.isPlaying == true
    fun pause() { mp?.let { if (it.isPlaying) it.pause() } }
    fun resume() { mp?.play() }
    fun toggle() { val p = mp ?: return; if (p.isPlaying) p.pause() else p.play() }
    fun setVolume(v: Float) { mp?.setVolume((v * 100).toInt().coerceIn(0, 100)) }
    fun setAudioDelay(us: Long) { try { mp?.setAudioDelay(us) } catch (_: Exception) {} }

    /** Oynatmayı durdur, görüntüyü gizle (motor bellekte kalır; sonraki kanal hızlı açılır) */
    fun stop() {
        url = null; started = false
        try { mp?.stop() } catch (_: Exception) {}
        layout.visibility = View.GONE
    }

    fun release() {
        if (released) return
        released = true
        main.removeCallbacksAndMessages(null)
        try { mp?.setEventListener(null) } catch (_: Exception) {}
        try { mp?.stop() } catch (_: Exception) {}
        try { mp?.detachViews() } catch (_: Exception) {}
        try { mp?.release() } catch (_: Exception) {}
        try { lib?.release() } catch (_: Exception) {}
        mp = null; lib = null
        root.removeView(layout)
    }
}
