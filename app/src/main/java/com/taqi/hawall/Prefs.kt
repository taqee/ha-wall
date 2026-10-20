package com.taqi.hawall

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

/** All persisted settings. Plain SharedPreferences, no extra deps. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("hawall", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    /** Base URL of Home Assistant, e.g. http://192.168.1.10:8123 (no trailing slash). */
    var haUrl: String
        get() = sp.getString("ha_url", "")!!.trim().trimEnd('/')
        set(v) = sp.edit().putString("ha_url", v.trim().trimEnd('/')).apply()

    /** Optional path to open, e.g. /dashboard-wall/0?kiosk */
    var dashboardPath: String
        get() = sp.getString("dash_path", "")!!.trim()
        set(v) = sp.edit().putString("dash_path", v.trim()).apply()

    /** Long-lived access token, used only for the one-time mobile_app registration. */
    var token: String
        get() = sp.getString("token", "")!!.trim()
        set(v) = sp.edit().putString("token", v.trim()).apply()

    var deviceName: String
        get() = sp.getString("device_name", null) ?: "Wall Tablet"
        set(v) = sp.edit().putString("device_name", v.trim()).apply()

    var webhookId: String?
        get() = sp.getString("webhook_id", null)
        set(v) = sp.edit().putString("webhook_id", v).apply()

    var sensorsRegistered: Boolean
        get() = sp.getBoolean("sensors_registered", false)
        set(v) = sp.edit().putBoolean("sensors_registered", v).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_screen_on", true)
        set(v) = sp.edit().putBoolean("keep_screen_on", v).apply()

    /** Full page reload every N hours to clear frontend memory growth. 0 = off. */
    var reloadHours: Int
        get() = sp.getInt("reload_hours", 12)
        set(v) = sp.edit().putInt("reload_hours", v.coerceIn(0, 168)).apply()

    /** Heartbeat interval for sensor updates, in minutes. */
    var heartbeatMinutes: Int
        get() = sp.getInt("heartbeat_min", 5)
        set(v) = sp.edit().putInt("heartbeat_min", v.coerceIn(1, 60)).apply()

    val isConfigured: Boolean get() = haUrl.startsWith("http")

    val startUrl: String
        get() {
            val p = dashboardPath
            return when {
                p.isEmpty() -> haUrl
                p.startsWith("/") -> haUrl + p
                else -> "$haUrl/$p"
            }
        }

    /** Stable per-install id; survives reinstalls on the same device via ANDROID_ID when available. */
    val deviceId: String
        @SuppressLint("HardwareIds")
        get() {
            sp.getString("device_id", null)?.let { return it }
            val aid = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            val id = if (!aid.isNullOrBlank() && aid != "9774d56d682e549c") "hawall-$aid"
            else "hawall-" + UUID.randomUUID().toString()
            sp.edit().putString("device_id", id).apply()
            return id
        }

    fun clearRegistration() {
        sp.edit().remove("webhook_id").putBoolean("sensors_registered", false).apply()
    }

    companion object {
        val model: String get() = Build.MODEL ?: "Fire"
        val manufacturer: String get() = Build.MANUFACTURER ?: "Amazon"
    }
}
