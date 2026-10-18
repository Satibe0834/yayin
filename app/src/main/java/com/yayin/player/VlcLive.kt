package com.yayin.player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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

    // Kanal değişirken siyah ekran yerine eski kanalın son karesi (yeni görüntü gelene kadar)
    private val still: ImageView = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
    }
    private val hideStill: Runnable = Runnable { still.visibility = View.GONE; still.setImageDrawable(null); lastStill?.recycle(); lastStill = null }
    private var lastStill: Bitmap? = null

    init {
        layout.visibility = View.GONE
        root.addView(layout, 0, FrameLayout.LayoutParams(0, 0))
        root.addView(still, 1, FrameLayout.LayoutParams(0, 0))
    }

    fun setLayout(lp: FrameLayout.LayoutParams) {
        layout.layoutParams = lp
        still.layoutParams = FrameLayout.LayoutParams(lp.width, lp.height).apply { leftMargin = lp.leftMargin; topMargin = lp.topMargin }
    }

    private fun findSurface(v: View): SurfaceView? {
        if (v is SurfaceView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findSurface(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Ekrandaki son kareyi yakala ve görüntünün üstünde göster (Android 7+) */
    private fun freezeFrame() {
        if (android.os.Build.VERSION.SDK_INT < 24 || !started || layout.visibility != View.VISIBLE) return
        val sv = findSurface(layout) ?: return
        val w = sv.width / 2
        val h = sv.height / 2
        if (w < 16 || h < 16) return
        try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            android.view.PixelCopy.request(sv, bmp, { res ->
                if (res == android.view.PixelCopy.SUCCESS && !released) {
                    main.removeCallbacks(hideStill)
                    lastStill?.recycle(); lastStill = bmp
                    still.setImageBitmap(bmp); still.visibility = View.VISIBLE
                    main.postDelayed(hideStill, 8_000)      // yeni kanal gecikirse en geç 8 sn'de kaldır
                } else bmp.recycle()
            }, main)
        } catch (_: Throwable) {}
    }

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
        freezeFrame()
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
                    main.removeCallbacks(hideStill); main.postDelayed(hideStill, 150)   // yeni görüntü geldi: son kareyi kaldır
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

    /** Atlanan (geç kalan) kare sayısı; bilinmiyorsa -1 */
    fun lostPictures(): Int {
        val p = mp ?: return -1
        return try { val m = p.media ?: return -1; try { (m as? Media)?.stats?.lostPictures ?: -1 } finally { m.release() } } catch (_: Throwable) { -1 }
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
        main.removeCallbacks(hideStill); hideStill.run()
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
        root.removeView(layout); root.removeView(still)
        lastStill?.recycle(); lastStill = null
    }
}
