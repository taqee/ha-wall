package com.taqi.hawall

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for Home Assistant's mobile_app integration:
 *  - registers this tablet as a device (needs the long-lived token, once)
 *  - registers sensors and pushes their states through the device webhook (no token needed)
 *
 * All calls are blocking; run them off the main thread.
 */
class HaClient(private val context: Context) {

    private val prefs = Prefs(context)

    class HaException(msg: String) : IOException(msg)

    /** Registers the device if there is no webhook yet. Returns the webhook id. */
    @Throws(IOException::class)
    fun ensureRegistered(): String {
        prefs.webhookId?.let { return it }
        if (prefs.token.isEmpty()) throw HaException("No access token set")

        val body = JSONObject().apply {
            put("device_id", prefs.deviceId)
            put("app_id", "com.taqi.hawall")
            put("app_name", "HA Wall")
            put("app_version", appVersion())
            put("device_name", prefs.deviceName)
            put("manufacturer", Prefs.manufacturer)
            put("model", Prefs.model)
            put("os_name", "Android")
            put("os_version", Build.VERSION.RELEASE ?: "")
            put("supports_encryption", false)
            put("app_data", JSONObject())
        }
        val (code, resp) = post("${prefs.haUrl}/api/mobile_app/registrations", body.toString(), prefs.token)
        if (code != 200 && code != 201) throw HaException("Registration failed: HTTP $code $resp")
        val webhook = JSONObject(resp).optString("webhook_id")
        if (webhook.isNullOrEmpty()) throw HaException("No webhook_id in response")
        prefs.webhookId = webhook
        prefs.sensorsRegistered = false
        Log.i(TAG, "Registered device, webhook=$webhook")
        return webhook
    }

    /** Pushes the current state of every sensor, registering them first when needed. */
    @Throws(IOException::class)
    fun pushSensors(sensors: List<Sensor>) {
        ensureRegistered()
        if (!prefs.sensorsRegistered) {
            for (s in sensors) {
                webhook("register_sensor", s.registerJson())
            }
            prefs.sensorsRegistered = true
            // Registration already carries the state, nothing else to send.
            return
        }

        val data = JSONArray()
        sensors.forEach { data.put(it.updateJson()) }
        val resp = webhook("update_sensor_states", data)

        // HA answers per sensor; "not_registered" means the entity was deleted on the HA side.
        val missing = try {
            val obj = JSONObject(resp)
            sensors.filter { s ->
                val r = obj.optJSONObject(s.uniqueId)
                r != null && !r.optBoolean("success", true) &&
                    r.optJSONObject("error")?.optString("code") == "not_registered"
            }
        } catch (_: Exception) { emptyList() }

        for (s in missing) webhook("register_sensor", s.registerJson())
    }

    /** Keeps device name / app version in HA in sync. */
    @Throws(IOException::class)
    fun updateRegistration() {
        val data = JSONObject().apply {
            put("app_version", appVersion())
            put("device_name", prefs.deviceName)
            put("manufacturer", Prefs.manufacturer)
            put("model", Prefs.model)
            put("os_version", Build.VERSION.RELEASE ?: "")
            put("app_data", JSONObject())
        }
        webhook("update_registration", data)
    }

    @Throws(IOException::class)
    private fun webhook(type: String, data: Any): String {
        val id = ensureRegistered()
        val payload = JSONObject().put("type", type).put("data", data).toString()
        val (code, resp) = post("${prefs.haUrl}/api/webhook/$id", payload, null)
        when (code) {
            200, 201 -> return resp
            410 -> {
                // Device was deleted from HA: drop it and register again next round.
                Log.w(TAG, "Webhook gone (410), clearing registration")
                prefs.clearRegistration()
                throw HaException("Device removed in Home Assistant, will re-register")
            }
            else -> throw HaException("Webhook $type failed: HTTP $code $resp")
        }
    }

    private fun post(url: String, body: String, bearer: String?): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    } catch (_: Exception) { "1.0" }

    companion object { private const val TAG = "HaClient" }
}
