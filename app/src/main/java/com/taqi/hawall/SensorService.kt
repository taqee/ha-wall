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
import android.util.Log
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
        watchNetwork()

        io.execute { runCatching { client.updateRegistration() } }
        main.post(heartbeat)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PUSH_NOW) scheduleSend(0)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(batteryReceiver) }
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
            .setContentText("Reporting sensors to Home Assistant")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        startForeground(NOTIF_ID, n)
    }

    companion object {
        private const val TAG = "SensorService"
        private const val CHANNEL = "hawall"
        private const val NOTIF_ID = 1
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
