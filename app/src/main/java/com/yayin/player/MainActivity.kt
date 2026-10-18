package com.yayin.player

import android.Manifest
import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceResponse
import java.io.File
import android.provider.Settings
import android.speech.RecognizerIntent
import org.json.JSONObject
import org.json.JSONTokener
import android.media.AudioManager
import java.util.concurrent.CountDownLatch
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var native: NativePlayer

    // Videonun yerleşik tam ekran düğmesi için
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    // M3U dosyası seçme ve konum izni
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var geoCallback: GeolocationPermissions.Callback? = null
    private var geoOrigin: String? = null

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            fileCallback?.onReceiveValue(uri?.let { arrayOf(it) })
            fileCallback = null
        }

    private val remote: RemoteSetup by lazy {
        RemoteSetup { json -> runOnUiThread { js("window.yayinRemote && yayinRemote($json)"); stopRemoteSoon() } }
    }
    // Telefona yanıt gidebilsin diye sunucu biraz bekledikten sonra kapanır
    private fun stopRemoteSoon() = web.postDelayed({ remote.stop() }, 2_000)
    private val watchNext by lazy { WatchNext(this) }
    private val images by lazy { ImageShrinker(cacheDir) }
    private val phone by lazy { PhoneRemote(this) { json -> callJs(json) } }

    /** Telefondan gelen isteği sayfadaki window.yayinPhone(...) ile yanıtla (en fazla 8 sn bekler). */
    private fun callJs(json: String): String {
        val latch = CountDownLatch(1)
        var out = "{\"error\":\"timeout\"}"
        runOnUiThread {
            if (webDead) { latch.countDown(); return@runOnUiThread }
            web.evaluateJavascript(
                "(function(){try{return JSON.stringify(window.yayinPhone?window.yayinPhone($json):{error:'not ready'})}catch(e){return JSON.stringify({error:String(e)})}})()"
            ) { v ->
                out = try { (JSONTokener(v).nextValue() as? String) ?: "{}" } catch (_: Exception) { "{}" }
                latch.countDown()
            }
        }
        try { latch.await(8, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        return out
    }
    private val updater by lazy { Updater(this) { evt, data -> jsEvent(evt, data) } }
    private fun jsEvent(evt: String, data: JSONObject) = runOnUiThread {
        if (!webDead) web.evaluateJavascript("window.yayinNative && yayinNative('$evt', $data)", null)
    }
    private var webDead = false
    private val crashFile get() = File(filesDir, "crash.txt")

    private fun saveCrash(text: String) {
        try {
            crashFile.writeText("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · PersetTV ${BuildConfig.VERSION_NAME}\n\n$text")
        } catch (_: Exception) {}
    }

    /** Uygulama bir önceki açılışta çöktüyse sebebini gösterir (fotoğrafı çekilip gönderilebilsin diye). */
    private fun showLastCrash() {
        val f = crashFile
        if (!f.exists()) return
        val text = try { f.readText().take(3500) } catch (_: Exception) { "" }
        f.delete()
        if (text.isBlank()) return
        // Uzaktan hata raporu için kısa özet (ilk anlamlı satırlar)
        getSharedPreferences("yayin", MODE_PRIVATE).edit()
            .putString("lastCrash", text.lineSequence().filter { it.isNotBlank() }.take(3).joinToString(" | ").take(400)).apply()
        // Çökmenin sebebi FFmpeg ise bir daha kullanma (uygulama tekrar tekrar çökmesin)
        val ffmpegNote = if (text.contains("ffmpeg", ignoreCase = true)) {
            getSharedPreferences("yayin", MODE_PRIVATE).edit().putBoolean("ffmpegBroken", true).apply()
            "\n\nFFmpeg ses çözücü bu cihazda kapatıldı."
        } else ""
        android.app.AlertDialog.Builder(this)
            .setTitle("Uygulama son açılışta kapandı")
            .setMessage("Lütfen bu ekranın fotoğrafını çekip gönderin:\n\n$text$ffmpegNote")
            .setPositiveButton("Tamam", null)
            .show()
    }
    private var pageReady = false
    private var pendingOpen: String? = null

    // Sesli arama sonucu
    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: ""
        js("window.yayinVoice && yayinVoice(${JSONObject.quote(text)})")
    }

    private fun js(code: String) = web.evaluateJavascript(code, null)

    private val askLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            geoCallback?.invoke(geoOrigin, granted, false)
            geoCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Beklenmedik çökme olursa sebebini bir sonraki açılışta göstermek için kaydet
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            saveCrash(Log.getStackTraceString(e))
            prevHandler?.uncaughtException(t, e)
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        root = FrameLayout(this).apply { setBackgroundColor(INK) }
        web = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)   // canlı yayın videosu arkadan görünsün
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        native = NativePlayer(this, root, web)
        hideSystemBars()

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true                 // favoriler, liste, ilerleme kaydı
            mediaPlaybackRequiresUserGesture = false // kanal değişince otomatik oynat
            allowFileAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true  // IPTV sunucularındaki CORS engelini kaldırır
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setGeolocationEnabled(true)
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = "$userAgentString YayinApp/${BuildConfig.VERSION_NAME}"
        }

        web.addJavascriptInterface(Bridge(), "YayinAndroid")

        web.webViewClient = object : WebViewClient() {
            // Büyük logo/afişleri küçülterek ver (az bellekli cihazlarda çökmeyi önler)
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (request.method != "GET" || !(url.startsWith("http://") || url.startsWith("https://"))) return null
                val accept = request.requestHeaders["Accept"] ?: request.requestHeaders["accept"] ?: return null
                if (!accept.startsWith("image/")) return null
                return images.load(url)
            }

            // Görüntü motoru çökerse uygulamayı kapatmak yerine yeniden başlat
            @androidx.annotation.RequiresApi(26)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                saveCrash("Görüntü motoru kapandı (WebView). Çöktü: ${detail.didCrash()}" +
                    if (Build.VERSION.SDK_INT >= 26) ", öncelik: ${detail.rendererPriorityAtExit()}" else "")
                webDead = true
                root.removeView(view)
                view.destroy()
                recreate()
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pendingOpen?.let { u -> pendingOpen = null; js("window.yayinOpen && yayinOpen(${JSONObject.quote(u)})") }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme == "file") return false
                // Dış bağlantılar tarayıcıda açılır, uygulama sayfadan çıkmaz
                try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: Exception) {}
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view
                customCallback = callback
                root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                web.visibility = View.GONE
                hideSystemBars()
            }

            override fun onHideCustomView() = exitCustomView()

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    pickFile.launch("*/*")
                    true
                } catch (_: ActivityNotFoundException) {
                    fileCallback = null
                    Toast.makeText(this@MainActivity,
                        "Dosya yöneticisi bulunamadı. Liste bağlantısını kullanın.", Toast.LENGTH_LONG).show()
                    false
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                val granted = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    callback.invoke(origin, true, false)
                } else {
                    geoOrigin = origin
                    geoCallback = callback
                    askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            }
        }

        // Geri tuşu: önce uygulama içinde bir ekran geri gider, ana ekranda çift basışla çıkar
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) { exitCustomView(); return }
                if (native.mode == NativePlayer.Mode.VOD) { native.closeVod(); return }
                web.evaluateJavascript(
                    "(window.yayinBack && window.yayinBack()) ? 'yes' : 'no'"
                ) { result ->
                    if (result?.contains("yes") != true) finish()
                }
            }
        })

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl("file:///android_asset/index.html")
        }
        handleDeepLink(intent)
        showLastCrash()
    }

    // Kumandadaki özel tuşlar (kanal +/-, oynat/duraklat, ileri/geri sar, menü)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Film/dizi VLC ile oynuyorsa tuşlar VLC ekranına (Geri tuşu hariç: o filmi kapatır)
        if (native.mode == NativePlayer.Mode.VOD && native.vlcActive) {
            if (native.vlcKey(event)) return true
            return super.dispatchKeyEvent(event)
        }
        if (native.mode == NativePlayer.Mode.VOD && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { native.toggle(); native.view.showController(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { native.seekBy(30_000); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { native.seekBy(-30_000); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> { native.next(); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> { native.previous(); return true }
            }
            // Kontroller gizliyken ← → doğrudan 10 sn sarar
            if (!native.view.isControllerFullyVisible) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { native.seekBy(-10_000); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { native.seekBy(10_000); return true }
                }
            }
        }
        if (native.mode == NativePlayer.Mode.VOD) return super.dispatchKeyEvent(event)
        if (customView == null) {
            val cmd = when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> "chup"
                KeyEvent.KEYCODE_CHANNEL_DOWN -> "chdown"
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> "playpause"
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "ff"
                KeyEvent.KEYCODE_MEDIA_REWIND -> "rw"
                KeyEvent.KEYCODE_MEDIA_NEXT -> "next"
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "prev"
                KeyEvent.KEYCODE_MENU -> "menu"
                KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST -> "voice"
                else -> null
            }
            if (cmd != null) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    web.evaluateJavascript("window.yayinKey && window.yayinKey('$cmd')", null)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun exitCustomView() {
        customView?.let { root.removeView(it) }
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        web.visibility = View.VISIBLE
        web.requestFocus()
        hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).run {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!webDead) web.saveState(outState)
    }

    override fun onPause() { native.onPause(); if (!webDead) web.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); if (!webDead) web.onResume(); native.onResume(); updater.onResume() }
    override fun onDestroy() { phone.stop(); remote.stop(); native.release(); if (!webDead) web.destroy(); super.onDestroy() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    /** Google TV ana ekranındaki "İzlemeye devam et" kartından gelen açılış: yayin://open?u=... */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "yayin") return
        val u = data.getQueryParameter("u") ?: return
        if (native.mode == NativePlayer.Mode.VOD) native.closeVod()
        if (pageReady) js("window.yayinOpen && yayinOpen(${JSONObject.quote(u)})") else pendingOpen = u
    }

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Kanal, film ya da dizi adı söyleyin")
        }
        try { voice.launch(i) } catch (_: Exception) {
            Toast.makeText(this, "Bu cihazda sesli arama bulunamadı", Toast.LENGTH_LONG).show()
            js("window.yayinVoice && yayinVoice('')")
        }
    }

    fun openExternal(url: String, title: String?) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")
            putExtra("title", title ?: "")
        }
        try {
            startActivity(Intent.createChooser(intent, "Oynatıcı seçin"))
        } catch (_: Exception) {
            Toast.makeText(this, "Yüklü bir video oynatıcı bulunamadı", Toast.LENGTH_LONG).show()
        }
    }

    /** Sayfadaki JavaScript'in çağırabildiği yerel işlevler (window.YayinAndroid). */
    inner class Bridge {
        @JavascriptInterface
        fun deviceId(): String {
            val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""
            return id.padStart(12, '0').take(12).chunked(2).joinToString(":")
        }

        @JavascriptInterface
        fun isTv(): Boolean {
            val ui = getSystemService(UI_MODE_SERVICE) as UiModeManager
            return ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        }

        /** Oynatılamayan yayınlar için VLC, MX Player gibi yüklü bir oynatıcıyı açar. */
        @JavascriptInterface
        fun openExternal(url: String, title: String?) = runOnUiThread { this@MainActivity.openExternal(url, title) }

        // ---- Yerel oynatıcı (ExoPlayer) ----
        @JavascriptInterface fun nativeLive(url: String) = runOnUiThread { native.playLive(url) }
        @JavascriptInterface fun nativeStop() = runOnUiThread { native.stop() }
        @JavascriptInterface fun nativeRect(x: Float, y: Float, w: Float, h: Float) = runOnUiThread { native.setRect(x, y, w, h) }
        @JavascriptInterface fun nativeToggle() = runOnUiThread { native.toggle() }
        @JavascriptInterface fun nativeMute(m: Boolean) = runOnUiThread { native.setMuted(m) }
        @JavascriptInterface fun nativeVolume(v: Float) = runOnUiThread { native.setVolume(v) }
        @JavascriptInterface fun nativePreconnect(url: String) { native.preconnect(url) }
        @JavascriptInterface fun nativeAspect(mode: String) = runOnUiThread { native.setAspect(mode) }
        @JavascriptInterface fun nativeAudioDelay(ms: Int) = runOnUiThread { native.setAudioDelay(ms) }
        @JavascriptInterface fun nativeLandscape(on: Boolean) = runOnUiThread { native.setJsLandscape(on) }
        @JavascriptInterface fun nativeConfig(json: String) = runOnUiThread { native.applyConfig(json) }
        @JavascriptInterface fun nativeCloseVod() = runOnUiThread { native.closeVod() }

        // ---- Önizleme ve çoklu ekran ----
        @JavascriptInterface fun auxPlay(slot: Int, url: String, muted: Boolean) = runOnUiThread { native.auxPlay(slot, url, muted) }
        @JavascriptInterface fun auxRect(slot: Int, x: Float, y: Float, w: Float, h: Float) = runOnUiThread { native.auxRect(slot, x, y, w, h) }
        @JavascriptInterface fun auxStop(slot: Int) = runOnUiThread { native.auxStop(slot) }
        @JavascriptInterface fun auxMute(slot: Int, m: Boolean) = runOnUiThread { native.auxMute(slot, m) }

        // ---- Telefonla kurulum ----
        @JavascriptInterface fun remoteStart(): String = remote.start()
        @JavascriptInterface fun remoteStop() = runOnUiThread { stopRemoteSoon() }

        // ---- Sesli arama ----
        @JavascriptInterface fun voiceSearch() = runOnUiThread { startVoice() }

        // ---- Aile mesajları: Google E-Tablo / GitHub dosyasını oku (tarayıcı engellerse) ----
        @JavascriptInterface fun httpGet(url: String): String {
            val ok = url.startsWith("https://docs.google.com/") || url.startsWith("https://raw.githubusercontent.com/")
            if (!ok) return ""
            return try {
                val c = okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS).followRedirects(true).followSslRedirects(true).build()
                c.newCall(okhttp3.Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { r ->
                    if (!r.isSuccessful) "" else (r.body?.string() ?: "").take(300_000)
                }
            } catch (_: Exception) { "" }
        }

        // ---- Maç Merkezi: resmi maç programı (arka planda indir, sonucu JS'e geri ver) ----
        @JavascriptInterface fun httpGetAsync(id: String, url: String) {
            if (!url.startsWith("https://site.api.espn.com/")) { httpCb(id, false, ""); return }
            Thread {
                var err = ""
                val body = try {
                    val c = okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).followRedirects(true).build()
                    // Tarayıcı gibi tanıt: bazı servisler kimliksiz istekleri reddediyor
                    val req = okhttp3.Request.Builder().url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 11; AFTKA) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Accept-Language", "en-US,en;q=0.8")
                        .build()
                    c.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) { err = "HTTP " + r.code; null } else (r.body?.string() ?: "").take(4_000_000)
                    }
                } catch (e: Exception) { err = (e.javaClass.simpleName + " " + (e.message ?: "")).trim().take(80); null }
                httpCb(id, body != null, body ?: err)
            }.start()
        }
        private fun httpCb(id: String, ok: Boolean, body: String) {
            val js = "window.yayinHttp&&window.yayinHttp(${org.json.JSONObject.quote(id)},$ok,${org.json.JSONObject.quote(body)})"
            runOnUiThread { web.evaluateJavascript(js, null) }
        }

        // ---- Telefon kumandası ----
        @JavascriptInterface fun phoneStart(): String = phone.start()
        @JavascriptInterface fun phoneStop() { phone.stop() }
        @JavascriptInterface fun phoneNewToken(): String = phone.newToken()

        // ---- Ses, film komutları, canlı geri sarma ----
        @JavascriptInterface fun sysVolume(dir: Int) = runOnUiThread {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                when { dir > 0 -> AudioManager.ADJUST_RAISE; dir < 0 -> AudioManager.ADJUST_LOWER; else -> AudioManager.ADJUST_TOGGLE_MUTE },
                AudioManager.FLAG_SHOW_UI)
        }
        @JavascriptInterface fun nativeVodCmd(cmd: String) = runOnUiThread { native.vodCmd(cmd) }
        @JavascriptInterface fun nativeTs(cmd: String, arg: Double) = runOnUiThread { native.tsCmd(cmd, arg.toLong()) }
        @JavascriptInterface fun ffmpegState(): String = try { native.ffmpegState() } catch (_: Exception) { "missing" }
        @JavascriptInterface fun ffmpegRetry() = runOnUiThread { native.ffmpegRetry() }
        @JavascriptInterface fun nativeLiveEx(url: String, noFfmpeg: Boolean, retry: Boolean) = runOnUiThread { native.playLiveEx(url, noFfmpeg, retry) }
        /** Son çökmenin kısa özeti (bir kez verilir), uzaktan hata raporu için. */
        @JavascriptInterface fun takeLastCrash(): String {
            val p = getSharedPreferences("yayin", MODE_PRIVATE); val c = p.getString("lastCrash", "") ?: ""
            if (c.isNotEmpty()) p.edit().remove("lastCrash").apply()
            return c
        }

        // ---- Uygulama içi güncelleme ----
        @JavascriptInterface fun updateInfo(): String = updater.info()
        @JavascriptInterface fun updateDownload(url: String) { updater.download(url, 0) }
        @JavascriptInterface fun updateDownloadV(url: String, version: Int) = runOnUiThread { updater.download(url, version) }
        @JavascriptInterface fun updateInstall() = runOnUiThread { updater.install() }
        @JavascriptInterface fun updateCanInstall(): Boolean = updater.canInstall()
        @JavascriptInterface fun updateRequestPermission() = runOnUiThread { updater.requestPermission() }

        // ---- Google TV "İzlemeye devam et" ----
        @JavascriptInterface fun watchNext(json: String) { watchNext.update(json) }
        @JavascriptInterface fun watchNextRemove(id: String) { watchNext.remove(id) }
        @JavascriptInterface
        fun nativeVod(json: String, index: Int, startMs: Double) =
            runOnUiThread { native.playVod(json, index, startMs.toLong()) }

        @JavascriptInterface
        fun exitApp() = runOnUiThread { finish() }

        @JavascriptInterface
        fun version(): String = BuildConfig.VERSION_NAME
    }

    private companion object {
        val INK = Color.parseColor("#140A0D")
    }
}
