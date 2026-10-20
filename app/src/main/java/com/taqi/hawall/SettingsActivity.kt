package com.taqi.hawall

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** Plain native form, no AndroidX. Reached on first run or via the hidden hot-corner. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var url: EditText
    private lateinit var path: EditText
    private lateinit var token: EditText
    private lateinit var name: EditText
    private lateinit var reload: EditText
    private lateinit var heartbeat: EditText
    private lateinit var keepOn: CheckBox
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun label(t: String) = col.addView(TextView(this).apply { text = t; setPadding(0, pad, 0, 0) })
        fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText =
            EditText(this).apply {
                this.hint = hint; setText(value); inputType = type; isSingleLine = true
            }.also { col.addView(it) }

        col.addView(TextView(this).apply { text = "HA Wall — settings"; textSize = 22f })

        label("Home Assistant URL (local is best)")
        url = field("http://192.168.1.10:8123", prefs.haUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)

        label("Dashboard path (optional)")
        path = field("/dashboard-wall/0", prefs.dashboardPath)

        label("Long-lived access token (for sensors)")
        token = field("Profile → Security → Long-lived tokens", prefs.token,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

        label("Device name in Home Assistant")
        name = field("Wall Tablet", prefs.deviceName)

        label("Full reload every N hours (0 = off)")
        reload = field("12", prefs.reloadHours.toString(), InputType.TYPE_CLASS_NUMBER)

        label("Sensor heartbeat, minutes")
        heartbeat = field("5", prefs.heartbeatMinutes.toString(), InputType.TYPE_CLASS_NUMBER)

        keepOn = CheckBox(this).apply { text = "Keep screen on"; isChecked = prefs.keepScreenOn }
        col.addView(keepOn)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
        row.addView(Button(this).apply { text = "Save & open"; setOnClickListener { save(open = true) } })
        row.addView(Button(this).apply { text = "Test sensors"; setOnClickListener { save(open = false); test() } })
        col.addView(row)

        col.addView(Button(this).apply {
            text = "Disable battery optimisation"
            setOnClickListener { askIgnoreBatteryOptimisation() }
        })
        col.addView(Button(this).apply {
            text = "Re-register device in HA"
            setOnClickListener { prefs.clearRegistration(); test() }
        })
        col.addView(Button(this).apply {
            text = "Android settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        })

        status = TextView(this).apply { setPadding(0, pad, 0, 0); textSize = 15f }
        col.addView(status)
        col.addView(TextView(this).apply {
            setPadding(0, pad, 0, 0)
            text = "Device id: ${prefs.deviceId}\nWebhook: ${prefs.webhookId ?: "not registered"}"
            textSize = 12f
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun save(open: Boolean) {
        val newUrl = url.text.toString().trim().trimEnd('/')
        if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
            status.text = "URL must start with http:// or https://"
            return
        }
        // A different server or token means a different HA instance/user: start clean.
        if (newUrl != prefs.haUrl || token.text.toString().trim() != prefs.token) prefs.clearRegistration()

        prefs.haUrl = newUrl
        prefs.dashboardPath = path.text.toString()
        prefs.token = token.text.toString()
        prefs.deviceName = name.text.toString().ifBlank { "Wall Tablet" }
        prefs.reloadHours = reload.text.toString().toIntOrNull() ?: 12
        prefs.heartbeatMinutes = heartbeat.text.toString().toIntOrNull() ?: 5
        prefs.keepScreenOn = keepOn.isChecked

        // Restart the service so new interval / URL take effect.
        stopService(Intent(this, SensorService::class.java))
        SensorService.start(this, pushNow = true)

        if (open) {
            startActivity(Intent(this, KioskActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        }
    }

    private fun test() {
        status.text = "Contacting Home Assistant…"
        io.execute {
            val msg = try {
                val client = HaClient(this)
                client.ensureRegistered()
                client.pushSensors(DeviceSensors.all(this))
                client.pushSensors(DeviceSensors.all(this)) // 2nd call exercises update_sensor_states
                "OK — device \"${prefs.deviceName}\" is in Settings → Devices → Mobile App."
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
            main.post { status.text = msg }
        }
    }

    private fun askIgnoreBatteryOptimisation() {
        if (Build.VERSION.SDK_INT < 23) { status.text = "Not needed on this Android version"; return }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            status.text = "Already exempt from battery optimisation"; return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            status.text = "Fire OS blocked the dialog — use the adb command from the README"
        }
    }
}

