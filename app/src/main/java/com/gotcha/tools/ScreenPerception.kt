package com.gotcha.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
        quality: Int = 50,
        drawGrid: Boolean = false
    ): CompressedScreenshot? {
        val bytes = captureRawBytes() ?: return null
        val originalSize = bytes.size.toLong()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scaled = if (maxDimension > 0) downscale(bitmap, maxDimension) else bitmap
        if (scaled != bitmap) bitmap.recycle()
        val (forEncoding, recycleForEncoding) = if (drawGrid) {
            drawCoordinateGrid(scaled) to true
        } else {
            scaled to false
        }
        if (drawGrid) scaled.recycle()
        val output = ByteArrayOutputStream()
        forEncoding.compress(format, quality, output)
        if (recycleForEncoding) forEncoding.recycle()
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
            appendLine("A coordinate grid is overlaid on the screenshot (10x10 divisions).")
            appendLine("UI element bounds below are in native pixel coordinates.")
            appendLine("For coordinate-based actions, use [0, 1000] normalized space:")
            appendLine("  actual_x = (model_x / 1000) * ${screenshot.width}")
            appendLine("  actual_y = (model_y / 1000) * ${screenshot.height}")
        }
    }

    fun resolveElementByIndex(targetIndex: Int): UiElement? {
        val service = GotchaAccessibilityService.instance ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) return null
        val root = service.rootInActiveWindow ?: return null
        val container = mutableListOf<UiElement>()
        collectElements(root, container, 1000)
        root.recycle()
        return container.firstOrNull { it.index == targetIndex }
    }

    fun normalizeToPixel(modelX: Int, modelY: Int, displayW: Int, displayH: Int): Pair<Int, Int> {
        val actualX = (modelX.toFloat() / 1000f * displayW).toInt().coerceIn(0, displayW)
        val actualY = (modelY.toFloat() / 1000f * displayH).toInt().coerceIn(0, displayH)
        return Pair(actualX, actualY)
    }

    fun denormalizeToModel(pixelX: Int, pixelY: Int, displayW: Int, displayH: Int): Pair<Int, Int> {
        val modelX = (pixelX.toFloat() / displayW * 1000f).toInt().coerceIn(0, 1000)
        val modelY = (pixelY.toFloat() / displayH * 1000f).toInt().coerceIn(0, 1000)
        return Pair(modelX, modelY)
    }

    fun getScreenDimensions(): Pair<Int, Int> {
        val service = GotchaAccessibilityService.instance
        if (service != null) {
            val root = service.rootInActiveWindow ?: return Pair(1080, 2340)
            val bounds = Rect()
            root.getBoundsInScreen(bounds)
            root.recycle()
            val w = bounds.right
            val h = bounds.bottom
            if (w > 0 && h > 0) return Pair(w, h)
        }
        return Pair(1080, 2340)
    }

    private fun drawCoordinateGrid(bitmap: Bitmap): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return bitmap
        val canvas = Canvas(result)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val divisions = 10

        val linePaint = Paint().apply {
            color = Color.argb(60, 180, 180, 200)
            strokeWidth = 1.5f
            isAntiAlias = true
        }
        val labelPaint = Paint().apply {
            color = Color.argb(160, 255, 255, 255)
            textSize = 20f
            isAntiAlias = true
            setShadowLayer(2f, 1f, 1f, Color.argb(128, 0, 0, 0))
        }

        for (i in 0..divisions) {
            val x = w * i / divisions
            val y = h * i / divisions
            canvas.drawLine(x, 0f, x, h, linePaint)
            canvas.drawLine(0f, y, w, y, linePaint)

            val labelX = (i * 100).toString()
            val labelY = (i * 100).toString()
            if (i > 0 && i < divisions) {
                canvas.drawText(labelX, x - 10f, h - 6f, labelPaint)
                canvas.drawText(labelY, 4f, y - 6f, labelPaint)
            }
            canvas.drawText("0", 4f, h - 6f, labelPaint)
            canvas.drawText("1000", w - 42f, h - 6f, labelPaint)
            canvas.drawText("1000", 4f, 16f, labelPaint)
        }

        val centerPaint = Paint().apply {
            color = Color.argb(100, 255, 200, 50)
            strokeWidth = 2f
        }
        val cx = w / 2
        val cy = h / 2
        canvas.drawCircle(cx, cy, 8f, centerPaint)

        return result
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
