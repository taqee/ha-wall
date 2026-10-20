package com.taqi.hawall

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError

/**
 * Full-screen Home Assistant dashboard on GeckoView (independent of the system WebView).
 * Retries with backoff on load errors, rebuilds the session if Gecko's content process dies,
 * reloads when the network comes back, and does an optional periodic full reload.
 *
 * Hidden settings: tap the bottom-right corner 5 times within 3 seconds.
 */
class KioskActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var geckoView: GeckoView
    private lateinit var status: TextView
    private lateinit var dimOverlay: View
    private var session: GeckoSession? = null

    private var canGoBack = false
    private var inError = false
    private var retryDelayMs = INITIAL_RETRY_MS
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val retry = Runnable { load(prefs.startUrl) }
    private val periodicReload = object : Runnable {
        override fun run() {
            Log.i(TAG, "Periodic reload")
            session?.reload()
            schedulePeriodicReload()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        if (!prefs.isConfigured) {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }

        current = this
        showOverLockScreen()
        buildLayout()
        applyWindowFlags()
        askCameraPermissionIfNeeded()
        createSession()
        load(prefs.startUrl)
        watchNetwork()
        schedulePeriodicReload()
        SensorService.start(this, pushNow = true)
    }

    override fun onResume() {
        super.onResume()
        if (!::prefs.isInitialized || !prefs.isConfigured) return
        applyWindowFlags()
        session?.setActive(true)
    }

    override fun onPause() {
        // Keep the session active: HA keeps its websocket and stays live behind a screen-off.
        super.onPause()
    }

    override fun onDestroy() {
        if (current === this) current = null
        main.removeCallbacksAndMessages(null)
        netCallback?.let { cb ->
            runCatching { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) }
        }
        if (::geckoView.isInitialized) geckoView.releaseSession()
        session?.close()
        session = null
        super.onDestroy()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        ScreenController.touch()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            // Restart the service so it starts the camera with the right foreground type.
            stopService(Intent(this, SensorService::class.java))
            SensorService.start(this, pushNow = true)
        }
    }

    // ---------------------------------------------------------------- screen control (called by service)

    fun setDimmed(dim: Boolean) {
        if (!::dimOverlay.isInitialized) return
        AppState.dimmed = dim
        dimOverlay.visibility = if (dim) View.VISIBLE else View.GONE
        applyBrightness()
        if (!dim) ScreenController.touch()
    }

    fun applyBrightness() {
        val lp = window.attributes
        lp.screenBrightness = when {
            AppState.dimmed -> 0f
            prefs.brightness < 0 -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else -> (prefs.brightness / 255f).coerceIn(0.01f, 1f)
        }
        window.attributes = lp
    }

    fun reloadPage() { session?.reload() }

    fun navigate(pathOrUrl: String) {
        val url = when {
            pathOrUrl.startsWith("http") -> pathOrUrl
            pathOrUrl.startsWith("/") -> prefs.haUrl + pathOrUrl
            else -> prefs.haUrl + "/" + pathOrUrl
        }
        load(url)
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }

    private fun askCameraPermissionIfNeeded() {
        if (prefs.motionEnabled && Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Never leave the kiosk with Back; just navigate back inside HA.
        if (canGoBack) session?.goBack()
    }

    // ---------------------------------------------------------------- layout

    private fun buildLayout() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        geckoView = GeckoView(this)
        root.addView(geckoView, FrameLayout.LayoutParams(MATCH, MATCH))

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(235, 17, 17, 17))
            visibility = View.GONE
            text = "Reconnecting to Home Assistant…"
            setOnClickListener { retryNow() }
        }
        root.addView(status, FrameLayout.LayoutParams(MATCH, MATCH))

        // Black overlay for dim-mode "screen off"; a tap wakes it.
        dimOverlay = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
            setOnClickListener { setDimmed(false); AppState.changed() }
        }
        root.addView(dimOverlay, FrameLayout.LayoutParams(MATCH, MATCH))

        // Invisible hot-corner for the settings screen.
        val corner = View(this)
        val size = dp(56)
        val taps = LongArray(5)
        var idx = 0
        corner.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) {
                val now = SystemClock.uptimeMillis()
                taps[idx % taps.size] = now
                idx++
                val oldest = taps.minOrNull() ?: 0L
                if (idx >= taps.size && now - oldest < 3_000) {
                    idx = 0; taps.fill(0)
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
            true
        }
        root.addView(corner, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END))

        setContentView(root)
    }

    private fun applyWindowFlags() {
        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        applyBrightness()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    // ---------------------------------------------------------------- gecko

    private fun createSession() {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)          // keep HA login / tokens across restarts
            .useTrackingProtection(false)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build()

        val s = GeckoSession(settings)

        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@KioskActivity.canGoBack = canGoBack
            }

            override fun onLoadError(
                session: GeckoSession, uri: String?, error: WebRequestError
            ): GeckoResult<String>? {
                Log.w(TAG, "Load error ${error.code} for $uri")
                showError()
                return null
            }
        }

        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (success && !inError) {
                    status.visibility = View.GONE
                    retryDelayMs = INITIAL_RETRY_MS
                }
            }
        }

        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCrash(session: GeckoSession) {
                Log.e(TAG, "Content process crashed, rebuilding session")
                rebuildSession()
            }

            override fun onKill(session: GeckoSession) {
                Log.e(TAG, "Content process killed (memory?), rebuilding session")
                rebuildSession()
            }
        }

        s.open((application as App).runtime)
        geckoView.setSession(s)
        session = s
    }

    private fun rebuildSession() {
        main.post {
            geckoView.releaseSession()
            session?.close()
            session = null
            createSession()
            load(prefs.startUrl)
        }
    }

    private fun load(url: String) {
        inError = false
        main.removeCallbacks(retry)
        session?.loadUri(url)
    }

    private fun showError() {
        inError = true
        status.text = "Reconnecting to Home Assistant…\n(retry in ${retryDelayMs / 1000}s — tap to retry now)"
        status.visibility = View.VISIBLE
        main.removeCallbacks(retry)
        main.postDelayed(retry, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
    }

    private fun retryNow() {
        retryDelayMs = INITIAL_RETRY_MS
        load(prefs.startUrl)
    }

    private fun schedulePeriodicReload() {
        main.removeCallbacks(periodicReload)
        val h = prefs.reloadHours
        if (h > 0) main.postDelayed(periodicReload, h * 3_600_000L)
    }

    private fun watchNetwork() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.postDelayed({ if (inError) retryNow() }, 2_000)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(cb)
            else cm.registerNetworkCallback(android.net.NetworkRequest.Builder().build(), cb)
            netCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "Network callback failed: ${e.message}")
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "Kiosk"
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val INITIAL_RETRY_MS = 5_000L
        private const val MAX_RETRY_MS = 60_000L
        private const val REQ_CAMERA = 7

        /** The running kiosk, so the service can dim / reload / navigate it. */
        @Volatile var current: KioskActivity? = null
            private set
    }
}
