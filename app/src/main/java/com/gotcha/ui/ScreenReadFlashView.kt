package com.gotcha.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.core.graphics.ColorUtils
import com.gotcha.ui.theme.OverlaySkin
import kotlin.math.pow

/**
 * One-shot "the screen was read" pulse: an accent rounded-rect border with a
 * blurred glow that ramps in and out over ~650 ms. The owning controller removes
 * the window on animation end — this view only draws. Deliberately drawn only
 * AFTER a capture has finished so it never appears in a screenshot.
 */
class ScreenReadFlashView(
    context: Context,
    private val colors: OverlaySkin
) : View(context) {

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = BORDER_DP * resources.displayMetrics.density
        color = ColorUtils.setAlphaComponent(colors.accent, BORDER_ALPHA)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = GLOW_DP * resources.displayMetrics.density
        color = ColorUtils.setAlphaComponent(colors.accent, GLOW_ALPHA)
        maskFilter = BlurMaskFilter(
            GLOW_BLUR_DP * resources.displayMetrics.density,
            BlurMaskFilter.Blur.NORMAL
        )
    }
    private val borderRect = RectF()

    /** 0..1 progress of the pulse; 0 invisible, mid peak, 1 finished. */
    var pulse: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val progress = pulse
        if (progress <= 0f || progress >= 1f) return
        val inset = glowPaint.strokeWidth / 2f + 1f
        borderRect.set(inset, inset, width - inset, height - inset)
        // Rise fast and hold near peak so the pulse reads as a clear flash rather
        // than a flicker: the sin wave alone only sits at full brightness for a
        // fraction of the duration. Raising it to ^0.4 keeps it bright across
        // most of the middle of the animation.
        val wave = kotlin.math.sin(progress * kotlin.math.PI)
        val alpha = (wave.pow(0.4) * 255).toInt().coerceIn(0, 255)
        borderPaint.alpha = alpha
        glowPaint.alpha = alpha
        val radius = colors.cardRadiusDp * resources.displayMetrics.density
        canvas.drawRoundRect(borderRect, radius, radius, glowPaint)
        canvas.drawRoundRect(borderRect, radius, radius, borderPaint)
    }

    /** The accent-derived border stroke colour; tests pin it to the skin. */
    internal fun borderColor(): Int = ColorUtils.setAlphaComponent(colors.accent, BORDER_ALPHA)

    /** The accent-derived glow colour; tests pin it to the skin. */
    internal fun glowColor(): Int = ColorUtils.setAlphaComponent(colors.accent, GLOW_ALPHA)

    private companion object {
        const val BORDER_DP = 6f
        const val GLOW_DP = 14f
        const val GLOW_BLUR_DP = 26f
        const val BORDER_ALPHA = 0xB0
        const val GLOW_ALPHA = 0x66
    }
}
