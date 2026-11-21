package dev.udemytv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : Activity() {

    companion object {
        const val HOME = "https://www.udemy.com/home/my-courses/learning/"
        const val SITE = "https://www.udemy.com/"
        private val ZOOMS = intArrayOf(100, 115, 130, 85)
    }

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var navJs: String
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var lastBack = 0L
    private val prefs by lazy { getSharedPreferences("udemytv", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        navJs = assets.open("tvnav.js").bufferedReader().use { it.readText() }

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        createWebView()
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(HOME)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        WebView.setWebContentsDebuggingEnabled(true) // inspect via chrome://inspect over adb
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        web.setBackgroundColor(Color.BLACK)

        val defaultUa = web.settings.userAgentString
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false) // target=_blank opens in the same view
            textZoom = prefs.getInt("zoom", 100)
            userAgentString = if (prefs.getBoolean("desktop", true)) desktopUa(defaultUa) else defaultUa
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        // Inject the navigation script before any page script runs (so it sees keys first).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, navJs, setOf("*"))
        }
        web.addJavascriptInterface(Bridge(), "TvBridge")
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.requestFocus()
    }

    /** Same Chrome version, desktop platform: gets the full site and no "open the app" prompts. */
    private fun desktopUa(def: String): String {
        val chrome = Regex("Chrome/[\\d.]+").find(def)?.value ?: "Chrome/120.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }

    private fun js(code: String) = web.evaluateJavascript(code, null)

    /* ---------------- remote keys ---------------- */

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> { if (first) onBack(); return true }
            KeyEvent.KEYCODE_MENU -> { if (first) showMenu(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (first) media("playpause"); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (first) media("play"); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (first) media("pause"); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) media("rew"); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) media("ff"); return true }
        }

        // Fullscreen video: the remote controls playback directly.
        if (customView != null) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) media("rew"); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) media("ff"); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (first) media("playpause"); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (first) media("wake"); return true }
            }
        }

        // Make the remote's OK button behave exactly like Enter inside the page.
        if (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            val enter = KeyEvent(
                e.downTime, e.eventTime, e.action, KeyEvent.KEYCODE_ENTER,
                e.repeatCount, e.metaState, e.deviceId, e.scanCode, e.flags, e.source
            )
            return super.dispatchKeyEvent(enter)
        }
        return super.dispatchKeyEvent(e)
    }

    private fun media(action: String) = js("window.__tv && __tv.media('$action')")

    private fun onBack() {
        if (customView != null) {
            js("document.exitFullscreen && document.fullscreenElement && document.exitFullscreen()")
            web.postDelayed({ if (customView != null) { customCallback?.onCustomViewHidden(); hideCustomView() } }, 400)
            return
        }
        web.evaluateJavascript("(window.__tv && __tv.back()) ? 1 : 0") { r ->
            if (r == "1") return@evaluateJavascript
            when {
                web.canGoBack() -> web.goBack()
                SystemClock.uptimeMillis() - lastBack < 2000 -> finish()
                else -> {
                    lastBack = SystemClock.uptimeMillis()
                    Toast.makeText(this, "Press Back again to exit", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /* ---------------- ☰ menu ---------------- */

    private fun showMenu() {
        val zoom = prefs.getInt("zoom", 100)
        val desktop = prefs.getBoolean("desktop", true)
        val items = arrayOf(
            "My Learning",
            "Udemy home / search",
            "Reload page",
            "Video fullscreen on/off",
            "Text size: $zoom%  (change)",
            "Desktop site: ${if (desktop) "ON" else "OFF"}  (change)",
            "Sign out & clear data",
            "Exit app"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Udemy TV")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> web.loadUrl(HOME)
                    1 -> web.loadUrl(SITE)
                    2 -> web.reload()
                    3 -> sendKeyToPage(KeyEvent.KEYCODE_F) // Udemy player shortcut: F = fullscreen
                    4 -> {
                        val next = ZOOMS[(ZOOMS.indexOf(zoom) + 1).mod(ZOOMS.size)]
                        prefs.edit().putInt("zoom", next).apply()
                        web.settings.textZoom = next
                        Toast.makeText(this, "Text size $next%", Toast.LENGTH_SHORT).show()
                    }
                    5 -> {
                        prefs.edit().putBoolean("desktop", !desktop).apply()
                        recreateWebView(web.url ?: HOME)
                    }
                    6 -> confirmSignOut()
                    7 -> finish()
                }
            }
            .setOnDismissListener { web.requestFocus() }
            .show()
    }

    /** A real key event (not JS), so the page treats it as a user action. */
    private fun sendKeyToPage(code: Int) {
        web.requestFocus()
        val t = SystemClock.uptimeMillis()
        web.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        web.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Sign out?")
            .setMessage("This removes your Udemy login and cached data from this app.")
            .setPositiveButton("Sign out") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                web.clearCache(true)
                web.clearHistory()
                web.loadUrl(HOME)
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { web.requestFocus() }
            .show()
    }

    private fun recreateWebView(url: String) {
        root.removeView(web)
        web.destroy()
        createWebView()
        web.loadUrl(url)
    }

    /* ---------------- fullscreen video ---------------- */

    private fun hideCustomView() {
        customView?.let { root.removeView(it) }
        customView = null
        customCallback = null
        web.visibility = View.VISIBLE
        web.requestFocus()
    }

    private inner class Chrome : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            // Needed for DRM-protected (Widevine) course videos.
            val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            runOnUiThread {
                if (allowed.isNotEmpty()) request.grant(allowed.toTypedArray()) else request.deny()
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) { callback.onCustomViewHidden(); return }
            customView = view
            customCallback = callback
            root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
            web.visibility = View.GONE
            view.isFocusable = true
            view.requestFocus()
        }

        override fun onHideCustomView() = hideCustomView()

        // Avoid the grey "play" placeholder before videos load.
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    /* ---------------- page loading ---------------- */

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Keep web pages inside the app; block intent://, market:// "open in app" links.
            return when (request.url.scheme) {
                "http", "https", "about", "data", "blob" -> false
                else -> true
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            view.evaluateJavascript(navJs, null) // fallback if document-start injection is unsupported
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            val retry = request.url.toString().replace("'", "%27")
            val html = """
                <html><body style="background:#111;color:#eee;font-family:sans-serif;text-align:center;padding-top:18vh">
                <h1>Can't reach Udemy</h1><p style="font-size:22px">${error.description}</p>
                <p><a href='$retry' style="display:inline-block;font-size:26px;color:#fff;background:#a435f0;padding:14px 34px;border-radius:8px;text-decoration:none">Try again</a></p>
                </body></html>
            """.trimIndent()
            view.loadDataWithBaseURL(retry, html, "text/html", "utf-8", retry)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            recreateWebView(HOME)
            return true
        }
    }

    /* ---------------- JS bridge ---------------- */

    private inner class Bridge {
        @JavascriptInterface
        fun showKeyboard() {
            runOnUiThread {
                web.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    /* ---------------- lifecycle ---------------- */

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        web.requestFocus()
    }

    override fun onPause() {
        // Pause video when leaving the app (Home button).
        js("document.querySelectorAll('video').forEach(function(v){v.pause()})")
        web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        root.removeAllViews()
        web.destroy()
        super.onDestroy()
    }
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
