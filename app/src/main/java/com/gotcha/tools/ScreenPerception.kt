package com.gotcha.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.gotcha.service.GotchaAccessibilityService
import com.gotcha.service.MediaProjectionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object ScreenPerception {

    /** Set by MainActivity after the user grants MediaProjection consent. */
    @Volatile
    var mediaProjectionResultData: android.content.Intent? = null

    @Volatile
    private var lastElementsCache: List<UiElement>? = null

    /** Application context — set by ChatViewModel.init(). Used by captureRawBytes. */
    @Volatile
    var appContext: android.content.Context? = null

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
        drawGrid: Boolean = false,
        saveDir: File? = null
    ): CompressedScreenshot? {
        return try {
            Log.d("ScreenCapture", "compressScreenshot: starting capture...")
            val bytes = captureRawBytes()
            if (bytes == null) {
                Log.e("ScreenCapture", "compressScreenshot: captureRawBytes returned null — all paths failed")
                return null
            }
            Log.d("ScreenCapture", "compressScreenshot: got ${bytes.size} raw bytes")
            val originalSize = bytes.size.toLong()
            val options = BitmapFactory.Options().apply {
                inMutable = true
                inScaled = false
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (bitmap == null) {
                Log.e(
                    "ScreenCapture",
                    "compressScreenshot: BitmapFactory.decodeByteArray returned null — invalid image data"
                )
                return null
            }
            Log.d("ScreenCapture", "compressScreenshot: decoded bitmap ${bitmap.width}x${bitmap.height}")
            val annotated = if (drawGrid) {
                val elements = lastElementsCache ?: emptyList()
                if (elements.isNotEmpty()) {
                    drawElementOverlays(bitmap, elements)
                } else {
                    drawCoordinateGrid(bitmap)
                }
            } else {
                bitmap
            }

            val forEncoding = if (maxDimension > 0) downscale(annotated, maxDimension) else annotated

            val sw = forEncoding.width
            val sh = forEncoding.height
            val output = ByteArrayOutputStream()
            forEncoding.compress(format, quality, output)

            if (saveDir != null) {
                try {
                    if (!saveDir.exists()) saveDir.mkdirs()
                    val debugFile = File(saveDir, "screenshot_overlay_${System.currentTimeMillis()}.png")
                    FileOutputStream(debugFile).use { fos ->
                        forEncoding.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                } catch (e: Throwable) {
                    Log.e("ScreenCapture", "Failed to save debug screenshot: ${e.message}")
                }
            }

            if (forEncoding !== annotated) forEncoding.recycle()
            if (annotated !== bitmap) annotated.recycle()
            bitmap.recycle()
            val base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            // WEBP_LOSSY/WEBP_LOSSLESS enum fields need API 30 — match by name instead.
            val fmtName = when {
                format == Bitmap.CompressFormat.JPEG -> "jpeg"
                format == Bitmap.CompressFormat.PNG -> "png"
                format.name.startsWith("WEBP") -> "webp"
                else -> "jpeg"
            }
            CompressedScreenshot(base64, sw, sh, fmtName, originalSize)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("ScreenCapture", "compressScreenshot failed with throwable: ${e.message}", e)
            null
        }
    }

    fun buildUiHierarchyText(maxElements: Int = 100): String {
        return try {
            val service = GotchaAccessibilityService.instance ?: return "(accessibility service not available)"
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) return "(requires API 18+)"
            val root = service.rootInActiveWindow ?: return "(no active window)"
            val elements = mutableListOf<UiElement>()
            collectElements(root, elements, maxElements)
            lastElementsCache = elements.toList()
            root.recycle()
            if (elements.isEmpty()) return "(no UI elements found)"
            elements.joinToString("\n") { el ->
                val props = buildString {
                    if (el.clickable) append(" clickable")
                    if (el.scrollable) append(" scrollable")
                    el.checked?.let { if (it) append(" checked") }
                    if (!el.enabled) append(" disabled")
                }
                val text = if (el.text.isNotBlank()) "\"${el.text}\"" else ""
                "${el.index}. ${el.className} $text (${el.bounds})$props"
            }
        } catch (e: Throwable) {
            Log.e("ScreenCapture", "buildUiHierarchyText failed with throwable: ${e.message}", e)
            "(error reading UI: ${e.message})"
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
        return lastElementsCache?.firstOrNull { it.index == targetIndex }
    }

    fun normalizeToPixel(modelX: Int, modelY: Int, displayW: Int, displayH: Int): Pair<Int, Int> {
        val actualX = (modelX.toFloat() / 1000f * displayW).toInt().coerceIn(0, displayW - 1)
        val actualY = (modelY.toFloat() / 1000f * displayH).toInt().coerceIn(0, displayH - 1)
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

    fun getCurrentPackageName(): String? {
        val service = GotchaAccessibilityService.instance ?: return null
        val root = service.rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString()
        root.recycle()
        return pkg
    }

    private fun drawCoordinateGrid(bitmap: Bitmap): Bitmap {
        val canvas = Canvas(bitmap)
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

        return bitmap
    }

    private fun drawElementOverlays(bitmap: Bitmap, elements: List<UiElement>): Bitmap {
        val canvas = Canvas(bitmap)

        val boxPaint = Paint().apply {
            color = Color.argb(120, 255, 50, 50)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val labelBgPaint = Paint().apply {
            color = Color.argb(220, 0, 0, 0)
            style = Paint.Style.FILL
        }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 28f
            isAntiAlias = true
            setShadowLayer(2f, 1f, 1f, Color.argb(255, 0, 0, 0))
        }

        // Draw boxes and tags
        for (el in elements) {
            val parts = el.bounds.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (parts.size == 4) {
                val left = parts[0].toFloat()
                val top = parts[1].toFloat()
                val right = parts[2].toFloat()
                val bottom = parts[3].toFloat()

                // Draw bounding box
                canvas.drawRect(left, top, right, bottom, boxPaint)

                // Draw label [Index] at the top-left of the box
                val label = "[${el.index}]"
                val textWidth = textPaint.measureText(label)
                val fontMetrics = textPaint.fontMetrics
                val textHeight = fontMetrics.descent - fontMetrics.ascent

                // Keep label within bounds
                val bgLeft = left
                var bgTop = top - textHeight - 4f
                if (bgTop < 0) bgTop = top // If goes off top edge, push inside the box

                val bgRight = bgLeft + textWidth + 8f
                val bgBottom = bgTop + textHeight + 8f

                canvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, labelBgPaint)
                canvas.drawText(label, bgLeft + 4f, bgBottom - fontMetrics.descent - 4f, textPaint)
            }
        }
        return bitmap
    }

    /** Public entry point: capture raw screenshot PNG bytes. */
    suspend fun captureRawScreenshotBytes(): ByteArray? = captureRawBytes()

    private suspend fun captureRawBytes(): ByteArray? {
        // Path 1: AccessibilityService.takeScreenshot() — API 30+, no special perms.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val service = GotchaAccessibilityService.instance
            Log.d("ScreenCapture", "Path1: API>=30, service=${service != null}")
            if (service != null) {
                for (attempt in 1..2) {
                    Log.d("ScreenCapture", "Path1: takeScreenshotBitmap attempt $attempt")
                    val bitmap = service.takeScreenshotBitmap()
                    Log.d("ScreenCapture", "Path1: takeScreenshotBitmap returned ${bitmap != null}")
                    if (bitmap != null) {
                        val bytes = withContext(Dispatchers.Default) {
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                            bitmap.recycle()
                            stream.toByteArray()
                        }
                        Log.d("ScreenCapture", "Path1: compressed to ${bytes.size} bytes")
                        if (bytes.isNotEmpty()) return bytes
                    }
                    if (attempt < 2) delay(1100)
                }
                Log.w("ScreenCapture", "Path1: both attempts failed")
            }
        }
        // Path 2: MediaProjection — works on all devices if user granted consent.
        val projectionData = mediaProjectionResultData
        val ctx = appContext ?: GotchaAccessibilityService.instance?.applicationContext
        Log.d(
            "ScreenCapture",
            "Path2: projectionData=${projectionData != null}, ctx=${ctx != null}, appContext=${appContext != null}"
        )
        if (projectionData != null && ctx != null) {
            Log.d("ScreenCapture", "Path2: calling MediaProjectionService.capture()")
            val bytes = withContext(Dispatchers.IO) {
                MediaProjectionService.capture(ctx, projectionData)
            }
            Log.d("ScreenCapture", "Path2: MediaProjectionService.capture returned ${bytes?.size ?: "null"} bytes")
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
        // Path 3: screencap via shell (works on rooted devices / emulators).
        Log.d("ScreenCapture", "Path3: trying screencap -p")
        return try {
            withContext(Dispatchers.IO) {
                val process = Runtime.getRuntime().exec("screencap -p")
                val bytes = process.inputStream.use { it.readBytes() }
                process.waitFor()
                Log.d("ScreenCapture", "Path3: screencap returned ${bytes.size} bytes, exit=${process.exitValue()}")
                if (bytes.isEmpty()) null else bytes
            }
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Path3: screencap failed: ${e.message}")
            null
        }
    }

    fun compressBitmap(
        bitmap: Bitmap,
        maxDimension: Int = 1024,
        quality: Int = 85,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        recycleInput: Boolean = false
    ): String? {
        return try {
            val forEncoding = if (maxDimension > 0) downscale(bitmap, maxDimension) else bitmap
            val output = ByteArrayOutputStream()
            forEncoding.compress(format, quality, output)
            if (forEncoding !== bitmap) {
                forEncoding.recycle()
            }
            if (recycleInput) {
                bitmap.recycle()
            }
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("ScreenCapture", "compressBitmap failed: ${e.message}", e)
            null
        }
    }

    private fun downscale(bitmap: Bitmap, maxDim: Int): Bitmap {
        val (w, h) = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val ratio = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
            (bitmap.width * ratio).toInt() to (bitmap.height * ratio).toInt()
        } else {
            bitmap.width to bitmap.height
        }
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

        val isInteractable = node.isClickable || node.isScrollable || node.isEditable || node.isCheckable
        val hasMeaningfulContent = label.isNotEmpty()

        if (isInteractable || hasMeaningfulContent) {
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
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            try {
                collectElements(child, out, max, index)
            } finally {
                child?.recycle()
            }
        }
    }
}
