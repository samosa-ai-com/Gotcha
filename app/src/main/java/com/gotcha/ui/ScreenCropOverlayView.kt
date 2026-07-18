package com.gotcha.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen, semi-transparent overlay that lets the user drag a bounding box
 * to select a screen region ("Lens" mode). While dragging, the selected region
 * is punched clear of the dim scrim so the user sees exactly what they're
 * capturing. On release, [onSelection] fires with the selected [Rect] in view
 * pixels (empty/degenerate selections are ignored).
 *
 * The view is added to the WindowManager by [ScreenLensController]; it is focusable
 * and touchable so it intercepts the drag gesture.
 */
@SuppressLint("ViewConstructor")
class ScreenCropOverlayView(
    context: Context,
    private val onSelection: (Rect) -> Unit,
    private val onCancel: () -> Unit
) : View(context) {

    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var selecting = false

    private val scrimPaint = Paint().apply {
        color = Color.parseColor("#99000000")
    }
    private val clearPaint = Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.CYAN
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }

    init {
        // Enable an offscreen layer so PorterDuff.CLEAR punches through the scrim.
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isFocusableInTouchMode = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        if (selecting) {
            val r = currentRectF()
            canvas.drawRect(r, clearPaint)
            canvas.drawRect(r, borderPaint)
        } else {
            canvas.drawText(
                "Drag to select a region • tap outside to cancel",
                width / 2f,
                height * 0.12f,
                hintPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                currentX = event.x
                currentY = event.y
                selecting = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                currentX = event.x
                currentY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                selecting = false
                val rect = currentRect()
                if (rect.width() > MIN_SIZE_PX && rect.height() > MIN_SIZE_PX) {
                    onSelection(rect)
                } else {
                    // A tap (no real drag) cancels Lens mode.
                    onCancel()
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                selecting = false
                onCancel()
            }
        }
        return true
    }

    private fun currentRectF(): RectF = RectF(
        min(startX, currentX),
        min(startY, currentY),
        max(startX, currentX),
        max(startY, currentY)
    )

    private fun currentRect(): Rect {
        val l = min(startX, currentX).toInt()
        val t = min(startY, currentY).toInt()
        val r = max(startX, currentX).toInt()
        val b = max(startY, currentY).toInt()
        return Rect(l, t, r, b)
    }

    private companion object {
        val MIN_SIZE_PX get() = 24
    }
}
