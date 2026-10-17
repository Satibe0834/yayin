package com.yayin.player

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.TextView
import android.widget.Toast
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Film/dizi için VLC motoru. VLC standart dışı yazılmış dosyaları (bozuk boyut bilgisi, sonda "moov",
 * tuhaf sunucu davranışları) diğer oynatıcılar gibi tolere eder.
 *
 * Kumanda: OK / ⏯ duraklat · ◀ ▶ 10 sn (basılı tutunca 30 sn) · ⏪ ⏩ 30 sn ·
 *          ▲ ses dili · ▼ altyazı · kanal +/- sonraki/önceki bölüm · Geri: kapat
 */
class VlcVod(
    private val activity: MainActivity,
    private val root: FrameLayout,
    private val cb: Callbacks,
    /** Telefon/tablet: dokunmatik düğmeler ve sürüklenebilir zaman çubuğu */
    private val touch: Boolean = false,
) {
    interface Callbacks {
        fun onVlcStarted()
        fun onVlcEnded()
        fun onVlcError(msg: String)
        fun onVlcNext(delta: Int)
        fun onVlcStall()
        fun onVlcClose()
    }

    private val main = Handler(Looper.getMainLooper())
    private var lib: LibVLC? = null
    private var mp: MediaPlayer? = null
    private var started = false
    private var released = false
    var lengthMs = 0L
        private set

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    // ---------------- Görünüm ----------------

    private val container: FrameLayout = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
    private val videoLayout = VLCVideoLayout(activity)
    private val spinner: ProgressBar = ProgressBar(activity).apply { isIndeterminate = true }
    private val titleText: TextView = TextView(activity).apply {
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); paint.isFakeBoldText = true
    }
    private val subText: TextView = TextView(activity).apply {
        setTextColor(Color.argb(190, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }
    private var dragging = false
    private val bar: SeekBar = SeekBar(activity).apply {
        max = 1000; progress = 0
        isFocusable = false
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                if (fromUser && lengthMs > 0) timeText.text = "${fmt(lengthMs * v / 1000)} / ${fmt(lengthMs)}"
            }
            override fun onStartTrackingTouch(sb: SeekBar) { dragging = true; main.removeCallbacks(hideOverlay) }
            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                val p = mp; if (p != null && lengthMs > 0) p.setTime(lengthMs * sb.progress / 1000)
                showOverlay()
            }
        })
    }
    private val timeText: TextView = TextView(activity).apply {
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }
    private val hintText: TextView = TextView(activity).apply {
        setTextColor(Color.argb(160, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        text = "OK: duraklat  ·  ◀ ▶: 10 sn  ·  ▲: ses dili  ·  ▼: altyazı  ·  CH +/-: bölüm"
    }
    // Teşhis satırı: indirme hızı, atlanan kare, takılma sayısı
    private val statText: TextView = TextView(activity).apply {
        setTextColor(Color.argb(200, 255, 220, 120)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }
    private val stateText: TextView = TextView(activity).apply {
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); paint.isFakeBoldText = true
    }
    private fun touchBtn(label: String, onTap: () -> Unit): TextView = TextView(activity).apply {
        text = label; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(Color.argb(110, 255, 255, 255)) }
        isFocusable = false
        setOnClickListener { onTap(); showOverlay() }
    }
    private val touchRow: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        val lp = { LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(5), 0, dp(5), 0) } }
        addView(touchBtn("⏮") { cb.onVlcNext(-1) }, lp())
        addView(touchBtn("⏪ 10") { seekBy(-10_000) }, lp())
        addView(touchBtn("⏯") { toggle() }, lp())
        addView(touchBtn("10 ⏩") { seekBy(10_000) }, lp())
        addView(touchBtn("⏭") { cb.onVlcNext(1) }, lp())
        addView(touchBtn("🔊") { cycleAudio() }, lp())
        addView(touchBtn("💬") { cycleSub() }, lp())
    }
    private val closeBtn: TextView = TextView(activity).apply {
        text = "✕"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        gravity = Gravity.CENTER; isFocusable = false
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(120, 0, 0, 0)) }
        setOnClickListener { cb.onVlcClose() }
    }

    private val overlay: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(32), dp(48), dp(32), dp(22))
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(230, 0, 0, 0), Color.TRANSPARENT))
        addView(titleText); addView(subText)
        addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, dp(8)).apply { topMargin = dp(14) })
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(stateText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(16) })
            addView(timeText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(statText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        if (touch) addView(touchRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        else addView(hintText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
    }

    init {
        container.addView(videoLayout, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        container.addView(spinner, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        container.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        if (touch) {
            container.addView(closeBtn, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply { setMargins(dp(16), dp(16), 0, 0) })
            // Dokunma: bir kez → kontrolleri göster/gizle; çift dokunma sol/sağ yarı → 10 sn geri/ileri
            val gd = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (overlay.visibility == View.VISIBLE) { overlay.visibility = View.GONE; closeBtn.visibility = View.GONE }
                    else showOverlay()
                    return true
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    seekBy(if (e.x < container.width / 2f) -10_000 else 10_000); return true
                }
            })
            container.setOnTouchListener { _, ev -> gd.onTouchEvent(ev); true }
        }
        root.addView(container, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private val hideOverlay: Runnable = Runnable { if (mp?.isPlaying == true && !dragging) { overlay.visibility = View.GONE; closeBtn.visibility = View.GONE } }
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (released) return
            paintTime()
            checkStall()
            if (++tickN % 4 == 0) updateStats()
            main.postDelayed(this, 500)
        }
    }

    private var tickN = 0
    private var lastRead = -1L
    private var lastReadAt = 0L
    private var kbps = 0
    private var stalls = 0
    private var wasBuffering = false

    /** Her 2 sn: sunucudan gelen hız (Mbit/sn), atlanan kare sayısı, takılma sayısı */
    private fun updateStats() {
        val p = mp ?: return
        try {
            val m = p.media ?: return
            val st = try { (m as? Media)?.stats } finally { m.release() } ?: return
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastRead >= 0 && now > lastReadAt) kbps = (((st.readBytes - lastRead) * 8L) / (now - lastReadAt)).toInt()
            lastRead = st.readBytes.toLong(); lastReadAt = now
            val mbit = String.format(java.util.Locale.GERMANY, "%.1f", kbps / 1000.0)
            statText.text = "📶 $mbit Mbit/sn  ·  atlanan kare ${st.lostPictures}  ·  gösterilen ${st.displayedPictures}  ·  takılma $stalls"
        } catch (_: Throwable) {}
    }

    // Donma bekçisi: kullanıcı duraklatmadığı hâlde film 15 sn ilerlemezse yeniden bağlan
    private var userPaused = false
    private var lastT = -1L
    private var stuckAt = 0L
    private fun checkStall() {
        val p = mp ?: return
        if (!started || userPaused) { lastT = -1L; return }
        val now = android.os.SystemClock.elapsedRealtime()
        val t = p.time
        if (t != lastT) { lastT = t; stuckAt = now; return }
        if (now - stuckAt > 15_000) { stuckAt = now; cb.onVlcStall() }
    }

    fun showOverlay(ms: Long = 4_000) {
        overlay.visibility = View.VISIBLE
        if (touch) closeBtn.visibility = View.VISIBLE
        paintTime()
        main.removeCallbacks(hideOverlay)
        main.postDelayed(hideOverlay, ms)
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }

    private fun paintTime() {
        val p = mp ?: return
        val t = p.time.coerceAtLeast(0)
        val len = if (p.length > 0) p.length else lengthMs
        if (dragging) return
        if (len > 0) { lengthMs = len; bar.progress = ((t * 1000) / len).toInt().coerceIn(0, 1000) }
        timeText.text = if (len > 0) "${fmt(t)} / ${fmt(len)}" else fmt(t)
        stateText.text = if (p.isPlaying) "▶" else "❚❚"
    }

    // ---------------- Oynatma ----------------

    /**
     * @param ua    boşsa VLC'nin kendi kimliği kullanılır
     * @param audio tercih edilen ses dili ("tr" vb.), boş = otomatik
     * @param sub   tercih edilen altyazı dili, "off" = kapalı, boş = otomatik
     */
    fun play(url: String, title: String, sub: String, startMs: Long, ua: String, audio: String, subLang: String, volume: Float, aspect: String) {
        titleText.text = title; subText.text = sub
        subText.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE
        userPaused = false; lastT = -1L; lastRead = -1L; kbps = 0
        started = false; lengthMs = 0L; lastBuf = -1f; lastState = "başlamadı"; sawVout = false
        spinner.visibility = View.VISIBLE
        showOverlay(6_000)
        val l = lib ?: LibVLC(activity, arrayListOf(
            "--network-caching=8000",      // 8 sn arabellek: yavaş sunucuda takılmayı azaltır
            "--file-caching=3000",
            "--no-stats",
        )).also { lib = it }
        val p = mp ?: MediaPlayer(l).also { np ->
            mp = np
            np.attachViews(videoLayout, null, true, false)
            np.setEventListener(MediaPlayer.EventListener { e -> main.post { onEvent(e) } })
        }
        val m = Media(l, Uri.parse(url))
        m.setHWDecoderEnabled(true, false)
        if (ua.isNotBlank()) m.addOption(":http-user-agent=$ua")
        if (startMs > 1000) m.addOption(":start-time=" + (startMs / 1000.0))
        if (audio.isNotBlank()) m.addOption(":audio-language=$audio")
        if (subLang.isNotBlank() && subLang != "off") m.addOption(":sub-language=$subLang")
        subOff = subLang == "off"
        p.media = m
        m.release()
        setAspect(aspect)
        p.setVolume((volume * 100).toInt().coerceIn(0, 100))
        p.play()
        main.removeCallbacks(tick); main.post(tick)
        main.removeCallbacks(startTimeout); main.postDelayed(startTimeout, 35_000)
    }

    private var subOff = false
    private var lastBuf = -1f
    private var lastState = "başlamadı"
    private var sawVout = false

    /** Açılamazsa teşhis: VLC nerede kaldı */
    private fun stateText(): String {
        val p = mp
        val len = p?.length ?: -1L
        return "durum: $lastState, yükleme %" + (if (lastBuf >= 0) lastBuf.toInt() else -1) +
            ", süre " + (if (len > 0) fmt(len) else "bilinmiyor") + ", görüntü " + (if (sawVout) "var" else "yok")
    }

    private val startTimeout: Runnable = Runnable {
        if (!started && !released) cb.onVlcError("35 sn içinde başlamadı (" + stateText() + ")")
    }

    private fun onEvent(e: MediaPlayer.Event) {
        if (released) return
        when (e.type) {
            MediaPlayer.Event.Vout, MediaPlayer.Event.Playing -> {
                if (e.type == MediaPlayer.Event.Vout) sawVout = true else lastState = "oynatılıyor"
                spinner.visibility = View.GONE
                if (!started && (e.type == MediaPlayer.Event.Vout || (mp?.time ?: 0L) > 0)) {
                    started = true
                    main.removeCallbacks(startTimeout)
                    if (subOff) try { mp?.setSpuTrack(-1) } catch (_: Exception) {}
                    cb.onVlcStarted()
                }
                paintTime()
            }
            MediaPlayer.Event.Buffering -> {
                if (started && e.buffering < 100f && !wasBuffering) { stalls++; wasBuffering = true; showOverlay(6_000) }
                if (e.buffering >= 100f) wasBuffering = false
                lastBuf = e.buffering; spinner.visibility = if (e.buffering < 100f) View.VISIBLE else View.GONE }
            MediaPlayer.Event.TimeChanged -> {
                if (!started && e.timeChanged > 0) {
                    started = true; spinner.visibility = View.GONE
                    main.removeCallbacks(startTimeout)
                    if (subOff) try { mp?.setSpuTrack(-1) } catch (_: Exception) {}
                    cb.onVlcStarted()
                }
            }
            MediaPlayer.Event.Opening -> lastState = "açılıyor"
            MediaPlayer.Event.Paused -> { paintTime(); showOverlay(60_000) }
            MediaPlayer.Event.LengthChanged -> { if (e.lengthChanged > 0) lengthMs = e.lengthChanged }
            MediaPlayer.Event.EndReached -> cb.onVlcEnded()
            MediaPlayer.Event.EncounteredError -> cb.onVlcError("VLC bu dosyayı açamadı (" + stateText() + ")")
            else -> {}
        }
    }

    val hasStarted get() = started
    val isPlaying get() = mp?.isPlaying == true
    val timeMs get() = mp?.time ?: 0L

    fun toggle() {
        val p = mp ?: return
        if (p.isPlaying) { p.pause(); userPaused = true } else { p.play(); userPaused = false }
        showOverlay()
    }
    fun pause() { mp?.let { if (it.isPlaying) it.pause() }; userPaused = true }
    fun resume() { mp?.play(); userPaused = false }
    fun setVolume(v: Float) { mp?.setVolume((v * 100).toInt().coerceIn(0, 100)) }

    fun seekBy(ms: Long) {
        val p = mp ?: return
        val len = if (p.length > 0) p.length else lengthMs
        var t = p.time + ms
        if (len > 0) t = t.coerceIn(0, len - 2_000)
        p.setTime(t.coerceAtLeast(0))
        showOverlay()
    }

    fun setAspect(mode: String) {
        val p = mp ?: return
        try {
            p.videoScale = when (mode) {
                "zoom" -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                "fill" -> MediaPlayer.ScaleType.SURFACE_FILL
                else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            }
        } catch (_: Exception) {}
    }

    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_SHORT).show()

    /** ▲: sıradaki ses parçası */
    private fun cycleAudio() {
        val p = mp ?: return
        val tracks = p.audioTracks?.filter { it.id >= 0 } ?: emptyList()
        if (tracks.size < 2) { toast("Tek ses parçası var"); return }
        val i = tracks.indexOfFirst { it.id == p.audioTrack }
        val n = tracks[(i + 1) % tracks.size]
        p.setAudioTrack(n.id)
        toast("Ses: " + n.name)
    }

    /** ▼: sıradaki altyazı (en sonda "kapalı") */
    private fun cycleSub() {
        val p = mp ?: return
        val tracks = p.spuTracks?.toList() ?: emptyList()   // ilk eleman genelde "Devre dışı" (id -1)
        if (tracks.size < 2) { toast("Altyazı yok"); return }
        val i = tracks.indexOfFirst { it.id == p.spuTrack }
        val n = tracks[(i + 1) % tracks.size]
        p.setSpuTrack(n.id)
        toast("Altyazı: " + if (n.id < 0) "Kapalı" else n.name)
    }

    /** Kumanda tuşları. Geri tuşu için false döner (uygulama kapatır). */
    fun onKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) {
            return e.keyCode != KeyEvent.KEYCODE_BACK
        }
        val step = if (e.repeatCount > 2) 30_000L else 10_000L
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_SPACE -> { if (e.repeatCount == 0) toggle(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-step); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(step); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-30_000); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30_000); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (e.repeatCount == 0) cycleAudio(); showOverlay(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> { if (e.repeatCount == 0) cycleSub(); showOverlay(); return true }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> { if (e.repeatCount == 0) cb.onVlcNext(1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (e.repeatCount == 0) cb.onVlcNext(-1); return true }
            KeyEvent.KEYCODE_BACK -> return false
        }
        showOverlay()
        return true
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
        root.removeView(container)
    }
}
