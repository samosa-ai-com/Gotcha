package com.gotcha.agent

import android.graphics.Bitmap
import android.os.Build
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
    fun isAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && GotchaAccessibilityService.instance != null

    /**
     * Capture the current screen as a downscaled JPEG base64 string via the
     * accessibility screenshot API (API 30+). Returns null if unavailable
     * (older API, service not bound, or capture failed).
     *
     * The accessibility screenshot API is rate-limited (roughly one call per second),
     * so a single failure is retried once after a short delay.
     */
    suspend fun captureScreenBase64(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
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
}
