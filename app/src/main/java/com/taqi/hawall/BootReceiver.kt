package com.taqi.hawall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the sensor service and the dashboard after boot or after an app update.
 * On Android 10+ (Fire OS 8) bringing the activity up from the background needs
 * SYSTEM_ALERT_WINDOW granted via adb, or the app set as the home launcher.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("BootReceiver", "Got ${intent.action}")
        if (!Prefs(context).isConfigured) return
        SensorService.start(context, pushNow = true)
        try {
            context.startActivity(
                Intent(context, KioskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w("BootReceiver", "Could not start activity: ${e.message}")
        }
    }
}
