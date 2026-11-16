package com.raillink.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.CalendarContract
import java.time.ZoneId
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.content.res.Configuration
import android.os.Build
import android.webkit.JavascriptInterface
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

private const val PAGE_SIZE_FULL =
    "(function(){var d=document.documentElement;if(d){d.style.zoom='1';}})();"

class MainActivity : ComponentActivity() {

    companion object {
        /** Used by the long-press shortcut and the reminder notification. */
        const val ACTION_NEXT_EVENT = "com.raillink.app.NEXT_EVENT"
        const val ACTION_JUNCTION = "com.raillink.app.JUNCTION_PLUS"
    }

    private var openEventWhenReady = false


    private lateinit var root: FrameLayout
    // Two websites, one per tab. "webView" is whichever one is showing.
    private lateinit var webView: WebView
    private lateinit var railView: WebView
    private lateinit var jpView: WebView
    private lateinit var tabs: View
    private lateinit var tabRail: TextView
    private lateinit var tabJp: TextView
    private var jpLoaded = false
    private lateinit var progress: ProgressBar
    private lateinit var swipe: SwipeRefreshLayout
    private var pageAtTop = true
    // Host of the page currently showing; the app's own features only answer Rail Link pages.
    @Volatile private var currentPageInternal = false
    private val handler = Handler(Looper.getMainLooper())
    private val showProgress = Runnable { if (progress.progress < 100) progress.visibility = View.VISIBLE }
    private lateinit var offline: View

    // Full-screen video (when the viewer taps the player's full-screen button)
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var keepSplash = true
    private lateinit var loading: View
    private var loadingAnim: ObjectAnimator? = null

    // Fire TV / Android TV: on-screen pointer driven by the remote
    private var tvRemote: TvRemote? = null

    // Phones: video fills the screen when turned sideways
    private var videoMode = false
    private var nextEvent: RailEvent? = null
    private var eventHidden = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Block screenshots and screen recording (they come out black)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        splash.setKeepOnScreenCondition { keepSplash }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        railView = findViewById(R.id.webview)
        jpView = findViewById(R.id.webview_jp)
        webView = railView
        tabs = findViewById(R.id.tabs)
        tabRail = findViewById(R.id.tab_rail)
        tabJp = findViewById(R.id.tab_jp)
        progress = findViewById(R.id.progress)
        swipe = findViewById(R.id.swipe)
        offline = findViewById(R.id.offline)
        loading = findViewById(R.id.loading)
        keepSplash = false // the branded loading screen takes over from here
        startLoadingAnimation()

        // Keep content clear of the status bar, navigation bar and camera cut-out.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            if (videoMode) v.setPadding(0, 0, 0, 0)
            else v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupWebView(railView)
        setupWebView(jpView)
        setupPullToRefresh()
        tabRail.setOnClickListener { showTab(railView) }
        tabJp.setOnClickListener { showTab(jpView) }
        tabRail.isSelected = true

        // Let the video use the whole screen, including around the camera cut-out.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        if (isTvDevice(this)) {
            val cursor = CursorView(this)
            // Sits above the website but below the "No connection" screen.
            root.addView(
                cursor, 1,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            tvRemote = TvRemote(webView, cursor, root).also { remote ->
                webView.post { remote.show(true) }
            }
        }

        findViewById<Button>(R.id.retry).setOnClickListener {
            webView.settings.cacheMode = if (isOnline()) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
            offline.visibility = View.GONE
            webView.visibility = View.VISIBLE
            if (webView.url.isNullOrEmpty()) webView.loadUrl(Config.HOME_URL) else webView.reload()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreenView != null -> exitFullscreen()
                    videoMode -> exitVideoMode()
                    webView.canGoBack() -> webView.goBack()
                    webView === jpView -> showTab(railView) // Back from Junction+ returns to Rail Link
                    else -> finish()
                }
            }
        })

        if (savedInstanceState != null) {
            railView.restoreState(savedInstanceState)
            hideLoading()
        } else {
            handleStart(intent)
            UpdateChecker(this).check()
        }

        // Never keep the loading screen up longer than 12 seconds, even on a slow connection.
        root.postDelayed({ hideLoading() }, 12000)
    }

    private fun startLoadingAnimation() {
        val d = resources.displayMetrics.density
        findViewById<View>(R.id.loading_content).apply {
            alpha = 0f; scaleX = 0.94f; scaleY = 0.94f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(600)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        loadingAnim = ObjectAnimator.ofFloat(
            findViewById(R.id.loading_bar), View.TRANSLATION_X, -48 * d, 120 * d
        ).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun hideLoading() {
        if (loading.visibility != View.VISIBLE) return
        loading.animate().alpha(0f).setDuration(400).withEndAction {
            loading.visibility = View.GONE
            loadingAnim?.cancel()
            loadingAnim = null
        }.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView) {
        WebView.setWebContentsDebuggingEnabled(false)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true) // keeps subscribers signed in
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Lock-down: the website can't read files or other apps' content on the phone.
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            // With no internet, show the pages you looked at last instead of an error
            cacheMode = if (isOnline()) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
            textZoom = 100 // keep the website its normal size, even if the phone uses large text
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString RailLinkApp/1.0"
            offscreenPreRaster = true // smoother scrolling
        }
        // Give the website the phone's full attention for smooth playback and scrolling.
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        webView.overScrollMode = View.OVER_SCROLL_NEVER // pull-to-refresh replaces the edge glow
        // Long-press only does something in text boxes (e.g. to paste a password).
        webView.isHapticFeedbackEnabled = false
        webView.setOnLongClickListener {
            webView.hitTestResult.type != WebView.HitTestResult.EDIT_TEXT_TYPE
        }
        webView.addJavascriptInterface(JsBridge(webView), VideoMode.BRIDGE)
        webView.setBackgroundColor(getColor(R.color.black))

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (isYouTubeLink(uri) && onStreamSite(view.url)) return true // keep members on our site
                val scheme = uri.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") {
                    if (isInternal(uri)) {
                        if (scheme == "http") { // always use the secure version
                            view.loadUrl(uri.buildUpon().scheme("https").build().toString())
                            return true
                        }
                        return false
                    }
                    openExternally(uri)
                    return true
                }
                // mailto:, tel:, intent: and app links
                openExternally(uri)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (view !== this@MainActivity.webView) return // the hidden tab loads quietly
                currentPageInternal = url != null && isInternal(Uri.parse(url)) && url.startsWith("https://")
                exitVideoMode() // a new page never starts in video mode
                pageAtTop = true
                progress.progress = 0
                handler.removeCallbacks(showProgress)
                handler.postDelayed(showProgress, 300) // only show the bar if loading is slow
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                view.evaluateJavascript(Polish.SCRIPT, null)
                applyPageSize(view)
                view.evaluateJavascript(VideoMode.PLAY_HOOK, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (view !== this@MainActivity.webView) {
                    view.evaluateJavascript(Polish.SCRIPT, null)
                applyPageSize(view)
                    view.evaluateJavascript(VideoMode.PLAY_HOOK, null)
                    return
                }
                handler.removeCallbacks(showProgress)
                progress.animate().alpha(0f).setDuration(250).withEndAction {
                    progress.visibility = View.GONE
                    progress.alpha = 1f
                }.start()
                swipe.isRefreshing = false
                view.evaluateJavascript(Polish.SCRIPT, null)
                applyPageSize(view)
                keepSplash = false
                hideLoading()
                view.evaluateJavascript(VideoMode.PLAY_HOOK, null)
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame && view === this@MainActivity.webView) {
                    keepSplash = false
                    hideLoading()
                    webView.visibility = View.INVISIBLE
                    offline.visibility = View.VISIBLE
                    findViewById<Button>(R.id.retry).requestFocus() // so a TV remote can press it
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view !== this@MainActivity.webView) return
                progress.setProgress(newProgress, true)
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        fullscreenView = view
        fullscreenCallback = callback
        (window.decorView as FrameLayout).addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        view.setBackgroundColor(getColor(R.color.black))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun exitFullscreen() {
        val view = fullscreenView ?: return
        (window.decorView as FrameLayout).removeView(view)
        fullscreenView = null
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        ViewCompat.requestApplyInsets(root)
    }

    /** Called from the website when a video starts playing. */
    inner class JsBridge(private val owner: WebView) {
        @JavascriptInterface
        fun onVideoPlay() {
            if (!currentPageInternal || owner !== webView) return
            runOnUiThread { updateVideoMode() }
        }

        @JavascriptInterface
        fun onScrollTop(atTop: Boolean) {
            if (!currentPageInternal || owner !== webView) return
            pageAtTop = atTop
        }
    }

    private fun setupPullToRefresh() {
        swipe.setColorSchemeColors(getColor(R.color.rl_blue), getColor(R.color.rl_crimson))
        swipe.setProgressBackgroundColorSchemeColor(0xFF111A1A.toInt())
        swipe.setOnRefreshListener { webView.reload() }
        // Only when the page is at the very top, and never during video or on TVs.
        swipe.setOnChildScrollUpCallback { _, _ ->
            tvRemote != null || videoMode || fullscreenView != null ||
                webView.scrollY > 0 || !pageAtTop
        }
        if (isTvDevice(this)) swipe.isEnabled = false
        setUpEventStrip()
        findViewById<ImageButton>(R.id.share).apply {
            visibility = if (isTvDevice(this@MainActivity)) View.GONE else View.VISIBLE
            setOnClickListener { sharePage() }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        listOf(railView, jpView).forEach { applyPageSize(it) }
        updateVideoMode()
    }

    /** Sideways with a video playing: video fills the screen. Upright: back to normal. */
    private fun updateVideoMode() {
        if (tvRemote != null || fullscreenView != null) return
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape) enterVideoMode() else exitVideoMode()
    }

    private fun enterVideoMode() {
        webView.evaluateJavascript(VideoMode.ENTER) { result ->
            if (result?.contains("on") == true && !videoMode) {
                videoMode = true
                findViewById<View>(R.id.share).visibility = View.GONE
                findViewById<View>(R.id.event_strip).visibility = View.GONE
                webView.evaluateJavascript(PAGE_SIZE_FULL, null) // the video fills the whole screen
                progress.visibility = View.GONE
                tabs.visibility = View.GONE
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                ViewCompat.requestApplyInsets(root)
            }
        }
    }

    /**
     * Shows the website a little smaller on phones so it looks neat and fits more:
     * 85% upright, 80% sideways. TVs stay at full size. Videos playing full screen are never shrunk.
     */
    private fun pageSizeScript(): String {
        val size = when {
            tvRemote != null -> "1"
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE -> "0.8"
            else -> "0.85"
        }
        return "(function(){var d=document.documentElement;if(d&&d.style.zoom!=='$size'){d.style.zoom='$size';}})();"
    }

    private fun applyPageSize(view: WebView) {
        if (videoMode && view === webView) return
        view.evaluateJavascript(pageSizeScript(), null)
    }

    /** Next-event strip: name, countdown, Add to calendar and Directions. Phones only. */
    private fun setUpEventStrip() {
        if (isTvDevice(this)) return
        findViewById<View>(R.id.event_close).setOnClickListener { eventHidden = true; updateEventStrip() }
        findViewById<View>(R.id.event_calendar).setOnClickListener { nextEvent?.let { addToCalendar(it) } }
        findViewById<View>(R.id.event_directions).setOnClickListener { nextEvent?.let { openDirections(it) } }
        findViewById<View>(R.id.event_name).setOnClickListener {
            nextEvent?.link?.let { link -> showTab(railView); railView.loadUrl(link) }
        }
        Thread {
            val ev = Events.fetchNext()
            runOnUiThread {
                nextEvent = ev
                if (ev != null) {
                    Reminders.schedule(this, ev)
                    askForReminders()
                    if (openEventWhenReady) {
                        openEventWhenReady = false
                        ev.link?.let { showTab(railView); railView.loadUrl(it) }
                    }
                    findViewById<TextView>(R.id.event_name).text = ev.name
                    findViewById<TextView>(R.id.event_countdown).text = ev.countdownText()
                }
                updateEventStrip()
            }
        }.start()
    }

    private fun updateEventStrip() {
        val show = nextEvent != null && !eventHidden && !videoMode && webView === railView && !isTvDevice(this)
        findViewById<View>(R.id.event_strip).visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun addToCalendar(ev: RailEvent) {
        val zone = ZoneId.systemDefault()
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, ev.name)
            putExtra(CalendarContract.Events.EVENT_LOCATION, ev.venue)
            ev.link?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            if (ev.start != null) {
                val begin = ev.date.atTime(ev.start).atZone(zone).toInstant().toEpochMilli()
                val end = ev.date.atTime(ev.end ?: ev.start.plusHours(8)).atZone(zone).toInstant().toEpochMilli()
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            } else {
                putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ev.date.atStartOfDay(zone).toInstant().toEpochMilli())
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ev.date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            }
        }
        try { startActivity(intent) } catch (e: Exception) { }
    }

    private fun openDirections(ev: RailEvent) {
        val q = Uri.encode(ev.venue)
        val maps = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q"))
        try { startActivity(maps) } catch (e: Exception) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$q"))) } catch (_: Exception) { }
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Opens the phone's share menu with the page that's on screen. */
    private fun sharePage() {
        val url = webView.url ?: return
        val title = webView.title?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, "$title\n$url")
        }
        startActivity(Intent.createChooser(send, "Share"))
    }

    private fun exitVideoMode() {
        if (!videoMode) return
        videoMode = false
        if (!isTvDevice(this)) findViewById<View>(R.id.share).visibility = View.VISIBLE
        updateEventStrip()
        tabs.visibility = View.VISIBLE
        webView.evaluateJavascript(VideoMode.EXIT, null)
        applyPageSize(webView)
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ViewCompat.requestApplyInsets(root)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val remote = tvRemote
        if (remote != null && offline.visibility != View.VISIBLE &&
            remote.handle(event, fullscreenView)
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isYouTubeLink(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "vnd.youtube" || scheme == "youtube") return true
        val text = uri.toString().lowercase()
        if (scheme == "intent" && (text.contains("com.google.android.youtube") || text.contains("youtube.com") || text.contains("youtu.be"))) return true
        val host = uri.host?.lowercase() ?: return false
        return listOf("youtube.com", "youtu.be", "youtube-nocookie.com").any { host == it || host.endsWith(".$it") }
    }

    private fun onStreamSite(url: String?): Boolean {
        val host = url?.let { Uri.parse(it).host?.lowercase() } ?: return false
        return Config.STREAM_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun isInternal(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return Config.INTERNAL_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun openExternally(uri: Uri) {
        try {
            val intent = if (uri.scheme == "intent") {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    // Only allow links that ordinary web pages may open; never the app's own screens.
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // No app can open this link; ignore.
        } catch (e: Exception) {
            // Malformed link; ignore.
        }
    }

    /** Switch between the Rail Link and Junction+ tabs. Each keeps its own page and history. */
    private fun showTab(target: WebView) {
        if (target === jpView && !jpLoaded) {
            jpLoaded = true
            jpView.loadUrl(Config.JP_URL)
        }
        if (target === webView) {
            // Tapping the tab you're already on goes back to its home page.
            val home = if (target === jpView) Config.JP_URL else Config.HOME_URL
            if (target.url?.trimEnd('/') != home.trimEnd('/')) target.loadUrl(home)
            return
        }
        exitVideoMode()
        swipe.isRefreshing = false
        pageAtTop = true
        target.visibility = View.VISIBLE
        webView.visibility = View.GONE
        webView = target
        currentPageInternal = target.url?.let { isInternal(Uri.parse(it)) } ?: true
        tvRemote?.webView = target
        tabRail.isSelected = target === railView
        tabJp.isSelected = target === jpView
        updateEventStrip()
        offline.visibility = View.GONE
        target.visibility = View.VISIBLE
    }

    /** Open a link from outside the app in the right tab (Junction+ links go to the Junction+ tab). */
    /** Remembers the page you were on, so the app reopens there if you come back within 12 hours. */
    private fun saveLastPage(url: String?) {
        if (url.isNullOrEmpty() || !isInternal(Uri.parse(url))) return
        getSharedPreferences("app", MODE_PRIVATE).edit()
            .putString("last_url", url).putLong("last_time", System.currentTimeMillis()).apply()
    }

    private fun lastPage(): Uri? {
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        val url = prefs.getString("last_url", null) ?: return null
        val age = System.currentTimeMillis() - prefs.getLong("last_time", 0L)
        if (age > 12L * 60 * 60 * 1000) return null
        return Uri.parse(url).takeIf { isInternal(it) }
    }

    private fun openLink(data: Uri?) {
        val host = data?.host?.lowercase() ?: ""
        val isJp = host == "junctionplus.com" || host.endsWith(".junctionplus.com")
        when {
            data == null || !isInternal(data) -> railView.loadUrl(Config.HOME_URL)
            isJp -> {
                railView.loadUrl(Config.HOME_URL)
                jpLoaded = true
                jpView.loadUrl(data.toString())
                showTab(jpView)
            }
            else -> railView.loadUrl(data.toString())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.action) {
            ACTION_NEXT_EVENT, ACTION_JUNCTION -> handleStart(intent)
            else -> intent.data?.let { if (isInternal(it)) openLink(it) }
        }
    }

    /** Works out what to open: a shortcut, a link from outside, or the page you were last on. */
    private fun handleStart(intent: Intent?) {
        when (intent?.action) {
            ACTION_JUNCTION -> openLink(Uri.parse(Config.JP_URL))
            ACTION_NEXT_EVENT -> {
                eventHidden = false
                val link = nextEvent?.link
                if (link != null) { showTab(railView); railView.loadUrl(link) }
                else { openEventWhenReady = true; if (railView.url == null) railView.loadUrl(Config.HOME_URL); showTab(railView) }
            }
            else -> openLink(intent?.data ?: lastPage())
        }
    }

    /** Asks once (Android 13+) if the app may show event reminders. */
    private fun askForReminders() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notify", false)) return
        prefs.edit().putBoolean("asked_notify", true).apply()
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 5)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        railView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        railView.onResume()
        jpView.onResume()
    }

    override fun onPause() {
        saveLastPage(webView.url)
        railView.onPause()
        jpView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        railView.destroy()
        jpView.destroy()
        super.onDestroy()
    }
}
