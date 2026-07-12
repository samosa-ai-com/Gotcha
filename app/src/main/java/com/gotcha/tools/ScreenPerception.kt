package com.gotcha.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import com.gotcha.service.GotchaAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object ScreenPerception {

    data class CompressedScreenshot(
        val base64: String,
        val width: Int,
        val height: Int,
        val format: String,
        val originalSizeBytes: Long
    )

    data class UiElement(
        val index: Int,
        val className: String,
        val text: String,
        val bounds: String,
        val clickable: Boolean,
        val scrollable: Boolean,
        val checked: Boolean?,
        val enabled: Boolean
    )

    suspend fun compressScreenshot(
        maxDimension: Int = 2000,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = 50
    ): CompressedScreenshot? {
        val bytes = captureRawBytes() ?: return null
        val originalSize = bytes.size.toLong()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scaled = if (maxDimension > 0) downscale(bitmap, maxDimension) else bitmap
        if (scaled != bitmap) bitmap.recycle()
        val output = ByteArrayOutputStream()
        scaled.compress(format, quality, output)
        scaled.recycle()
        val base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        val fmtName = when (format) {
            Bitmap.CompressFormat.JPEG -> "jpeg"
            Bitmap.CompressFormat.PNG -> "png"
            Bitmap.CompressFormat.WEBP_LOSSY -> "webp"
            Bitmap.CompressFormat.WEBP_LOSSLESS -> "webp"
            else -> "jpeg"
        }
        return CompressedScreenshot(base64, scaled.width, scaled.height, fmtName, originalSize)
    }

    fun buildUiHierarchyText(maxElements: Int = 100): String {
        val service = GotchaAccessibilityService.instance ?: return "(accessibility service not available)"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) return "(requires API 18+)"
        val root = service.rootInActiveWindow ?: return "(no active window)"
        val elements = mutableListOf<UiElement>()
        collectElements(root, elements, maxElements)
        root.recycle()
        if (elements.isEmpty()) return "(no UI elements found)"
        return elements.joinToString("\n") { el ->
            val props = buildString {
                if (el.clickable) append(" clickable")
                if (el.scrollable) append(" scrollable")
                el.checked?.let { if (it) append(" checked") }
                if (!el.enabled) append(" disabled")
            }
            val text = if (el.text.isNotBlank()) "\"${el.text}\"" else ""
            "${el.index}. ${el.className} $text (${el.bounds})$props"
        }
    }

    fun buildObservationText(screenshot: CompressedScreenshot, uiTree: String): String {
        return buildString {
            appendLine("[Screen State]")
            appendLine("── Screenshot ──")
            appendLine("(image attached, ${screenshot.width} x ${screenshot.height} px, ${screenshot.format})")
            appendLine("── UI Elements ──")
            appendLine(uiTree)
            appendLine("── Coordinate Contract ──")
            appendLine("Screenshot is ${screenshot.width} x ${screenshot.height} pixels.")
            appendLine("UI element bounds below are in native pixel coordinates.")
            appendLine("For coordinate-based actions, use [0, 1000] normalized space:")
            appendLine("  actual_x = (model_x / 1000) * ${screenshot.width}")
            appendLine("  actual_y = (model_y / 1000) * ${screenshot.height}")
        }
    }

    private suspend fun captureRawBytes(): ByteArray? {
        return try {
            withContext(Dispatchers.IO) {
                val process = Runtime.getRuntime().exec("screencap -p")
                val bytes = process.inputStream.use { it.readBytes() }
                process.waitFor()
                if (bytes.isEmpty()) null else bytes
            }
        } catch (_: Exception) { null }
    }

    private fun downscale(bitmap: Bitmap, maxDim: Int): Bitmap {
        val (w, h) = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val ratio = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
            (bitmap.width * ratio).toInt() to (bitmap.height * ratio).toInt()
        } else bitmap.width to bitmap.height
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    private fun collectElements(
        node: AccessibilityNodeInfo?,
        out: MutableList<UiElement>,
        max: Int,
        index: IntArray = intArrayOf(0)
    ) {
        if (node == null || index[0] >= max) return
        if (!node.isVisibleToUser) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() < 5 && bounds.height() < 5) return

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val label = text.ifBlank { desc }

        index[0]++
        out.add(
            UiElement(
                index = index[0],
                className = node.className?.toString()?.substringAfterLast('.') ?: "Unknown",
                text = label,
                bounds = "${bounds.left}, ${bounds.top}, ${bounds.right}, ${bounds.bottom}",
                clickable = node.isClickable,
                scrollable = node.isScrollable,
                checked = if (node.isCheckable) node.isChecked else null,
                enabled = node.isEnabled
            )
        )

        for (i in 0 until node.childCount) {
            collectElements(node.getChild(i), out, max, index)
        }
    }
}
