package com.gotcha.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import androidx.core.graphics.ColorUtils
import com.gotcha.ui.theme.OverlaySkin
import kotlin.math.PI
import kotlin.math.cos

/**
 * One-shot "the screen was read" pulse: an accent frame that glows from the
 * physical screen edges inward and fades back out over ~1 s. The owning
 * controller removes the window on animation end — this view only draws.
 * Deliberately drawn only AFTER a capture has finished so it never appears in
 * a screenshot.
 *
 * Rendering is an edge-anchored gradient, not a blurred stroke: each edge band
 * runs from full accent at the exact screen boundary to transparent ~48 dp
 * inward. Because the window is MATCH_PARENT and full-screen, the frame hugs
 * the real display perimeter (the device rounds the corners itself) and the
 * brightness is maximal on the edges and falls off toward the center — a clean
 * frame, not a floating box with a glow that reduces on both sides.
 */
class ScreenReadFlashView(
    context: Context,
    private val colors: OverlaySkin
) : View(context) {

    private val density = resources.displayMetrics.density

    /** Edge colour at its full (pre-envelope) alpha, from the skin accent. */
    private val edgeColor = ColorUtils.setAlphaComponent(colors.accent, EDGE_ALPHA)

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = edgeColor
    }

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
        val alpha = (pulseAlpha(progress) * EDGE_ALPHA).toInt().coerceIn(0, 255)
        val glowW = EDGE_GLOW_WIDTH_DP * density
        // Left / right bands.
        edgePaint.alpha = alpha
        edgePaint.shader = gradient(0f, 0f, glowW, 0f)
        canvas.drawRect(0f, 0f, glowW, height.toFloat(), edgePaint)
        edgePaint.shader = gradient(width.toFloat(), 0f, width.toFloat() - glowW, 0f)
        canvas.drawRect(width.toFloat() - glowW, 0f, width.toFloat(), height.toFloat(), edgePaint)
        // Top / bottom bands.
        edgePaint.shader = gradient(0f, 0f, 0f, glowW)
        canvas.drawRect(0f, 0f, width.toFloat(), glowW, edgePaint)
        edgePaint.shader = gradient(0f, height.toFloat(), 0f, height.toFloat() - glowW)
        canvas.drawRect(0f, height.toFloat() - glowW, width.toFloat(), height.toFloat(), edgePaint)
    }

    /**
     * A linear shader from full [edgeColor] at the anchor to transparent at the
     * fade point. Per-edge coordinates are passed in by [onDraw] so the falloff
     * always runs from the physical edge inward.
     */
    private fun gradient(x0: Float, y0: Float, x1: Float, y1: Float): Shader =
        LinearGradient(
            x0,
            y0,
            x1,
            y1,
            edgeColor,
            ColorUtils.setAlphaComponent(colors.accent, 0),
            Shader.TileMode.CLAMP
        )

    /** The accent-derived edge colour; tests pin it to the skin. */
    internal fun edgeColor(): Int = edgeColor

    /**
     * The pulse's alpha envelope for [progress] in 0..1, mapping to 0..1.
     *
     * Raised cosine: zero slope at both ends, a single smooth peak mid-pulse,
     * and 0 exactly at progress 0 and 1. No hard on/off snap and no strobing —
     * deliberately kept gentle for photosensitivity.
     */
    internal fun pulseAlpha(progress: Float): Float {
        if (progress <= 0f || progress >= 1f) return 0f
        return ((1f - cos(progress * 2f * PI)) / 2f).toFloat()
    }

    private companion object {
        const val EDGE_GLOW_WIDTH_DP = 48f
        const val EDGE_ALPHA = 0xA0
    }
}
