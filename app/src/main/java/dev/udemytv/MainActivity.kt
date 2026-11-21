package dev.udemytv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.Choreographer
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.math.min

/**
 * Udemy for Fire TV.
 *
 * Pages are controlled in one of two styles (switch in the ☰ menu):
 *   POINTER     arrows glide a mouse pointer, OK clicks (default)
 *   NAVIGATION  arrows move a highlight between items like the YouTube app, OK opens
 *
 * Fullscreen video (☰ → Watch fullscreen) always uses the remote:
 *   OK / ⏯  play-pause     ◀ ▶  −5 s / +5 s (hold to keep going)     ▲ ▼  speed up / down
 *   ⏪ ⏩  previous / next lecture     Back  exit fullscreen     ☰  video menu
 */
class MainActivity : Activity() {

    companion object {
        const val HOME = "https://www.udemy.com/home/my-courses/learning/"
        const val SITE = "https://www.udemy.com/"
        private const val PURPLE = 0xFFA435F0.toInt()
        private val ZOOMS = intArrayOf(100, 115, 130, 85)
        private val WIDTHS = intArrayOf(1280, 1440, 1100, 0)
        private val SPEEDS = arrayOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
        private val POINTER_SPEEDS = floatArrayOf(1f, 1.4f, 0.7f)
        private val POINTER_SPEED_NAMES = arrayOf("Normal", "Fast", "Slow")

        /** Third-party analytics / ads scripts: heavy for the Fire TV CPU and not needed to learn. */
        private val BLOCKED = listOf(
            "google-analytics.com", "googletagmanager.com", "doubleclick.net", "googleadservices.com",
            "googlesyndication.com", "connect.facebook.net", "hotjar.com", "hotjar.io", "optimizely.com",
            "segment.io", "segment.com", "bat.bing.com", "clarity.ms", "snap.licdn.com", "px.ads.linkedin.com",
            "ads-twitter.com", "analytics.twitter.com", "analytics.tiktok.com", "braze.com", "appboycdn.com",
            "js-agent.newrelic.com", "nr-data.net", "browser-intake-datadoghq.com", "datadoghq-browser-agent.com",
            "fullstory.com", "quantserve.com", "criteo.com", "criteo.net", "adroll.com", "taboola.com",
            "outbrain.com", "amplitude.com", "mixpanel.com", "branch.io", "qualtrics.com", "ct.pinterest.com",
            "redditstatic.com", "sentry.io", "sentry-cdn.com"
        )
        private val EMPTY = ByteArray(0)
    }

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var cursor: CursorView
    private lateinit var progress: ProgressBar
    private lateinit var osd: TextView
    private lateinit var navJs: String
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("udemytv", Context.MODE_PRIVATE) }
    @Volatile private var blockTrackers = true

    // page state (polled)
    private var hasVideo = false
    private var jsFs = false
    private var path = ""
    private var lastFs = false

    // control style for pages
    private var navStyle = false

    // fullscreen
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fsWanted = false        // user asked for fullscreen; restore it when the lecture changes
    private var lastFsTry = 0L
    private var fsTries = 0

    // pointer
    private var cx = 0f
    private var cy = 0f
    private var moveKey = 0
    private var moveStart = 0L
    private var lastFrameNs = 0L
    private var lastHover = 0L
    private var lastWheel = 0L
    private var edgeX = -1f              // where to scroll while pushing an edge (a side panel, not the header)
    private var edgeY = -1f
    private var edgeKey = 0
    private var okHeld = false
    private var touchSent = false
    private var touchDownTime = 0L

    private var lastNavKey = 0L
    private var lastBack = 0L

    /* ===================================================================== setup */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        navJs = assets.open("tvnav.js").bufferedReader().use { it.readText() }
        blockTrackers = prefs.getBoolean("block", true)
        navStyle = prefs.getBoolean("nav", false)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        createWebView()

        cursor = CursorView(this)
        root.addView(cursor, FrameLayout.LayoutParams(WRAP, WRAP))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(PURPLE)
            progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        }
        root.addView(progress, FrameLayout.LayoutParams(MATCH, dp(4), Gravity.TOP))

        osd = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(dp(26), dp(12), dp(26), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0xE6161616.toInt()) }
            alpha = 0f
        }
        root.addView(osd, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = dp(40) })

        root.post {
            cx = root.width / 2f
            cy = root.height / 2f
            cursor.place(cx, cy)
            updatePointerVisibility()
        }
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) web.loadUrl(HOME)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pageScript() = "window.__tvWidth=${prefs.getInt("width", 1280)};\n$navJs"

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        WebView.setWebContentsDebuggingEnabled(true) // chrome://inspect over adb
        web = WebView(this)
        root.addView(web, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        web.setBackgroundColor(Color.WHITE)
        if (Build.VERSION.SDK_INT >= 26) web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)

        val defaultUa = web.settings.userAgentString
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true   // scale the desktop layout to the TV screen
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

    /** Desktop Chrome user agent (same Chrome version): full site, no "open the app" screens. */
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

    /* ===================================================================== JS helpers */

    private fun js(code: String) = web.evaluateJavascript(code, null)

    private fun jsString(raw: String?): String =
        try { JSONArray("[$raw]").optString(0, "").let { if (it == "null") "" else it } } catch (e: Exception) { "" }

    private fun jsObject(raw: String?): JSONObject? =
        try { jsString(raw).takeIf { it.startsWith("{") }?.let { JSONObject(it) } } catch (e: Exception) { null }

    /** Run a video action in the page and show its result on screen. */
    private fun media(action: String) {
        web.evaluateJavascript("window.__tv ? __tv.media('$action') : ''") { r ->
            jsString(r).takeIf { it.isNotEmpty() }?.let { osd(it) }
        }
    }

    /* ===================================================================== on-screen display */

    private val hideOsd = Runnable { osd.animate().alpha(0f).setDuration(250).start() }

    private fun osd(text: String, ms: Long = 1600) {
        osd.text = text
        osd.animate().cancel()
        osd.alpha = 1f
        ui.removeCallbacks(hideOsd)
        ui.postDelayed(hideOsd, ms)
    }

    /* ===================================================================== page state */

    private val poll = object : Runnable {
        override fun run() {
            val nav = navStyle && !isFs()
            web.evaluateJavascript("JSON.stringify(window.__tv ? __tv.state($nav) : null)") { onState(it) }
            ui.postDelayed(this, 700)
        }
    }

    private fun isFs() = customView != null || jsFs

    private fun onState(raw: String?) {
        val s = jsObject(raw)
        hasVideo = s?.optBoolean("v") == true || customView != null
        jsFs = s?.optBoolean("fs") == true && customView == null
        path = s?.optString("path") ?: path
        onFsChanged()

        // The lecture changed while in fullscreen (the video was replaced): go fullscreen again.
        val now = SystemClock.uptimeMillis()
        if (fsWanted && !isFs() && hasVideo && fsTries < 3 && now - lastFsTry > 2500) {
            fsTries++
            requestFullscreen()
        }
    }

    private fun onFsChanged() {
        val fs = isFs()
        if (fs == lastFs) return
        lastFs = fs
        stopMove()
        releaseOk()
        if (fs) {
            fsTries = 0
            if (!prefs.getBoolean("helpShown2", false)) {
                prefs.edit().putBoolean("helpShown2", true).apply()
                showHelp()
            } else osd("◀ ▶ 5 s   ▲ ▼ speed   ⏪ ⏩ lecture   Back exits   ☰ menu", 3000)
        }
        updatePointerVisibility()
    }

    private fun updatePointerVisibility() {
        if (!navStyle && !isFs()) showCursor()
        else { cursor.animate().cancel(); cursor.visibility = View.GONE }
    }

    private fun setNavStyle(on: Boolean) {
        navStyle = on
        prefs.edit().putBoolean("nav", on).apply()
        stopMove()
        releaseOk()
        js("window.__tv && __tv.state($on)")
        updatePointerVisibility()
        osd(if (on) "Navigation mode:  arrows move the highlight  •  OK opens" else "Pointer mode:  arrows move the pointer  •  OK clicks", 2500)
    }

    /* ===================================================================== keys */

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0

        if (isFs()) return fullscreenKey(e, code, down, first)

        when (code) {
            KeyEvent.KEYCODE_BACK -> { if (first) onBack(); return true }
            KeyEvent.KEYCODE_MENU -> { if (first) showMainMenu(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (first) { if (hasVideo) media(playAction(code)) else osd("No video on this page") }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (down) scrollPage(if (code == KeyEvent.KEYCODE_MEDIA_REWIND) 1f else -1f)
                return true
            }
        }
        return if (navStyle) navKey(e, code, down, first) else pointerKey(e, code, down, first)
    }

    private fun isOk(code: Int) =
        code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun playAction(code: Int) = when (code) {
        KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
        else -> "playpause"
    }

    /** Your remote layout for fullscreen video. */
    private fun fullscreenKey(e: KeyEvent, code: Int, down: Boolean, first: Boolean): Boolean {
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE ->
                if (first) media(playAction(code))
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                first -> media("fwd5")
                down && e.repeatCount % 3 == 0 -> media(if (e.repeatCount < 18) "fwd10" else "fwd30")
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                first -> media("back5")
                down && e.repeatCount % 3 == 0 -> media(if (e.repeatCount < 18) "back10" else "back30")
            }
            KeyEvent.KEYCODE_DPAD_UP -> if (first) media("faster")
            KeyEvent.KEYCODE_DPAD_DOWN -> if (first) media("slower")
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (first) changeLecture(1)
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (first) changeLecture(-1)
            KeyEvent.KEYCODE_BACK -> if (first) exitFullscreen()
            KeyEvent.KEYCODE_MENU -> if (first) showVideoMenu()
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }

    private fun pointerKey(e: KeyEvent, code: Int, down: Boolean, first: Boolean): Boolean {
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (first) startMove(code)
                else if (e.action == KeyEvent.ACTION_UP && moveKey == code) stopMove()
                return true
            }
        }
        if (isOk(code)) {
            if (first) okDown() else if (e.action == KeyEvent.ACTION_UP) okUp()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    private fun navKey(e: KeyEvent, code: Int, down: Boolean, first: Boolean): Boolean {
        val dir = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            else -> null
        }
        if (dir != null) {
            val now = SystemClock.uptimeMillis()
            if (down && (first || now - lastNavKey > 140)) { // holding moves steadily, without flooding a slow page
                lastNavKey = now
                web.evaluateJavascript("window.__tv ? __tv.navMove('$dir') : ''") { r ->
                    jsString(r).takeIf { it.isNotEmpty() }?.let { osd(it) }
                }
            }
            return true
        }
        if (isOk(code)) {
            if (first) web.evaluateJavascript("JSON.stringify(window.__tv ? __tv.navOk() : null)") { onClickResult(jsObject(it)) }
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    /* ===================================================================== clicks */

    /** Result of OK from the page: text box, fullscreen button, a real tap, or already handled. */
    private fun onClickResult(r: JSONObject?): Boolean {
        when (r?.optString("t")) {
            "text" -> showTextInput(r)
            "fs" -> enterFullscreen()
            "tap" -> tapAt(r.optDouble("fx").toFloat() * web.width, r.optDouble("fy").toFloat() * web.height)
            "done" -> r.optString("msg").takeIf { it.isNotEmpty() }?.let { osd(it) }
            else -> return false
        }
        return true
    }

    private fun okDown() {
        showCursor()
        okHeld = true
        touchSent = false
        val fx = cx / web.width
        val fy = cy / web.height
        web.evaluateJavascript("JSON.stringify(window.__tv ? __tv.hit($fx, $fy) : null)") { raw ->
            if (onClickResult(jsObject(raw))) {
                okHeld = false
            } else {
                // Not a text box or the video player: a real tap (works everywhere, also in frames).
                touch(MotionEvent.ACTION_DOWN)
                touchSent = true
                if (!okHeld) { touch(MotionEvent.ACTION_UP); touchSent = false }
            }
        }
    }

    private fun okUp() {
        okHeld = false
        if (touchSent) { touch(MotionEvent.ACTION_UP); touchSent = false }
    }

    private fun releaseOk() {
        if (touchSent) touch(MotionEvent.ACTION_CANCEL)
        touchSent = false
        okHeld = false
    }

    private fun tapAt(x: Float, y: Float) {
        val hover = motion(MotionEvent.ACTION_HOVER_MOVE, InputDevice.SOURCE_MOUSE, x, y, SystemClock.uptimeMillis())
        web.dispatchGenericMotionEvent(hover)
        hover.recycle()
        ui.postDelayed({
            touch(MotionEvent.ACTION_DOWN, x, y)
            ui.postDelayed({ touch(MotionEvent.ACTION_UP, x, y) }, 50)
        }, 60)
    }

    /* ===================================================================== pointer */

    private val hideCursor = Runnable { cursor.animate().alpha(0f).setDuration(400).start() }

    private fun showCursor() {
        if (navStyle || isFs()) return
        cursor.visibility = View.VISIBLE
        cursor.animate().cancel()
        cursor.alpha = 1f
        ui.removeCallbacks(hideCursor)
        ui.postDelayed(hideCursor, 6000)
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (moveKey == 0) return
            val dt = if (lastFrameNs == 0L) 0f else (frameTimeNanos - lastFrameNs) / 1e9f
            lastFrameNs = frameTimeNanos
            val held = SystemClock.uptimeMillis() - moveStart
            if (held > 170 && dt > 0f) {
                val unit = root.height / 1080f * POINTER_SPEEDS[prefs.getInt("pspeed", 0)]
                val ramp = min((held - 170) / 1100f, 1f)
                val pxPerSec = (450f + 1350f * ramp * ramp) * unit
                step(moveKey, pxPerSec * min(dt, 0.05f), held)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startMove(code: Int) {
        showCursor()
        moveKey = code
        moveStart = SystemClock.uptimeMillis()
        lastFrameNs = 0L
        step(code, 12f * root.height / 1080f, 0) // a small precise step on a short press
        Choreographer.getInstance().removeFrameCallback(frame)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun stopMove() {
        edgeKey = 0
        if (moveKey == 0) return
        moveKey = 0
        Choreographer.getInstance().removeFrameCallback(frame)
        sendHover()
    }

    private fun step(code: Int, dist: Float, held: Long) {
        var dx = 0f
        var dy = 0f
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> dx = -dist
            KeyEvent.KEYCODE_DPAD_RIGHT -> dx = dist
            KeyEvent.KEYCODE_DPAD_UP -> dy = -dist
            KeyEvent.KEYCODE_DPAD_DOWN -> dy = dist
        }
        cx = (cx + dx).coerceIn(0f, root.width - 1f)
        cy = (cy + dy).coerceIn(0f, root.height - 1f)
        cursor.place(cx, cy)
        showCursor()

        // Pushing against an edge scrolls the panel in that column (lecture list, page) — not the header.
        val now = SystemClock.uptimeMillis()
        val edge = 6f
        val atEdge = (dy > 0 && cy >= root.height - edge) || (dy < 0 && cy <= edge) ||
            (dx > 0 && cx >= root.width - edge) || (dx < 0 && cx <= edge)
        if (atEdge) {
            if (edgeKey != code) findEdgeTarget(code)
            if (now - lastWheel > 45) {
                lastWheel = now
                val amt = 1f + min(held / 600f, 2.5f)
                val x = if (edgeX >= 0) edgeX else cx
                val y = if (edgeY >= 0) edgeY else cy
                when {
                    dy > 0 -> wheelAt(x, y, 0f, -amt)
                    dy < 0 -> wheelAt(x, y, 0f, amt)
                    dx > 0 -> wheelAt(x, y, amt, 0f)
                    else -> wheelAt(x, y, -amt, 0f)
                }
            }
        }
        if (touchSent) touch(MotionEvent.ACTION_MOVE)
        else if (now - lastHover > 90) sendHover()
    }

    private fun findEdgeTarget(code: Int) {
        edgeKey = code
        edgeX = -1f
        edgeY = -1f
        val vertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
        val dir = if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
        web.evaluateJavascript("JSON.stringify(window.__tv ? __tv.scrollPoint(${cx / web.width}, ${cy / web.height}, $vertical, $dir) : null)") { r ->
            val p = jsObject(r) ?: return@evaluateJavascript
            if (edgeKey == code) {
                edgeX = p.optDouble("fx").toFloat() * web.width
                edgeY = p.optDouble("fy").toFloat() * web.height
            }
        }
    }

    /** ⏪ / ⏩ on pages: scroll a screen up / down (the panel under the pointer or the highlight). */
    private fun scrollPage(v: Float) {
        val dir = if (v > 0) -1 else 1
        val point = if (navStyle) "__tv.navPoint()" else "{fx:${cx / web.width},fy:${cy / web.height}}"
        web.evaluateJavascript("JSON.stringify(window.__tv ? (function(p){ p = p || {fx:0.5, fy:0.5}; return __tv.scrollPoint(p.fx, p.fy, true, $dir) || p; })($point) : null)") { r ->
            val p = jsObject(r)
            val x = p?.optDouble("fx")?.toFloat()?.times(web.width) ?: (root.width / 2f)
            val y = p?.optDouble("fy")?.toFloat()?.times(web.height) ?: (root.height / 2f)
            wheelAt(x, y, 0f, v * 6f)
        }
    }

    private fun motion(action: Int, source: Int, x: Float, y: Float, downAt: Long, h: Float = 0f, v: Float = 0f): MotionEvent {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = if (source == InputDevice.SOURCE_MOUSE) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = 1f; size = 1f
            setAxisValue(MotionEvent.AXIS_HSCROLL, h)
            setAxisValue(MotionEvent.AXIS_VSCROLL, v)
        }
        return MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, source, 0)
    }

    private fun sendHover() {
        lastHover = SystemClock.uptimeMillis()
        val ev = motion(MotionEvent.ACTION_HOVER_MOVE, InputDevice.SOURCE_MOUSE, cx, cy, lastHover)
        web.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    /** Mouse-wheel scroll. Positive v = up, positive h = right (Android axis convention). */
    private fun wheelAt(x: Float, y: Float, h: Float, v: Float) {
        val ev = motion(MotionEvent.ACTION_SCROLL, InputDevice.SOURCE_MOUSE, x, y, SystemClock.uptimeMillis(), h, v)
        web.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    private fun touch(action: Int, x: Float = cx, y: Float = cy) {
        if (action == MotionEvent.ACTION_DOWN) touchDownTime = SystemClock.uptimeMillis()
        val ev = motion(action, InputDevice.SOURCE_TOUCHSCREEN, x, y, touchDownTime)
        web.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /* ===================================================================== native text input */

    private fun showTextInput(info: JSONObject) {
        val id = info.optInt("id")
        val multi = info.optString("kind") == "multi"
        val type = info.optString("type")
        val max = info.optInt("max")
        val label = info.optString("label").ifBlank { "Type here" }

        val edit = EditText(this).apply {
            setText(info.optString("value"))
            setSelection(text.length)
            textSize = 22f
            isSingleLine = !multi
            if (multi) { minLines = 3; maxLines = 6 }
            inputType = when {
                multi -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                type == "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                type == "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                type == "number" || type == "tel" -> InputType.TYPE_CLASS_NUMBER
                type == "url" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (max > 0) filters = arrayOf(InputFilter.LengthFilter(max))
            imeOptions = (when {
                multi -> EditorInfo.IME_ACTION_DONE
                type == "search" -> EditorInfo.IME_ACTION_SEARCH
                else -> EditorInfo.IME_ACTION_GO
            }) or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(edit)
        }

        fun fill(submit: Boolean) {
            js("window.__tv && __tv.setText($id, ${JSONObject.quote(edit.text.toString())})")
            if (submit) ui.postDelayed({ sendKeyToPage(KeyEvent.KEYCODE_ENTER) }, 250)
            ui.postDelayed({ hideIme() }, 500) // never leave the slow in-page keyboard open
        }

        val builder = dialog().setTitle(label).setView(box).setNegativeButton("Cancel", null)
        if (multi) builder.setPositiveButton("Done") { _, _ -> fill(false) }
        else builder.setPositiveButton("Enter ↵") { _, _ -> fill(true) }.setNeutralButton("Fill only") { _, _ -> fill(false) }
        val dlg = builder.create()
        edit.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                fill(!multi)
                dlg.dismiss()
                true
            } else false
        }
        dlg.window?.apply {
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            setGravity(Gravity.TOP)
        }
        dlg.setOnDismissListener { web.requestFocus() }
        dlg.show()
        edit.requestFocus()
    }

    /** Native input box that runs a Udemy search. */
    private fun showSearch() {
        val edit = EditText(this).apply {
            hint = "e.g. python, machine learning"
            textSize = 22f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(edit) }
        fun go() {
            val q = edit.text.toString().trim()
            if (q.isNotEmpty()) web.loadUrl("https://www.udemy.com/courses/search/?q=" + Uri.encode(q))
        }
        val dlg = dialog().setTitle("Search Udemy").setView(box)
            .setPositiveButton("Search") { _, _ -> go() }.setNegativeButton("Cancel", null).create()
        edit.setOnEditorActionListener { _, _, _ -> go(); dlg.dismiss(); true }
        dlg.window?.apply {
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            setGravity(Gravity.TOP)
        }
        dlg.setOnDismissListener { web.requestFocus() }
        dlg.show()
        edit.requestFocus()
    }

    private fun hideIme() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(web.windowToken, 0)
    }

    /** A real key event (not JS), so the page treats it as a user action. */
    private fun sendKeyToPage(code: Int) {
        web.requestFocus()
        val t = SystemClock.uptimeMillis()
        web.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        web.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    /* ===================================================================== fullscreen */

    private fun enterFullscreen() {
        if (!hasVideo) { osd("No video on this page"); return }
        fsWanted = true
        fsTries = 0
        requestFullscreen()
    }

    /**
     * Real fullscreen of the <video>: browsers only allow it from a user action, so we send a real
     * F12 key that the page script turns into video.requestFullscreen(). If refused, the page
     * stretches the video over the whole screen instead.
     */
    private fun requestFullscreen() {
        lastFsTry = SystemClock.uptimeMillis()
        ui.postDelayed({ // let a closing menu hand focus back to the page first
            sendKeyToPage(KeyEvent.KEYCODE_F12)
            ui.postDelayed({
                if (customView == null && fsWanted) {
                    web.evaluateJavascript("(window.__tv && __tv.isFullscreen()) ? 1 : 0") { r ->
                        if (r != "1" && customView == null) {
                            js("window.__tv && __tv.cssFullscreen(true)")
                            jsFs = true
                            onFsChanged()
                        }
                    }
                }
            }, 1200)
        }, 350)
    }

    private fun exitFullscreen() {
        fsWanted = false
        if (customView != null) {
            js("document.fullscreenElement && document.exitFullscreen()")
            ui.postDelayed({ if (customView != null) { customCallback?.onCustomViewHidden(); hideCustomView() } }, 400)
        } else {
            js("window.__tv && __tv.exitFullscreen()")
            jsFs = false
            onFsChanged()
        }
    }

    private fun hideCustomView() {
        customView?.let { root.removeView(it) }
        customView = null
        customCallback = null
        web.visibility = View.VISIBLE
        web.requestFocus()
        onFsChanged()
    }

    private fun changeLecture(dir: Int) {
        media(if (dir > 0) "next" else "prev")
        fsTries = 0
        lastFsTry = SystemClock.uptimeMillis() // give the new lecture a moment before restoring fullscreen
    }

    /* ===================================================================== back */

    private fun onBack() {
        releaseOk()
        stopMove()
        web.evaluateJavascript("(window.__tv && __tv.back()) ? 1 : 0") { r ->
            if (r == "1") return@evaluateJavascript // closed a popup / dialog
            when {
                web.canGoBack() -> web.goBack()
                SystemClock.uptimeMillis() - lastBack < 2000 -> finish()
                else -> {
                    lastBack = SystemClock.uptimeMillis()
                    osd("Press Back again to exit", 2000)
                }
            }
        }
    }

    /* ===================================================================== menus */

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun menu(title: String, items: List<Pair<String, () -> Unit>>) {
        stopMove()
        releaseOk()
        dialog().setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setOnDismissListener { web.requestFocus() }
            .show()
    }

    private fun styleItem(): Pair<String, () -> Unit> =
        if (navStyle) "Control style: Navigation  →  switch to Pointer" to { setNavStyle(false) }
        else "Control style: Pointer  →  switch to Navigation" to { setNavStyle(true) }

    private fun showMainMenu() {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (hasVideo) {
            items += "▶  Watch fullscreen (remote controls)" to { enterFullscreen() }
            items += "Play / Pause" to { media("playpause") }
            items += "Next lecture" to { media("next") }
            items += "Previous lecture" to { media("prev") }
            items += "Playback speed…" to { showSpeedMenu() }
            items += "Captions" to { media("captions") }
        }
        items += styleItem()
        items += "My Learning" to { web.loadUrl(HOME) }
        items += "Search courses…" to { showSearch() }
        items += "Go back" to { if (web.canGoBack()) web.goBack() }
        items += "Go forward" to { if (web.canGoForward()) web.goForward() }
        items += "Reload page" to { web.reload() }
        items += "Settings…" to { showSettings() }
        items += "Remote controls help" to { showHelp() }
        items += "Exit app" to { finish() }
        menu("Udemy TV", items)
    }

    private fun showVideoMenu() = menu("Video", listOf(
        "Play / Pause" to { media("playpause") },
        "Next lecture" to { changeLecture(1) },
        "Previous lecture" to { changeLecture(-1) },
        "Playback speed…" to { showSpeedMenu() },
        "Captions on / off" to { media("captions") },
        "Forward 30 s" to { media("fwd30") },
        "Back 30 s" to { media("back30") },
        "Restart this lecture" to { media("restart") },
        "Exit fullscreen (back to ${if (navStyle) "navigation" else "pointer"})" to { exitFullscreen() },
        "Remote controls help" to { showHelp() },
        "Exit app" to { finish() }
    ))

    private fun showSpeedMenu() {
        val labels = SPEEDS.map { if (it == 1.0) "1× (normal)" else "$it×" }.toTypedArray()
        dialog().setTitle("Playback speed").setItems(labels) { _, which ->
            web.evaluateJavascript("window.__tv ? __tv.setSpeed(${SPEEDS[which]}) : ''") { osd(jsString(it)) }
        }.setOnDismissListener { web.requestFocus() }.show()
    }

    private fun showSettings() {
        val zoom = prefs.getInt("zoom", 100)
        val width = prefs.getInt("width", 1280)
        val pspeed = prefs.getInt("pspeed", 0)
        val widthLabel = when (width) {
            0 -> "Large (site default)"
            1100 -> "Bigger"
            1280 -> "Fit TV (recommended)"
            else -> "Smaller, more content"
        }
        menu("Settings", listOf(
            styleItem(),
            "Pointer speed: ${POINTER_SPEED_NAMES[pspeed]}  (change)" to {
                val next = (pspeed + 1) % POINTER_SPEEDS.size
                prefs.edit().putInt("pspeed", next).apply()
                osd("Pointer speed: ${POINTER_SPEED_NAMES[next]}")
            },
            "Screen layout: $widthLabel  (change)" to {
                val next = WIDTHS[(WIDTHS.indexOf(width) + 1).mod(WIDTHS.size)]
                prefs.edit().putInt("width", next).apply()
                recreateWebView(web.url ?: HOME)
            },
            "Text size: $zoom%  (change)" to {
                val next = ZOOMS[(ZOOMS.indexOf(zoom) + 1).mod(ZOOMS.size)]
                prefs.edit().putInt("zoom", next).apply()
                web.settings.textZoom = next
                osd("Text size $next%")
            },
            "Faster browsing (block trackers): ${if (blockTrackers) "ON" else "OFF"}" to {
                blockTrackers = !blockTrackers
                prefs.edit().putBoolean("block", blockTrackers).apply()
                web.reload()
                osd("Tracker blocking ${if (blockTrackers) "ON" else "OFF"}")
            },
            "Sign out & clear data" to { confirmSignOut() }
        ))
    }

    private fun showHelp() {
        dialog().setTitle("Remote controls")
            .setMessage(
                "FULLSCREEN VIDEO  (☰ → Watch fullscreen)\n" +
                "  OK or ⏯  —  play / pause\n" +
                "  ◀ / ▶  —  back / forward 5 s  (hold to keep going)\n" +
                "  ▲ / ▼  —  faster / slower\n" +
                "  ⏪ / ⏩  —  previous / next lecture\n" +
                "  Back  —  exit fullscreen        ☰  —  video menu\n\n" +
                "PAGES — Pointer style (default)\n" +
                "  Arrows  —  move the pointer (hold = faster)\n" +
                "  OK  —  click   (on a text box: type with the keyboard)\n" +
                "  Push the pointer against an edge, or ⏪ / ⏩  —  scroll\n\n" +
                "PAGES — Navigation style (☰ → Control style)\n" +
                "  Arrows  —  move the highlight     OK  —  open / click\n" +
                "  ⏪ / ⏩  —  scroll a screen\n\n" +
                "Everywhere:  ⏯ play / pause   Back  —  close popup / previous page   ☰  —  menu"
            )
            .setPositiveButton("Got it", null)
            .setOnDismissListener { web.requestFocus() }
            .show()
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

    /* ===================================================================== WebView callbacks */

    private inner class Chrome : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            // Needed for DRM-protected (Widevine) course videos.
            val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            runOnUiThread { if (allowed.isNotEmpty()) request.grant(allowed.toTypedArray()) else request.deny() }
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
            progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) { callback.onCustomViewHidden(); return }
            customView = view
            customCallback = callback
            js("window.__tv && __tv.cssFullscreen(false)") // real fullscreen won; drop any fallback
            jsFs = false
            fsWanted = true
            root.addView(view, 1, FrameLayout.LayoutParams(MATCH, MATCH))
            web.visibility = View.GONE
            hasVideo = true
            onFsChanged()
        }

        override fun onHideCustomView() = hideCustomView()

        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!blockTrackers) return null
            val host = request.url.host ?: return null
            for (b in BLOCKED) {
                if (host == b || host.endsWith(".$b")) return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(EMPTY))
            }
            return null
        }

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

    /* ===================================================================== lifecycle */

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) { stopMove(); releaseOk() }
    }

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
        stopMove()
        releaseOk()
        js("document.querySelectorAll('video').forEach(function(v){v.pause()})")
        web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        Choreographer.getInstance().removeFrameCallback(frame)
        root.removeAllViews()
        web.destroy()
        super.onDestroy()
    }
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
