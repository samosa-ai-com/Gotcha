package com.gotcha.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot foreground service that captures a single screenshot via MediaProjection.
 * Writes PNG bytes to a temp file and signals completion via [resultReady].
 *
 * One-shot is not a simplification: from API 34 a consent token backs exactly one
 * capture session, so the projection obtained here cannot be reused for a later
 * capture. [ScreenPerception][com.gotcha.tools.ScreenPerception] clears the token as
 * it hands it over, and fresh consent is requested when the next capture needs it.
 */
class MediaProjectionService : Service() {

    companion object {
        private const val CHANNEL_ID = "screenshot_capture"
        private const val NOTIFICATION_ID = 9999
        private const val RESULT_FILE = "media_projection_capture.png"

        /** Give up waiting for a frame slightly before [capture]'s own timeout. */
        private const val FRAME_TIMEOUT_MS = 4000L

        /** Set by caller before starting the service. */
        var resultData: Intent? = null

        /** Latch that callers can await; countDown() fires when PNG is written. */
        var resultReady = CountDownLatch(1)

        /** Path to the captured PNG file after [resultReady] fires. */
        var resultFilePath: String? = null
            private set

        fun capture(context: Context, resultIntent: Intent, timeoutMs: Long = 5000): ByteArray? {
            Log.d("ScreenCapture", "MediaProjectionService.capture() called")
            resultData = resultIntent
            resultReady = CountDownLatch(1)
            resultFilePath = null

            val serviceIntent = Intent(context, MediaProjectionService::class.java)
            try {
                context.startForegroundService(serviceIntent)
                Log.d("ScreenCapture", "MediaProjectionService started")
            } catch (e: Exception) {
                Log.e("ScreenCapture", "Failed to start MediaProjectionService: ${e.message}")
                return null
            }

            val completed = resultReady.await(timeoutMs, TimeUnit.MILLISECONDS)
            Log.d("ScreenCapture", "MediaProjectionService latch completed=$completed, resultFilePath=$resultFilePath")

            val path = resultFilePath ?: return null
            val file = File(path)
            if (!file.exists()) {
                Log.e("ScreenCapture", "Result file does not exist: $path")
                return null
            }
            val bytes = file.readBytes()
            file.delete()
            Log.d("ScreenCapture", "MediaProjectionService returning ${bytes.size} bytes")
            return bytes
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    /** Frame delivery, the projection callback and the timeout all land here. */
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    /** Guards against the frame, the timeout and onStop() all racing to finish. */
    private val finished = AtomicBoolean(false)

    /** API 34 requires a registered callback before createVirtualDisplay(). */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d("ScreenCapture", "MediaProjection.onStop()")
            finish(null)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        captureThread = HandlerThread("MediaProjectionCapture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = resultData
        val handler = captureHandler
        Log.d("ScreenCapture", "MediaProjectionService.onStartCommand: data=${data != null}")
        if (data == null || handler == null) {
            finish(null)
            return START_NOT_STICKY
        }

        handler.post {
            try {
                startCapture(data)
            } catch (e: Exception) {
                Log.e("ScreenCapture", "MediaProjectionService startCapture exception: ${e.message}", e)
                finish(null)
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Sets up the projection and virtual display, then returns — the frame arrives
     * asynchronously on [captureHandler] via the ImageReader listener.
     */
    private fun startCapture(data: Intent) {
        Log.d("ScreenCapture", "startCapture: starting")
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // A spent or stale token throws here rather than returning null on API 34+.
        val mp = try {
            mpManager.getMediaProjection(Activity.RESULT_OK, data)
        } catch (e: Exception) {
            Log.e("ScreenCapture", "startCapture: getMediaProjection failed — consent is stale: ${e.message}")
            null
        }
        projection = mp
        if (mp == null) {
            Log.e("ScreenCapture", "startCapture: no projection — consent data may be stale")
            finish(null)
            return
        }
        mp.registerCallback(projectionCallback, captureHandler)

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val screenDpi = metrics.densityDpi
        Log.d("ScreenCapture", "startCapture: screen ${screenWidth}x$screenHeight dpi=$screenDpi")

        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            // Only the first frame matters; later ones arrive after we've finished.
            val image = try {
                r.acquireLatestImage()
            } catch (e: IllegalStateException) {
                Log.e("ScreenCapture", "acquireLatestImage failed: ${e.message}")
                null
            }
            if (image != null) {
                try {
                    onFrame(image, screenWidth, screenHeight)
                } finally {
                    image.close()
                }
            }
        }, captureHandler)

        virtualDisplay = mp.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null, null
        )
        Log.d("ScreenCapture", "startCapture: virtualDisplay=${virtualDisplay != null}")

        // Never leave the caller (and the service) hanging if no frame is produced.
        captureHandler?.postDelayed({
            if (!finished.get()) {
                Log.e("ScreenCapture", "startCapture: timed out waiting for a frame")
                finish(null)
            }
        }, FRAME_TIMEOUT_MS)
    }

    private fun onFrame(image: Image, screenWidth: Int, screenHeight: Int) {
        if (finished.get()) return
        val bitmap = image.toBitmap(screenWidth, screenHeight)
        if (bitmap == null) {
            Log.e("ScreenCapture", "onFrame: bitmap was null")
            finish(null)
            return
        }
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        val pngBytes = stream.toByteArray()
        Log.d("ScreenCapture", "onFrame: PNG compressed ${pngBytes.size} bytes")
        if (pngBytes.isEmpty()) {
            Log.e("ScreenCapture", "onFrame: PNG compression produced 0 bytes")
            finish(null)
            return
        }
        val outPath = File(cacheDir, RESULT_FILE).absolutePath
        File(outPath).writeBytes(pngBytes)
        Log.d("ScreenCapture", "onFrame: saved to $outPath")
        finish(outPath)
    }

    private fun Image.toBitmap(w: Int, h: Int): Bitmap? = try {
        val plane = planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val srcBytes = ByteArray(buffer.remaining())
        buffer.get(srcBytes)
        // Copy pixels row-by-row into tightly-packed array (skip row padding)
        // No channel swap — copy bytes as-is; the device's native pixel
        // order already matches ARGB_8888 on most devices.
        val packedBytes = ByteArray(w * h * 4)
        for (row in 0 until h) {
            val srcRow = row * rowStride
            val dstRow = row * w * 4
            for (col in 0 until w) {
                val sp = srcRow + col * pixelStride
                val dp = dstRow + col * 4
                packedBytes[dp + 0] = srcBytes[sp + 0]
                packedBytes[dp + 1] = srcBytes[sp + 1]
                packedBytes[dp + 2] = srcBytes[sp + 2]
                packedBytes[dp + 3] = srcBytes[sp + 3]
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(packedBytes))
        Log.d("ScreenCapture", "toBitmap: ${bmp.width}x${bmp.height} (stride=$rowStride, pxStride=$pixelStride)")
        bmp
    } catch (t: Throwable) {
        Log.e("ScreenCapture", "toBitmap failed: ${t.message}", t)
        null
    }

    /**
     * Publishes the result, releases the projection and stops the service. Safe to
     * call from the frame listener, the timeout and [MediaProjection.Callback.onStop]
     * — only the first caller wins.
     */
    private fun finish(filePath: String?) {
        if (!finished.compareAndSet(false, true)) return
        resultFilePath = filePath
        resultReady.countDown()
        releaseCapture()
        stopSelf()
    }

    private fun releaseCapture() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screenshot Capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "One-shot screen capture" }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Gotcha")
            .setContentText("Capturing screenshot…")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    override fun onDestroy() {
        // Covers a system-initiated stop that never went through finish().
        if (finished.compareAndSet(false, true)) {
            resultFilePath = null
            resultReady.countDown()
        }
        releaseCapture()
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        super.onDestroy()
    }
}
