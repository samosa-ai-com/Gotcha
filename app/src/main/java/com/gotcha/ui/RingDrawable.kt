package com.gotcha.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils

/**
 * A drawable that renders a ring (filled disk + outline) at a configurable
 * radius from the center of its bounds, with an optional soft glow aura.
 *
 * The aura is a radial gradient that peaks at [ringRadius] and fades outward
 * to [auraRadius], creating a soft halo effect around the ring.
 */
class RingDrawable : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var auraShaderValid = false

    var ringRadius: Float = 0f
        set(value) {
            field = value
            auraShaderValid = false
            invalidateSelf()
        }

    var strokeWidth: Float = 0f
        set(value) {
            field = value
            strokePaint.strokeWidth = value
            invalidateSelf()
        }

    var fillColor: Int = Color.TRANSPARENT
        set(value) {
            field = value
            invalidateSelf()
        }

    var strokeColor: Int = Color.TRANSPARENT
        set(value) {
            field = value
            invalidateSelf()
        }

    /** Outer radius of the soft glow aura. 0 disables the aura. */
    var auraRadius: Float = 0f
        set(value) {
            field = value
            auraShaderValid = false
            invalidateSelf()
        }

    /** Color used for the glow aura (alpha is scaled by [auraIntensity]). */
    var auraColor: Int = Color.TRANSPARENT
        set(value) {
            field = value
            auraShaderValid = false
            invalidateSelf()
        }

    /** Glow intensity 0..1, scales the aura color's alpha. */
    var auraIntensity: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            auraShaderValid = false
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()

        if (auraIntensity > 0f && auraRadius > 0f && auraColor != Color.TRANSPARENT) {
            if (!auraShaderValid) {
                val baseAlpha = Color.alpha(auraColor)
                val peakAlpha = (baseAlpha * auraIntensity).toInt().coerceIn(0, 255)
                val peakColor = ColorUtils.setAlphaComponent(auraColor, peakAlpha)
                val innerFade = ColorUtils.setAlphaComponent(auraColor, (peakAlpha * 0.3f).toInt())
                val glowColors = intArrayOf(innerFade, peakColor, Color.TRANSPARENT)
                val glowPositions = floatArrayOf(
                    (ringRadius / auraRadius) * 0.7f,
                    ringRadius / auraRadius,
                    1f
                )
                auraPaint.shader = RadialGradient(
                    cx, cy, auraRadius,
                    glowColors, glowPositions,
                    Shader.TileMode.CLAMP
                )
                auraShaderValid = true
            }
            canvas.drawCircle(cx, cy, auraRadius, auraPaint)
        }

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
        auraPaint.colorFilter = colorFilter
    }

    @Suppress("Deprecated")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
