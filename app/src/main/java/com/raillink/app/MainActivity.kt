package com.raillink.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
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

class MainActivity : ComponentActivity() {

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
            openLink(intent?.data)
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
                view.evaluateJavascript(VideoMode.PLAY_HOOK, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (view !== this@MainActivity.webView) {
                    view.evaluateJavascript(Polish.SCRIPT, null)
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
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
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

    private fun exitVideoMode() {
        if (!videoMode) return
        videoMode = false
        tabs.visibility = View.VISIBLE
        webView.evaluateJavascript(VideoMode.EXIT, null)
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
        offline.visibility = View.GONE
        target.visibility = View.VISIBLE
    }

    /** Open a link from outside the app in the right tab (Junction+ links go to the Junction+ tab). */
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
        intent.data?.let { if (isInternal(it)) openLink(it) }
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
