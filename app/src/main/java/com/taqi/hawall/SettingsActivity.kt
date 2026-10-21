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
    private lateinit var zoom: EditText
    private lateinit var keepAliveSel: android.widget.Spinner
    private lateinit var motionOn: CheckBox
    private lateinit var wakeOn: CheckBox
    private lateinit var fullOff: CheckBox
    private lateinit var sens: EditText
    private lateinit var offAfter: EditText
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

        label("Zoom % (100 = normal, 130 = bigger cards, less empty space)")
        zoom = field("100", prefs.zoomPercent.toString(), InputType.TYPE_CLASS_NUMBER)

        label("Wi-Fi keep-alive (stops the radio sleeping → no lag on first touch)")
        keepAliveSel = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Off", "While screen is on (recommended)", "Always")
            )
            setSelection(prefs.keepAliveMode)
        }
        col.addView(keepAliveSel)

        label("Full reload every N hours (0 = off)")
        reload = field("12", prefs.reloadHours.toString(), InputType.TYPE_CLASS_NUMBER)

        label("Sensor heartbeat, minutes")
        heartbeat = field("5", prefs.heartbeatMinutes.toString(), InputType.TYPE_CLASS_NUMBER)

        keepOn = CheckBox(this).apply { text = "Keep screen on"; isChecked = prefs.keepScreenOn }
        col.addView(keepOn)

        col.addView(TextView(this).apply { text = "Motion & screen"; textSize = 18f; setPadding(0, pad * 2, 0, 0) })
        motionOn = CheckBox(this).apply { text = "Camera motion detection"; isChecked = prefs.motionEnabled }
        col.addView(motionOn)
        wakeOn = CheckBox(this).apply { text = "Wake screen on motion"; isChecked = prefs.wakeOnMotion }
        col.addView(wakeOn)

        label("Motion sensitivity 1–100")
        sens = field("60", prefs.motionSensitivity.toString(), InputType.TYPE_CLASS_NUMBER)

        label("Screen off after N seconds without motion/touch (0 = never)")
        offAfter = field("120", prefs.screenOffSeconds.toString(), InputType.TYPE_CLASS_NUMBER)

        fullOff = CheckBox(this).apply {
            text = "Real screen-off (Device Admin) — otherwise dim to black"
            isChecked = prefs.fullScreenOff
        }
        col.addView(fullOff)
        col.addView(Button(this).apply {
            text = if (ScreenController.isAdminActive(this@SettingsActivity)) "Device Admin: active ✓"
                   else "Enable Device Admin (for real screen-off)"
            setOnClickListener { requestAdmin() }
        })
        col.addView(Button(this).apply {
            text = "Test: screen off now (wakes on motion)"
            setOnClickListener {
                save(open = false)
                ScreenController.off(this@SettingsActivity)
            }
        })

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
        prefs.keepAliveMode = keepAliveSel.selectedItemPosition
        val oldZoom = prefs.zoomPercent
        prefs.zoomPercent = zoom.text.toString().toIntOrNull() ?: 100
        if (open && prefs.zoomPercent != oldZoom) {
            restartApp(this)   // new zoom needs a fresh Gecko runtime
            return
        }
        prefs.motionEnabled = motionOn.isChecked
        prefs.wakeOnMotion = wakeOn.isChecked
        prefs.fullScreenOff = fullOff.isChecked
        prefs.motionSensitivity = sens.text.toString().toIntOrNull() ?: 60
        prefs.screenOffSeconds = offAfter.text.toString().toIntOrNull() ?: 120
        if (prefs.motionEnabled && Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 7)

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

    private fun requestAdmin() {
        if (ScreenController.isAdminActive(this)) {
            status.text = "Device Admin already active. To uninstall the app, disable it first in Settings → Security → Device admin apps."
            return
        }
        try {
            startActivity(Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, ScreenController.adminComponent(this))
                .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Lets HA Wall turn the screen off when nobody is around. It can't do anything else."))
        } catch (_: Exception) {
            status.text = "Use adb: adb shell dpm set-active-admin com.taqi.hawall/.AdminReceiver"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            stopService(Intent(this, SensorService::class.java))
            SensorService.start(this, pushNow = true)
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

