package com.taqi.hawall

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import org.json.JSONObject

/** One sensor as Home Assistant's mobile_app integration expects it. */
data class Sensor(
    val uniqueId: String,
    val name: String,
    val type: String,               // "sensor" or "binary_sensor"
    val state: Any,
    val icon: String,
    val deviceClass: String? = null,
    val unit: String? = null,
    val stateClass: String? = null,
    val entityCategory: String? = null,
    val attributes: JSONObject? = null,
) {
    fun registerJson(): JSONObject = JSONObject().apply {
        put("unique_id", uniqueId)
        put("name", name)
        put("type", type)
        put("state", state)
        put("icon", icon)
        deviceClass?.let { put("device_class", it) }
        unit?.let { put("unit_of_measurement", it) }
        stateClass?.let { put("state_class", it) }
        entityCategory?.let { put("entity_category", it) }
        attributes?.let { put("attributes", it) }
    }

    fun updateJson(): JSONObject = JSONObject().apply {
        put("unique_id", uniqueId)
        put("type", type)
        put("state", state)
        put("icon", icon)
        attributes?.let { put("attributes", it) }
    }
}

/** Snapshot of the battery, used both for change detection and for building sensors. */
data class BatterySnapshot(
    val level: Int,
    val status: Int,
    val plugged: Int,
    val health: Int,
    val tempC: Double,
    val voltageMv: Int,
)

object DeviceSensors {

    fun readBattery(context: Context, intent: Intent? = null): BatterySnapshot {
        val i = intent ?: context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) Math.round(level * 100f / scale) else -1
        return BatterySnapshot(
            level = pct,
            status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1,
            plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0,
            health = i?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1,
            tempC = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0,
            voltageMv = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0,
        )
    }

    fun all(context: Context, b: BatterySnapshot = readBattery(context)): List<Sensor> {
        val charging = b.status == BatteryManager.BATTERY_STATUS_CHARGING ||
            b.status == BatteryManager.BATTERY_STATUS_FULL
        val list = mutableListOf(
            Sensor(
                uniqueId = "battery_level", name = "Battery Level", type = "sensor",
                state = b.level, icon = batteryIcon(b.level, charging),
                deviceClass = "battery", unit = "%", stateClass = "measurement",
                entityCategory = "diagnostic",
                attributes = JSONObject().put("voltage_mv", b.voltageMv),
            ),
            Sensor(
                uniqueId = "battery_state", name = "Battery State", type = "sensor",
                state = statusText(b.status), icon = if (charging) "mdi:battery-charging" else "mdi:battery",
                entityCategory = "diagnostic",
            ),
            Sensor(
                uniqueId = "is_charging", name = "Is Charging", type = "binary_sensor",
                state = charging, icon = if (charging) "mdi:power-plug" else "mdi:power-plug-off",
                deviceClass = "battery_charging", entityCategory = "diagnostic",
            ),
            Sensor(
                uniqueId = "charger_type", name = "Charger Type", type = "sensor",
                state = pluggedText(b.plugged), icon = "mdi:power-plug",
                entityCategory = "diagnostic",
            ),
            Sensor(
                uniqueId = "battery_temperature", name = "Battery Temperature", type = "sensor",
                state = b.tempC, icon = "mdi:thermometer",
                deviceClass = "temperature", unit = "°C", stateClass = "measurement",
                entityCategory = "diagnostic",
            ),
            Sensor(
                uniqueId = "battery_health", name = "Battery Health", type = "sensor",
                state = healthText(b.health), icon = "mdi:battery-heart-variant",
                entityCategory = "diagnostic",
            ),
        )
        wifiRssi(context)?.let { rssi ->
            list += Sensor(
                uniqueId = "wifi_signal_strength", name = "WiFi Signal Strength", type = "sensor",
                state = rssi, icon = "mdi:wifi",
                deviceClass = "signal_strength", unit = "dBm", stateClass = "measurement",
                entityCategory = "diagnostic",
            )
        }
        return list
    }

    @Suppress("DEPRECATION")
    private fun wifiRssi(context: Context): Int? = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val rssi = wm.connectionInfo?.rssi
        if (rssi == null || rssi <= -127 || rssi >= 0) null else rssi
    } catch (_: Exception) { null }

    private fun statusText(s: Int) = when (s) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        else -> "unknown"
    }

    private fun pluggedText(p: Int) = when (p) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        0 -> "none"
        else -> "other"   // dock (API 33) and vendor values
    }

    private fun healthText(h: Int) = when (h) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        else -> "unknown"
    }

    private fun batteryIcon(level: Int, charging: Boolean): String {
        if (level < 0) return "mdi:battery-unknown"
        val step = (level / 10) * 10
        val base = if (charging) "mdi:battery-charging" else "mdi:battery"
        return when {
            step >= 100 -> if (charging) "mdi:battery-charging-100" else "mdi:battery"
            step < 10 -> if (charging) "mdi:battery-charging-outline" else "mdi:battery-outline"
            else -> "$base-$step"
        }
    }
}
