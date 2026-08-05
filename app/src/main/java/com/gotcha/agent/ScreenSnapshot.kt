package com.gotcha.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.gotcha.service.GotchaAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Accessibility-based screen capture for overlay features (voice calls).
 * Unlike the agent's read_screen tools (MediaProjection via ScreenPerception),
 * this needs no per-session consent — only the accessibility service.
 */
object ScreenSnapshot {

    private const val MAX_DIMENSION = 1024
    private const val JPEG_QUALITY = 85
    private const val RATE_LIMIT_RETRY_DELAY_MS = 1100L

    /** True when the Gotcha accessibility service is enabled and bound. */
    fun isAvailable(): Boolean = GotchaAccessibilityService.instance != null

    /**
     * Capture the current screen as a downscaled JPEG base64 string via the
     * accessibility screenshot API (API 30+). Returns null if unavailable
     * (service not bound, or capture failed).
     *
     * The accessibility screenshot API is rate-limited (roughly one call per second),
     * so a single failure is retried once after a short delay.
     */
    suspend fun captureScreenBase64(): String? {
        val service = GotchaAccessibilityService.instance ?: return null
        var bitmap = service.takeScreenshotBitmap()
        if (bitmap == null) {
            delay(RATE_LIMIT_RETRY_DELAY_MS) // clear the screenshot rate-limit window, then retry once
            bitmap = service.takeScreenshotBitmap()
        }
        if (bitmap == null) return null
        return withContext(Dispatchers.Default) {
            com.gotcha.tools.ScreenPerception.compressBitmap(
                bitmap = bitmap,
                maxDimension = MAX_DIMENSION,
                quality = JPEG_QUALITY,
                format = Bitmap.CompressFormat.JPEG,
                recycleInput = true
            )
        }
    }

    /** Read on-screen text via the accessibility service, or null if unavailable. */
    fun captureScreenText(limit: Int = 60): String? {
        val service = GotchaAccessibilityService.instance ?: return null
        val lines = service.dumpScreenText(limit)
        return if (lines.isEmpty()) null else lines.joinToString("\n") { "- $it" }
    }

    /**
     * True when the JPEG base64 is essentially a solid black frame — the usual
     * result of capturing while the screen is off or blank. A black screenshot
     * carries no information for the model (and can look like a "vision" input
     * that never resolves), so callers should drop it. A dark app with content
     * has enough bright pixels / variance to fail this check.
     *
     * The frame is decoded as a small thumbnail ([SAMPLE_SIZE]) rather than at
     * full resolution — the luminance statistics are what matter, not the
     * pixels — so an off-screen capture costs ~1/64 of the memory.
     */
    fun isMostlyBlack(jpegBase64: String, blackRatio: Float = 0.99f): Boolean {
        val bytes = try {
            android.util.Base64.decode(jpegBase64, android.util.Base64.DEFAULT)
        } catch (_: Exception) {
            return false
        }
        val options = BitmapFactory.Options().apply { inSampleSize = SAMPLE_SIZE }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
        try {
            return classifyBlackness(bitmap, blackRatio)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Samples [bitmap] and reports whether it is essentially solid black. Kept
     * separate from [isMostlyBlack] so the classification logic is testable on
     * an in-memory bitmap without going through a JPEG decode.
     */
    internal fun classifyBlackness(bitmap: Bitmap, blackRatio: Float = 0.99f): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return false
        var sampled = 0
        var dark = 0
        var sumLuma = 0.0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
                sumLuma += luma
                if (luma < 8.0) dark++
                sampled++
            }
        }
        if (sampled == 0) return false
        val meanLuma = sumLuma / sampled
        val darkRatio = dark.toDouble() / sampled
        return meanLuma < 10.0 && darkRatio >= blackRatio
    }

    private const val SAMPLE_SIZE = 8
}
