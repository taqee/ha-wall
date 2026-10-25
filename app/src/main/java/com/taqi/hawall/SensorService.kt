package com.taqi.hawall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.content.pm.ServiceInfo
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that keeps Wi-Fi awake and reports device sensors to Home Assistant:
 *  - immediately when charging state / charger / battery level changes
 *  - plus a heartbeat every N minutes (so "last updated" never goes stale)
 *  - plus right after the network comes back
 */
class SensorService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val pending = AtomicBoolean(false)
    private lateinit var prefs: Prefs
    private lateinit var client: HaClient

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var last: BatterySnapshot? = null
    @Volatile private var failures = 0
    private var motion: MotionDetector? = null
    private var commands: CommandChannel? = null
    private lateinit var keepAlive: WifiKeepAlive

    /** Starts/stops the Wi-Fi keep-alive according to the setting and screen state. */
    private fun updateKeepAlive(forceOn: Boolean = false) {
        val want = when (prefs.keepAliveMode) {
            2 -> true
            1 -> forceOn || ScreenController.isInteractive(this)
            else -> false
        }
        if (want) keepAlive.start() else keepAlive.stop()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) ScreenController.touch()
            updateKeepAlive()
            scheduleSend(300)
        }
    }

    /** Every 2 s: clears the motion flag and turns the screen off when idle. */
    private val idleLoop = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (AppState.motion && now - AppState.lastMotionAt > MOTION_HOLD_MS) {
                AppState.motion = false
                scheduleSend(0)
            }
            val timeout = prefs.screenOffSeconds
            if (timeout > 0 && ScreenController.isScreenOn(this@SensorService) &&
                now - ScreenController.lastActivityAt > timeout * 1000L
            ) {
                ScreenController.off(this@SensorService)
            }
            main.postDelayed(this, 2_000)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) {
                scheduleSend(500)          // power connected / disconnected
                return
            }
            val now = DeviceSensors.readBattery(c, intent)
            val prev = last
            // ACTION_BATTERY_CHANGED fires a lot (voltage/temperature jitter); only react to what matters.
            if (prev == null || prev.level != now.level || prev.status != now.status ||
                prev.plugged != now.plugged || prev.health != now.health
            ) {
                scheduleSend(1_000)
            }
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            scheduleSend(0)
            main.postDelayed(this, prefs.heartbeatMinutes * 60_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        client = HaClient(this)
        startInForeground()
        acquireLocks()

        registerReceiver(batteryReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        watchNetwork()

        keepAlive = WifiKeepAlive(this)
        updateKeepAlive()
        AppState.onChange = { main.post { scheduleSend(300) } }
        ScreenController.touch()
        if (prefs.motionEnabled) {
            motion = MotionDetector(this, { prefs.motionSensitivity }, ::onMotion).also { it.start() }
        }
        commands = CommandChannel(this, ::onCommand).also { it.start() }

        io.execute { runCatching { client.updateRegistration() } }
        main.post(heartbeat)
        main.postDelayed(idleLoop, 2_000)
    }

    /** Camera thread → main. */
    private fun onMotion() {
        main.post {
            val wasMotion = AppState.motion
            AppState.motion = true
            AppState.lastMotionAt = SystemClock.elapsedRealtime()
            ScreenController.touch()
            // Wake the radio right away so it's awake by the time the user touches the screen.
            updateKeepAlive(forceOn = true)
            if (prefs.wakeOnMotion && !ScreenController.isScreenOn(this)) ScreenController.wake(this)
            if (!wasMotion) scheduleSend(0)
        }
    }

    /**
     * notify.mobile_app_<device> messages:
     *   command_screen_on | command_screen_off | command_reload
     *   command_brightness  (data.level 0-255, or -1 for system)
     *   command_url         (data.url, e.g. /lovelace/cameras)
     * Anything else: wakes the screen and shows the message as a toast.
     */
    private fun onCommand(message: String, data: JSONObject?) {
        Log.i(TAG, "Command: $message")
        when (message) {
            "command_screen_on" -> ScreenController.wake(this)
            "command_screen_off" -> ScreenController.off(this)
            "command_reload" -> KioskActivity.current?.reloadPage()
            "command_brightness" -> {
                val level = data?.let { it.optInt("level", it.optInt("command", -1)) } ?: -1
                prefs.brightness = level
                KioskActivity.current?.applyBrightness()
            }
            "command_zoom" -> {
                // data.level = percent, e.g. 130
                val level = data?.let { it.optInt("level", it.optInt("command", 100)) } ?: 100
                if (level != prefs.zoomPercent) {
                    prefs.zoomPercent = level
                    restartApp(this)
                }
            }
            "command_font" -> {
                // data.level = percent, e.g. 120
                val level = data?.let { it.optInt("level", it.optInt("command", 100)) } ?: 100
                prefs.fontPercent = level
                runCatching { applyFontSize(this) }
            }
            "command_url" -> {
                val url = data?.optString("url").orEmpty()
                if (url.isNotEmpty()) {
                    ScreenController.wake(this)
                    KioskActivity.current?.navigate(url)
                }
            }
            else -> {
                ScreenController.wake(this)
                val title = data?.optString("title").orEmpty()
                val text = if (title.isNotEmpty()) "$title\n$message" else message
                if (text.isNotBlank()) Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PUSH_NOW) scheduleSend(0)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { unregisterReceiver(screenReceiver) }
        motion?.stop(); motion = null
        if (::keepAlive.isInitialized) keepAlive.stop()
        commands?.stop(); commands = null
        AppState.onChange = null
        netCallback?.let { cb ->
            runCatching { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) }
        }
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        io.shutdownNow()
        super.onDestroy()
    }

    /** Coalesces bursts of events into one send. */
    private fun scheduleSend(delayMs: Long) {
        if (!prefs.isConfigured || prefs.token.isEmpty() && prefs.webhookId == null) return
        if (!pending.compareAndSet(false, true)) return
        main.postDelayed({
            pending.set(false)
            io.execute { send() }
        }, delayMs)
    }

    private fun send() {
        try {
            val snap = DeviceSensors.readBattery(this)
            client.pushSensors(DeviceSensors.all(this, snap))
            last = snap
            failures = 0
        } catch (e: Exception) {
            failures++
            Log.w(TAG, "Sensor push failed ($failures): ${e.message}")
            // Short retry so a brief outage doesn't wait for the next heartbeat.
            if (failures <= 5) main.post { scheduleSend(30_000L * failures) }
        }
    }

    private fun watchNetwork() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (::keepAlive.isInitialized) keepAlive.reset()
                main.post { scheduleSend(3_000) }
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

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hawall:wifi").apply {
            setReferenceCounted(false); acquire()
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hawall:sensors").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "HA Wall", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, KioskActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
        val n = builder
            .setContentTitle("HA Wall")
            .setContentText("Sensors, motion and screen control")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (prefs.motionEnabled &&
                checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            startForeground(NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val TAG = "SensorService"
        private const val CHANNEL = "hawall"
        private const val NOTIF_ID = 1
        private const val MOTION_HOLD_MS = 30_000L
        const val ACTION_PUSH_NOW = "com.taqi.hawall.PUSH_NOW"

        fun start(context: Context, pushNow: Boolean = false) {
            val i = Intent(context, SensorService::class.java)
            if (pushNow) i.action = ACTION_PUSH_NOW
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start service: ${e.message}")
            }
        }
    }
}
