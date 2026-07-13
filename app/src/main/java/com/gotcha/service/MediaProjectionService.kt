package com.gotcha.service

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
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One-shot foreground service that captures a single screenshot via MediaProjection.
 * Writes PNG bytes to a temp file and signals completion via [resultReady].
 */
class MediaProjectionService : Service() {

    companion object {
        private const val CHANNEL_ID = "screenshot_capture"
        private const val NOTIFICATION_ID = 9999
        private const val RESULT_FILE = "media_projection_capture.png"

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = resultData
        Log.d("ScreenCapture", "MediaProjectionService.onStartCommand: data=${data != null}")
        if (data == null) {
            resultFilePath = null
            resultReady.countDown()
            stopSelf()
            return START_NOT_STICKY
        }

        Thread {
            try {
                captureScreenshot(data)
            } catch (e: Exception) {
                Log.e("ScreenCapture", "MediaProjectionService captureScreenshot exception: ${e.message}")
                resultFilePath = null
                resultReady.countDown()
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun captureScreenshot(data: Intent) {
        Log.d("ScreenCapture", "captureScreenshot: starting")
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpManager.getMediaProjection(-1, data)
        Log.d("ScreenCapture", "captureScreenshot: projection=${projection != null}")
        if (projection == null) {
            Log.e("ScreenCapture", "captureScreenshot: getMediaProjection returned null — consent data may be stale")
            resultFilePath = null
            resultReady.countDown()
            stopSelf()
            return
        }

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)

        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val screenDpi = metrics.densityDpi
        Log.d("ScreenCapture", "captureScreenshot: screen ${screenWidth}x${screenHeight} dpi=$screenDpi")

        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = projection!!.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )
        Log.d("ScreenCapture", "captureScreenshot: virtualDisplay=${virtualDisplay != null}")

        Thread.sleep(500)

        Log.d("ScreenCapture", "captureScreenshot: acquiring image from ImageReader")
        val image = imageReader!!.acquireLatestImage()
        Log.d("ScreenCapture", "captureScreenshot: image=${image != null}")
        if (image != null) {
            try {
                val bitmap = try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val w = screenWidth
                    val h = screenHeight
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
                    Log.d("ScreenCapture", "captureScreenshot: bitmap=${bmp.width}x${bmp.height} (stride=$rowStride, pxStride=$pixelStride)")
                    bmp
                } catch (e: Exception) {
                    Log.e("ScreenCapture", "captureScreenshot: bitmap creation failed: ${e.message}")
                    null
                }

                if (bitmap != null) {
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    bitmap.recycle()
                    val pngBytes = stream.toByteArray()
                    Log.d("ScreenCapture", "captureScreenshot: PNG compressed ${pngBytes.size} bytes")
                    if (pngBytes.isNotEmpty()) {
                        val outPath = File(cacheDir, RESULT_FILE).absolutePath
                        File(outPath).writeBytes(pngBytes)
                        resultFilePath = outPath
                        Log.d("ScreenCapture", "captureScreenshot: saved to $outPath")
                    } else {
                        Log.e("ScreenCapture", "captureScreenshot: PNG compression produced 0 bytes")
                        resultFilePath = null
                    }
                } else {
                    Log.e("ScreenCapture", "captureScreenshot: bitmap was null")
                    resultFilePath = null
                }
            } finally {
                image.close()
            }
        } else {
            Log.e("ScreenCapture", "captureScreenshot: acquireLatestImage returned null")
            resultFilePath = null
        }

        resultReady.countDown()

        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screenshot Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "One-shot screen capture" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Gotcha")
            .setContentText("Capturing screenshot…")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        super.onDestroy()
    }
}
