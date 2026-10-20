package com.taqi.hawall

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import kotlin.math.abs

/**
 * Low-res front-camera motion detector (Camera2, no preview, no deps).
 *
 * Each frame's luma plane is reduced to a 32x24 grid of block averages and compared with the
 * previous grid. If enough blocks changed by more than a threshold, that frame counts as motion.
 * Two consecutive motion frames are required, which filters out sensor noise and single glitches.
 * Frames are processed at ~4 fps and the camera is asked for its lowest frame rate to save power.
 */
class MotionDetector(
    private val context: Context,
    private val sensitivity: () -> Int,
    private val onMotion: () -> Unit,
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    private var prev: IntArray? = null
    private var lastProcessed = 0L
    private var startedAt = 0L
    private var hits = 0

    @Volatile var running = false
        private set

    fun start() {
        if (running) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status("no camera permission")
            return
        }
        running = true
        thread = HandlerThread("motion").also { it.start() }
        handler = Handler(thread!!.looper)
        handler!!.post { open() }
    }

    fun stop() {
        running = false
        val h = handler
        if (h != null) h.post { closeCamera() } else closeCamera()
        thread?.quitSafely()
        thread = null
        handler = null
        status("off")
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            } ?: cm.cameraIdList.firstOrNull()
            if (id == null) { status("no camera"); running = false; return }

            val chars = cm.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
            if (sizes.isNullOrEmpty()) { status("no YUV output"); running = false; return }
            // Smallest size that's still >= 160 px wide; motion needs very little resolution.
            val size = sizes.filter { it.width >= 160 }.minByOrNull { it.width * it.height }
                ?: sizes.minByOrNull { it.width * it.height }!!

            val fpsRange = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.minByOrNull { it.upper }

            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            r.setOnImageAvailableListener({ ir ->
                val img = try { ir.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                try { process(img) } catch (e: Exception) { Log.w(TAG, "process: ${e.message}") } finally { img.close() }
            }, handler)
            reader = r

            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (!running) { d.close(); return }
                    device = d
                    createSession(d, r, fpsRange)
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close(); device = null
                    status("disconnected")
                    restartLater()
                }

                override fun onError(d: CameraDevice, error: Int) {
                    d.close(); device = null
                    status("error $error")
                    restartLater()
                }
            }, handler)
        } catch (e: Exception) {
            status("open failed: ${e.message}")
            restartLater()
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(d: CameraDevice, r: ImageReader, fps: Range<Int>?) {
        try {
            d.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (!running) { s.close(); return }
                    session = s
                    val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(r.surface)
                        fps?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    }.build()
                    try {
                        s.setRepeatingRequest(req, null, handler)
                        prev = null; hits = 0
                        startedAt = SystemClock.elapsedRealtime()
                        status("running")
                    } catch (e: Exception) {
                        status("request failed: ${e.message}")
                        restartLater()
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    status("configure failed")
                    restartLater()
                }
            }, handler)
        } catch (e: Exception) {
            status("session failed: ${e.message}")
            restartLater()
        }
    }

    private fun process(img: Image) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProcessed < 250) return
        lastProcessed = now

        val plane = img.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val w = img.width
        val h = img.height

        val gw = 32
        val gh = 24
        val bw = w / gw
        val bh = h / gh
        if (bw < 1 || bh < 1) return
        val step = maxOf(1, minOf(bw, bh) / 4)
        val grid = IntArray(gw * gh)

        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                var sum = 0
                var n = 0
                var y = gy * bh
                val yEnd = y + bh
                while (y < yEnd) {
                    var x = gx * bw
                    val xEnd = x + bw
                    val row = y * rowStride
                    while (x < xEnd) {
                        sum += buf.get(row + x * pixStride).toInt() and 0xFF
                        n++
                        x += step
                    }
                    y += step
                }
                grid[gy * gw + gx] = if (n > 0) sum / n else 0
            }
        }

        val p = prev
        prev = grid
        // Let auto-exposure settle after (re)start.
        if (p == null || now - startedAt < 2_000) return

        val sens = sensitivity().coerceIn(1, 100)
        val pixelThreshold = 40 - (sens * 0.3).toInt()          // 40 .. 10 levels
        val minFraction = 0.20 - sens * 0.0018                   // 20% .. 2% of blocks
        var changed = 0
        for (i in grid.indices) if (abs(grid[i] - p[i]) > pixelThreshold) changed++

        if (changed >= grid.size * minFraction) {
            hits++
            if (hits >= 2) { hits = 0; onMotion() }
        } else {
            hits = 0
        }
    }

    private fun restartLater() {
        closeCamera()
        if (!running) return
        handler?.postDelayed({ if (running) open() }, 10_000)
    }

    private fun closeCamera() {
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { reader?.close() }; reader = null
    }

    private fun status(s: String) {
        Log.i(TAG, "camera: $s")
        if (AppState.cameraStatus != s) {
            AppState.cameraStatus = s
            AppState.changed()
        }
    }

    companion object { private const val TAG = "Motion" }
}
