package com.gotcha.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * A drawable that renders a ring (filled disk + outline) at a configurable
 * radius from the center of its bounds. Used for the long-press growing-ring
 * effect on the assistive ball and the call end button.
 *
 * Unlike scaling a [android.graphics.drawable.GradientDrawable], the stroke
 * width and fill here are independent of the [ringRadius] — animating the
 * radius changes the ring's size without distorting its stroke.
 *
 * Properties are settable from a [android.animation.ValueAnimator] for smooth
 * interpolation: grow [ringRadius] from a small value to a max while
 * simultaneously fading the fill.
 */
class RingDrawable : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** How far the ring extends from the center, in pixels. */
    var ringRadius: Float = 0f
        set(value) {
            field = value
            invalidateSelf()
        }

    /** Outline thickness in pixels. Independent of [ringRadius]. */
    var strokeWidth: Float = 0f
        set(value) {
            field = value
            strokePaint.strokeWidth = value
            invalidateSelf()
        }

    /** Color painted inside the ring (between the center and [ringRadius]). */
    var fillColor: Int = Color.TRANSPARENT
        set(value) {
            field = value
            invalidateSelf()
        }

    /** Outline color drawn at [ringRadius]. */
    var strokeColor: Int = Color.TRANSPARENT
        set(value) {
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        if (ringRadius <= 0f || bounds.isEmpty) return
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        if (fillColor != Color.TRANSPARENT) {
            fillPaint.color = fillColor
            canvas.drawCircle(cx, cy, ringRadius, fillPaint)
        }
        if (strokeColor != Color.TRANSPARENT && strokeWidth > 0f) {
            strokePaint.color = strokeColor
            strokePaint.strokeWidth = strokeWidth
            canvas.drawCircle(cx, cy, ringRadius, strokePaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }

    @Suppress("Deprecated")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
