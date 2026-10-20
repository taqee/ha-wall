package com.taqi.hawall

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class App : Application() {

    /**
     * One GeckoRuntime per process. Lazy, so Gecko's own child processes
     * (which also instantiate App) never try to create a second runtime.
     */
    val runtime: GeckoRuntime by lazy {
        // Zoom: scale the CSS-px density Gecko lays the page out with. 125% → everything 1.25x bigger.
        val zoom = Prefs(this).zoomPercent / 100f
        val density = resources.displayMetrics.density * zoom
        val settings = GeckoRuntimeSettings.Builder()
            .displayDensityOverride(density)
            .consoleOutput(BuildConfigShim.DEBUG)
            .javaScriptEnabled(true)
            .remoteDebuggingEnabled(BuildConfigShim.DEBUG)
            .build()
        GeckoRuntime.create(this, settings)
    }
}

/**
 * GeckoRuntime settings are fixed once created, so a zoom change restarts the app:
 * schedules the kiosk to open in ~1s, then ends the process.
 */
fun restartApp(context: Context) {
    val app = context.applicationContext
    val pi = PendingIntent.getActivity(
        app, 42,
        Intent(app, KioskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1_000, pi)
    Process.killProcess(Process.myPid())
}

/** Avoids enabling buildConfig generation just for one flag. */
internal object BuildConfigShim {
    const val DEBUG = false
}
