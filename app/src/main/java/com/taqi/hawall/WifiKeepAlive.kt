package com.taqi.hawall

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL

/**
 * Keeps the Wi-Fi radio out of power-save by sending a tiny UDP datagram to the gateway
 * every [intervalMs]. Fire OS (MediaTek) ignores WifiLock HIGH_PERF, so without traffic the
 * radio dozes between beacons and replies take 100–900 ms. A few bytes every 150 ms
 * (~0.5 KB/s) keeps it in active mode. Port 9 = discard; the router just ignores it.
 */
class WifiKeepAlive(private val context: Context) {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var socket: DatagramSocket? = null
    private var target: InetAddress? = null
    @Volatile private var intervalMs = 150L
    @Volatile var running = false
        private set

    private val payload = byteArrayOf(0)

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val t = target ?: resolveTarget().also { target = it }
                if (t != null) {
                    val s = socket ?: DatagramSocket().also { socket = it }
                    s.send(DatagramPacket(payload, payload.size, t, 9))
                }
            } catch (e: Exception) {
                // Network changed or not ready: re-resolve next time.
                target = null
                runCatching { socket?.close() }; socket = null
            }
            handler?.postDelayed(this, intervalMs)
        }
    }

    fun start(interval: Long = 150L) {
        intervalMs = interval
        if (running) return
        running = true
        thread = HandlerThread("wifi-keepalive").also { it.start() }
        handler = Handler(thread!!.looper).also { it.post(tick) }
        Log.i(TAG, "keep-alive on (${intervalMs}ms)")
    }

    fun stop() {
        if (!running) return
        running = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null; handler = null
        runCatching { socket?.close() }; socket = null
        target = null
        Log.i(TAG, "keep-alive off")
    }

    /** Call on network change. */
    fun reset() { target = null }

    @Suppress("DEPRECATION")
    private fun resolveTarget(): InetAddress? {
        // 1) Wi-Fi gateway from DHCP
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val gw = wm.dhcpInfo?.gateway ?: 0
            if (gw != 0) {
                val b = byteArrayOf(
                    (gw and 0xFF).toByte(), (gw shr 8 and 0xFF).toByte(),
                    (gw shr 16 and 0xFF).toByte(), (gw shr 24 and 0xFF).toByte()
                )
                return InetAddress.getByAddress(b)
            }
        } catch (_: Exception) { }
        // 2) Home Assistant host
        return try {
            InetAddress.getByName(URL(Prefs(context).haUrl).host)
        } catch (_: Exception) { null }
    }

    companion object { private const val TAG = "KeepAlive" }
}
