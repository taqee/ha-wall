package com.taqi.hawall

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Receives notify.mobile_app_<device> messages over HA's websocket push channel
 * (the same mechanism the official app uses when it has no Firebase).
 *
 * The device registers with app_data.push_websocket_channel = true, then this class:
 *   auth → mobile_app/push_notification_channel(webhook_id) → events.
 */
class CommandChannel(
    private val context: Context,
    private val onMessage: (message: String, data: JSONObject?) -> Unit,
) : WebSocketListener() {

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    @Volatile private var stopped = true
    private var backoffMs = 5_000L
    private var nextId = 1

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        ws?.close(1000, null)
        ws = null
        setStatus("disconnected")
    }

    private fun connect() {
        if (stopped) return
        val p = Prefs(context)
        val hook = p.webhookId
        if (!p.isConfigured || p.token.isEmpty() || hook == null) {
            // Not registered yet (the sensor push registers the device); try again later.
            reconnectLater()
            return
        }
        // http→ws, https→wss
        val url = p.haUrl.replaceFirst("http", "ws") + "/api/websocket"
        nextId = 1
        ws = client.newWebSocket(Request.Builder().url(url).build(), this)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val msg = try { JSONObject(text) } catch (_: Exception) { return }
        val p = Prefs(context)
        when (msg.optString("type")) {
            "auth_required" -> webSocket.send(
                JSONObject().put("type", "auth").put("access_token", p.token).toString()
            )
            "auth_ok" -> {
                val id = nextId++
                webSocket.send(
                    JSONObject()
                        .put("id", id)
                        .put("type", "mobile_app/push_notification_channel")
                        .put("webhook_id", p.webhookId)
                        .put("support_confirm", false)
                        .toString()
                )
            }
            "auth_invalid" -> {
                Log.w(TAG, "Token rejected by Home Assistant")
                setStatus("auth invalid")
                backoffMs = 300_000L       // don't hammer HA with a bad token
                webSocket.close(1000, null)
            }
            "result" -> {
                if (msg.optBoolean("success")) {
                    backoffMs = 5_000L
                    setStatus("connected")
                } else {
                    Log.w(TAG, "Subscribe failed: $text")
                    setStatus("subscribe failed")
                }
            }
            "event" -> {
                val ev = msg.optJSONObject("event") ?: return
                val message = ev.optString("message")
                val data = ev.optJSONObject("data")
                main.post { onMessage(message, data) }
            }
        }
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (webSocket === ws) { ws = null; setStatus("disconnected"); reconnectLater() }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Log.w(TAG, "Websocket failure: ${t.message}")
        if (webSocket === ws) { ws = null; setStatus("disconnected"); reconnectLater() }
    }

    private fun reconnectLater() {
        if (stopped) return
        val delay = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(60_000L).coerceAtLeast(backoffMs)
        main.postDelayed({ connect() }, delay)
    }

    private fun setStatus(s: String) {
        if (AppState.commandChannel != s) {
            AppState.commandChannel = s
            AppState.changed()
        }
    }

    companion object { private const val TAG = "Commands" }
}
