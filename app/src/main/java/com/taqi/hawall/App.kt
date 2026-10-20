package com.taqi.hawall

import android.app.Application
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class App : Application() {

    /**
     * One GeckoRuntime per process. Lazy, so Gecko's own child processes
     * (which also instantiate App) never try to create a second runtime.
     */
    val runtime: GeckoRuntime by lazy {
        val settings = GeckoRuntimeSettings.Builder()
            .consoleOutput(BuildConfigShim.DEBUG)
            .javaScriptEnabled(true)
            .remoteDebuggingEnabled(BuildConfigShim.DEBUG)
            .build()
        GeckoRuntime.create(this, settings)
    }
}

/** Avoids enabling buildConfig generation just for one flag. */
internal object BuildConfigShim {
    const val DEBUG = false
}
