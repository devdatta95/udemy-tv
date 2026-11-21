package dev.udemytv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/**
 * Two ways to drive the page with the Fire TV remote:
 *  - POINTER mode (normal pages): arrows move a mouse pointer, OK = real tap, page scrolls at the edges.
 *  - VIDEO mode (a lecture video on screen): OK play/pause, ◀ ▶ 10 s, ▲ ▼ speed, ☰ player menu.
 * The app switches to VIDEO mode automatically when a lecture video appears; Back returns to the pointer.
 */
class MainActivity : Activity() {

    companion object {
        const val HOME = "https://www.udemy.com/home/my-courses/learning/"
        const val SITE = "https://www.udemy.com/"
        private val ZOOMS = intArrayOf(100, 115, 130, 85)
        private val WIDTHS = intArrayOf(1280, 1440, 1100, 0) // 0 = site default (phone/tablet layout)
        private val SPEEDS = arrayOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
    }

    private enum class Mode { POINTER, VIDEO }

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var cursor: CursorView
    private lateinit var hint: TextView
    private lateinit var navJs: String

    private var mode = Mode.POINTER
    private var userChosePointer = false   // user pressed Back in video mode; stay in pointer for this lecture
    private var playerPath = ""            // lecture path the current player belongs to
    private var hasPlayer = false
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var touchDownTime = 0L
    private var okDown = false
    private var lastBack = 0L
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("udemytv", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        navJs = assets.open("tvnav.js").bufferedReader().use { it.readText() }

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        createWebView()
        cursor = CursorView(this)
        root.addView(cursor, FrameLayout.LayoutParams(MATCH, MATCH))
        hint = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setBackgroundColor(0xDD222222.toInt())
            setPadding(36, 18, 36, 18)
            visibility = View.GONE
        }
        root.addView(hint, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = 48 })

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) web.loadUrl(HOME)
    }

    /* ================= WebView ================= */

    private fun pageScript() = "window.__tvWidth=${prefs.getInt("width", 1280)};\n$navJs"

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        WebView.setWebContentsDebuggingEnabled(true) // chrome://inspect over adb
        web = WebView(this)
        root.addView(web, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        web.setBackgroundColor(Color.WHITE)

        val defaultUa = web.settings.userAgentString
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true   // scale the wide desktop layout down to the TV screen
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
            textZoom = prefs.getInt("zoom", 100)
            userAgentString = desktopUa(defaultUa)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, pageScript(), setOf("*"))
        }
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.requestFocus()
    }

    /** Desktop Chrome user agent (same Chrome version) = full Udemy site, no "get the app" screens. */
    private fun desktopUa(def: String): String {
        val chrome = Regex("Chrome/[\\d.]+").find(def)?.value ?: "Chrome/120.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }

    private fun recreateWebView(url: String) {
        root.removeView(web)
        web.destroy()
        createWebView()
        web.loadUrl(url)
    }

    private fun js(code: String) = web.evaluateJavascript(code, null)
    private fun media(action: String) = js("window.__tv && __tv.media('$action')")

    /* ================= modes ================= */

    private fun setMode(m: Mode, announce: Boolean = true) {
        if (mode == m) return
        mode = m
        cursor.visibility = if (m == Mode.POINTER) View.VISIBLE else View.GONE
        if (!announce) return
        if (m == Mode.VIDEO) {
            media("wake")
            showHint("Video controls:  OK play/pause  •  ◀ ▶ 10 s  •  ▲ ▼ speed  •  ☰ menu  •  Back = pointer")
        } else {
            showHint("Pointer mode:  arrows move  •  OK click  •  ⏪ ⏩ scroll" +
                if (hasPlayer) "  •  ⏯ back to video controls" else "")
        }
    }

    private fun showHint(text: String) {
        hint.text = text
        hint.visibility = View.VISIBLE
        ui.removeCallbacks(hideHint)
        ui.postDelayed(hideHint, 4500)
    }

    private val hideHint = Runnable { hint.visibility = View.GONE }

    /** Ask the page about once a second whether a lecture video is on screen. */
    private val poll = object : Runnable {
        override fun run() {
            web.evaluateJavascript("JSON.stringify(window.__tv ? __tv.state() : null)") { onState(it) }
            ui.postDelayed(this, 1000)
        }
    }

    private fun onState(raw: String?) {
        val s = try {
            val txt = JSONArray("[$raw]").optString(0, "null")
            if (txt == "null") null else JSONObject(txt)
        } catch (e: Exception) { null }
        val player = s?.optBoolean("player") == true || customView != null
        hasPlayer = player
        if (player) {
            val path = s?.optString("path") ?: playerPath
            if (path != playerPath) { playerPath = path; userChosePointer = false }
            if (!userChosePointer) setMode(Mode.VIDEO)
        } else {
            playerPath = ""
            userChosePointer = false
            setMode(Mode.POINTER)
        }
    }

    /* ================= remote keys ================= */

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        val video = mode == Mode.VIDEO || customView != null

        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> { if (first) onBack(); return true }
            KeyEvent.KEYCODE_MENU -> { if (first) { if (video) showPlayerMenu() else showMenu() }; return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (first) {
                    if (hasPlayer && mode == Mode.POINTER) { userChosePointer = false; setMode(Mode.VIDEO) }
                    media(when (e.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
                        else -> "playpause"
                    })
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (down) {
                    val back = e.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
                    if (video) media(if (back) "rew" else "ff")
                    else wheel(0f, if (back) 6f else -6f) // page up / page down
                }
                return true
            }
        }

        if (video) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) media("rew"); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) media("ff"); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (first) media("faster"); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (first) media("slower"); return true }
                KeyEvent.KEYCODE_DPAD_CENTER -> { if (first) media("playpause"); return true }
            }
            return super.dispatchKeyEvent(e)
        }

        // POINTER mode
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) moveCursor(e.keyCode, e.repeatCount)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (first) touch(MotionEvent.ACTION_DOWN)
                else if (e.action == KeyEvent.ACTION_UP && okDown) touch(MotionEvent.ACTION_UP)
                return true
            }
        }
        return super.dispatchKeyEvent(e) // keyboard keys, Enter from the on-screen keyboard, etc.
    }

    /* ================= pointer ================= */

    private fun moveCursor(code: Int, repeat: Int) {
        val unit = root.width / 1920f
        val step = min(16f + repeat * 7f, 90f) * unit
        var dx = 0f
        var dy = 0f
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> dx = -step
            KeyEvent.KEYCODE_DPAD_RIGHT -> dx = step
            KeyEvent.KEYCODE_DPAD_UP -> dy = -step
            KeyEvent.KEYCODE_DPAD_DOWN -> dy = step
        }
        val oldX = cursor.px
        val oldY = cursor.py
        cursor.moveTo(oldX + dx, oldY + dy)

        // Pushing against an edge scrolls whatever is under the pointer (page, side panel, list).
        val edge = 24f * unit
        val speed = min(1f + repeat * 0.25f, 4f)
        when {
            dy > 0 && cursor.py >= root.height - edge -> wheel(0f, -speed)
            dy < 0 && cursor.py <= edge -> wheel(0f, speed)
            dx > 0 && cursor.px >= root.width - edge -> wheel(speed, 0f)
            dx < 0 && cursor.px <= edge -> wheel(-speed, 0f)
        }

        if (okDown) touch(MotionEvent.ACTION_MOVE) else hover()
    }

    private fun pointer(action: Int, source: Int, h: Float = 0f, v: Float = 0f): MotionEvent {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = if (source == InputDevice.SOURCE_MOUSE) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = cursor.px; y = cursor.py; pressure = 1f; size = 1f
            setAxisValue(MotionEvent.AXIS_HSCROLL, h)
            setAxisValue(MotionEvent.AXIS_VSCROLL, v)
        }
        val now = SystemClock.uptimeMillis()
        val downAt = if (source == InputDevice.SOURCE_TOUCHSCREEN) touchDownTime else now
        return MotionEvent.obtain(downAt, now, action, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, source, 0)
    }

    private fun hover() {
        val ev = pointer(MotionEvent.ACTION_HOVER_MOVE, InputDevice.SOURCE_MOUSE)
        web.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    /** Mouse-wheel scroll at the pointer. Positive v = up, positive h = right (Android axis convention). */
    private fun wheel(h: Float, v: Float) {
        val ev = pointer(MotionEvent.ACTION_SCROLL, InputDevice.SOURCE_MOUSE, h, v)
        web.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    /** A real tap at the pointer: works on any page, inputs (opens keyboard) and checkboxes in frames. */
    private fun touch(action: Int) {
        if (action == MotionEvent.ACTION_DOWN) { touchDownTime = SystemClock.uptimeMillis(); okDown = true }
        if (action == MotionEvent.ACTION_UP) okDown = false
        web.requestFocus()
        val ev = pointer(action, InputDevice.SOURCE_TOUCHSCREEN)
        web.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /* ================= back ================= */

    private fun onBack() {
        if (okDown) touch(MotionEvent.ACTION_UP)
        if (customView != null) { exitFullscreen(); return }
        if (mode == Mode.VIDEO) {
            web.evaluateJavascript("(window.__tv && __tv.exitFullscreen()) ? 1 : 0") { r ->
                if (r != "1") { userChosePointer = true; setMode(Mode.POINTER) }
            }
            return
        }
        when {
            web.canGoBack() -> web.goBack()
            SystemClock.uptimeMillis() - lastBack < 2000 -> finish()
            else -> {
                lastBack = SystemClock.uptimeMillis()
                Toast.makeText(this, "Press Back again to exit", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /* ================= fullscreen ================= */

    private fun toggleFullscreen() {
        if (customView != null) { exitFullscreen(); return }
        web.evaluateJavascript("(window.__tv && __tv.isFullscreen()) ? 1 : 0") { r ->
            if (r == "1") { js("__tv.exitFullscreen()"); return@evaluateJavascript }
            sendKeyToPage(KeyEvent.KEYCODE_F) // Udemy player shortcut: F = fullscreen
            ui.postDelayed({
                if (customView == null) {
                    web.evaluateJavascript("(window.__tv && __tv.isFullscreen()) ? 1 : 0") { r2 ->
                        if (r2 != "1") js("__tv.cssFullscreen(true)") // fallback: stretch the video
                    }
                }
            }, 700)
        }
    }

    private fun exitFullscreen() {
        js("document.fullscreenElement && document.exitFullscreen()")
        ui.postDelayed({ if (customView != null) { customCallback?.onCustomViewHidden(); hideCustomView() } }, 400)
    }

    /** A real key event, so the page treats it as a user action. */
    private fun sendKeyToPage(code: Int) {
        web.requestFocus()
        val t = SystemClock.uptimeMillis()
        web.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        web.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    private fun hideCustomView() {
        customView?.let { root.removeView(it) }
        customView = null
        customCallback = null
        web.visibility = View.VISIBLE
        web.requestFocus()
    }

    /* ================= menus ================= */

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun showPlayerMenu() {
        val items = arrayOf(
            "Play / Pause",
            "Playback speed…",
            "Next lecture",
            "Previous lecture",
            "Fullscreen on/off",
            "Use pointer (captions, notes, lecture list…)",
            "My Learning",
            "Reload page",
            "Exit app"
        )
        dialog().setTitle("Video").setItems(items) { _, which ->
            when (which) {
                0 -> media("playpause")
                1 -> showSpeedMenu()
                2 -> media("next")
                3 -> media("prev")
                4 -> toggleFullscreen()
                5 -> { userChosePointer = true; setMode(Mode.POINTER) }
                6 -> web.loadUrl(HOME)
                7 -> web.reload()
                8 -> finish()
            }
        }.setOnDismissListener { web.requestFocus() }.show()
    }

    private fun showSpeedMenu() {
        val labels = SPEEDS.map { if (it == 1.0) "1× (normal)" else "$it×" }.toTypedArray()
        dialog().setTitle("Playback speed").setItems(labels) { _, which ->
            js("window.__tv && __tv.setSpeed(${SPEEDS[which]})")
        }.setOnDismissListener { web.requestFocus() }.show()
    }

    private fun showMenu() {
        val zoom = prefs.getInt("zoom", 100)
        val width = prefs.getInt("width", 1280)
        val widthLabel = when (width) {
            0 -> "Large (site default)"
            1100 -> "Bigger"
            1280 -> "Fit TV (recommended)"
            else -> "Smaller / more content"
        }
        val items = arrayOf(
            "My Learning",
            "Udemy home / search",
            "Reload page",
            "Screen layout: $widthLabel  (change)",
            "Text size: $zoom%  (change)",
            "Center the pointer",
            "Sign out & clear data",
            "Exit app"
        )
        dialog().setTitle("Udemy TV").setItems(items) { _, which ->
            when (which) {
                0 -> web.loadUrl(HOME)
                1 -> web.loadUrl(SITE)
                2 -> web.reload()
                3 -> {
                    val next = WIDTHS[(WIDTHS.indexOf(width) + 1).mod(WIDTHS.size)]
                    prefs.edit().putInt("width", next).apply()
                    recreateWebView(web.url ?: HOME)
                }
                4 -> {
                    val next = ZOOMS[(ZOOMS.indexOf(zoom) + 1).mod(ZOOMS.size)]
                    prefs.edit().putInt("zoom", next).apply()
                    web.settings.textZoom = next
                    Toast.makeText(this, "Text size $next%", Toast.LENGTH_SHORT).show()
                }
                5 -> cursor.center()
                6 -> confirmSignOut()
                7 -> finish()
            }
        }.setOnDismissListener { web.requestFocus() }.show()
    }

    private fun confirmSignOut() {
        dialog().setTitle("Sign out?")
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

    /* ================= WebView callbacks ================= */

    private inner class Chrome : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            // Needed for DRM-protected (Widevine) course videos.
            val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            runOnUiThread { if (allowed.isNotEmpty()) request.grant(allowed.toTypedArray()) else request.deny() }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) { callback.onCustomViewHidden(); return }
            customView = view
            customCallback = callback
            root.addView(view, 1, FrameLayout.LayoutParams(MATCH, MATCH))
            web.visibility = View.GONE
            hasPlayer = true
            userChosePointer = false
            setMode(Mode.VIDEO)
        }

        override fun onHideCustomView() = hideCustomView()

        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            when (request.url.scheme) {
                "http", "https", "about", "data", "blob" -> false
                else -> true // block intent://, market:// "open in app" links
            }

        override fun onPageFinished(view: WebView, url: String) {
            view.evaluateJavascript(pageScript(), null) // fallback if document-start injection is unsupported
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

    /* ================= lifecycle ================= */

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        web.requestFocus()
        ui.removeCallbacks(poll)
        ui.post(poll)
    }

    override fun onPause() {
        ui.removeCallbacks(poll)
        js("document.querySelectorAll('video').forEach(function(v){v.pause()})")
        web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        root.removeAllViews()
        web.destroy()
        super.onDestroy()
    }
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
