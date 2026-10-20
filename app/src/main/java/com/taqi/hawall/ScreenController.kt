package com.taqi.hawall

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/** Device admin with the single "force-lock" policy, used only for lockNow() = real screen off. */
class AdminReceiver : DeviceAdminReceiver()

/** Small shared state between the service, the activity and the sensors. */
object AppState {
    @Volatile var motion = false
    @Volatile var lastMotionAt = 0L
    /** True while the activity shows the black "dimmed" overlay (dim-mode screen off). */
    @Volatile var dimmed = false
    @Volatile var cameraStatus = "off"
    @Volatile var commandChannel = "disconnected"

    /** Called when something changed that HA should see right away. */
    @Volatile var onChange: (() -> Unit)? = null
    fun changed() = onChange?.invoke()
}

/**
 * Screen on/off logic.
 *  - off(): Device Admin lockNow() when enabled and active, otherwise dims the kiosk to black.
 *  - wake(): turns the panel on, brings the kiosk to the front over the lock screen.
 *  - touch(): resets the idle timer (motion, touches, HA commands).
 */
object ScreenController {
    private const val TAG = "Screen"
    private val main = Handler(Looper.getMainLooper())

    @Volatile var lastActivityAt = SystemClock.elapsedRealtime()
        private set

    fun touch() { lastActivityAt = SystemClock.elapsedRealtime() }

    fun adminComponent(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    fun isAdminActive(ctx: Context): Boolean {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(adminComponent(ctx))
    }

    fun isInteractive(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    /** What HA's "Screen On" sensor reports. */
    fun isScreenOn(ctx: Context): Boolean = isInteractive(ctx) && !AppState.dimmed

    fun wake(ctx: Context) {
        touch()
        main.post {
            val app = ctx.applicationContext
            if (!isInteractive(app)) {
                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "hawall:wake"
                )
                wl.acquire(5_000)
            }
            val kiosk = KioskActivity.current
            if (kiosk != null) {
                kiosk.setDimmed(false)
            }
            try {
                app.startActivity(
                    Intent(app, KioskActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not bring kiosk to front: ${e.message}")
            }
            AppState.changed()
        }
    }

    fun off(ctx: Context) {
        main.post {
            val app = ctx.applicationContext
            val prefs = Prefs(app)
            if (prefs.fullScreenOff && isAdminActive(app)) {
                val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                try {
                    dpm.lockNow()
                } catch (e: SecurityException) {
                    Log.w(TAG, "lockNow failed, dimming instead: ${e.message}")
                    KioskActivity.current?.setDimmed(true)
                }
            } else {
                KioskActivity.current?.setDimmed(true)
            }
            AppState.changed()
        }
    }
}
