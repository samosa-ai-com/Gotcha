package com.gotcha.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.graphics.ColorUtils
import com.gotcha.ui.theme.OverlaySkin

/**
 * The background every overlay surface is drawn on: a soft drop shadow, an
 * opaque fill, a hairline outline, and a lit top edge.
 *
 * An overlay floats over somebody else's app. There is nothing of ours behind
 * it, so the app's glass — blur, scrim, translucency — has nothing to show
 * through and would only make the surface unreadable over an unknown screen.
 * Depth here has to be carried by the edge and the shadow instead, which is a
 * technique that works over a white document and a photograph alike.
 *
 * The shadow is stacked rounded rects rather than [Paint.setShadowLayer] or a
 * [android.graphics.BlurMaskFilter]: both of those are ignored on a
 * hardware-accelerated canvas for anything but text, and forcing the view into
 * a software layer to get them back would cost more than this does — a handful
 * of alpha-blended rects, each cheap, drawn once and then cached with the rest
 * of the view.
 */
class OverlayCardDrawable(
    private val fillColor: Int,
    private val strokeColor: Int,
    private val highlightColor: Int,
    private val shadowColor: Int,
    private val cornerRadiusPx: Float,
    private val shadowRadiusPx: Float,
    private val hairlinePx: Float
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val rect = RectF()
    private val scratch = RectF()

    /** How much room the shadow needs outside the card, in whole pixels. */
    val shadowPadPx: Int = kotlin.math.ceil(shadowRadiusPx).toInt()

    /**
     * The shadow reads as cast from above, so it sits slightly low. Kept well
     * under the blur radius: any further and the card looks like it is peeling
     * off the screen rather than resting a few millimetres above it.
     */
    private val shadowOffsetPx: Float = shadowRadiusPx * SHADOW_DROP

    override fun getPadding(padding: Rect): Boolean {
        padding.set(shadowPadPx, shadowPadPx, shadowPadPx, shadowPadPx)
        return shadowPadPx > 0
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        rect.set(
            bounds.left + shadowPadPx.toFloat(),
            bounds.top + shadowPadPx.toFloat(),
            bounds.right - shadowPadPx.toFloat(),
            bounds.bottom - shadowPadPx.toFloat()
        )
        if (rect.width() <= 0f || rect.height() <= 0f) return

        drawShadow(canvas)

        fillPaint.color = fillColor
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, fillPaint)

        strokePaint.strokeWidth = hairlinePx
        strokePaint.color = strokeColor
        val half = hairlinePx / 2f
        scratch.set(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half)
        canvas.drawRoundRect(scratch, cornerRadiusPx, cornerRadiusPx, strokePaint)

        drawTopEdge(canvas)
    }

    /**
     * Each layer is faint; it is the overlap that builds the density, so the
     * result falls off smoothly from the card's edge outward without any layer
     * being visible as a band on its own.
     */
    private fun drawShadow(canvas: Canvas) {
        if (shadowRadiusPx <= 0f) return
        val layerAlpha = android.graphics.Color.alpha(shadowColor).toFloat() / SHADOW_STEPS
        for (step in SHADOW_STEPS downTo 1) {
            val spread = shadowRadiusPx * step / SHADOW_STEPS
            scratch.set(
                rect.left - spread,
                rect.top - spread + shadowOffsetPx,
                rect.right + spread,
                rect.bottom + spread + shadowOffsetPx
            )
            shadowPaint.color = ColorUtils.setAlphaComponent(shadowColor, layerAlpha.toInt())
            canvas.drawRoundRect(
                scratch,
                cornerRadiusPx + spread,
                cornerRadiusPx + spread,
                shadowPaint
            )
        }
    }

    /**
     * The highlight is the same rounded rect as the outline, clipped to the top
     * of the card, so it follows the corners round and stops where the light
     * would. Drawn last, over the outline it replaces along that edge.
     */
    private fun drawTopEdge(canvas: Canvas) {
        val strip = cornerRadiusPx.coerceAtLeast(hairlinePx * 2f)
        canvas.save()
        canvas.clipRect(rect.left, rect.top, rect.right, rect.top + strip)
        strokePaint.color = highlightColor
        strokePaint.strokeWidth = hairlinePx
        val half = hairlinePx / 2f
        scratch.set(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half)
        canvas.drawRoundRect(scratch, cornerRadiusPx, cornerRadiusPx, strokePaint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        shadowPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        shadowPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val SHADOW_STEPS = 8
        const val SHADOW_DROP = 0.3f
    }
}

/**
 * The raised surface for [colors], at [radiusDp] (the card radius unless the
 * caller wants the button radius, or half a ball).
 */
fun overlayCardBackground(
    density: Float,
    colors: OverlaySkin,
    radiusDp: Float = colors.cardRadiusDp,
    fill: Int = colors.surface,
    shadowRadiusDp: Float = colors.shadowRadiusDp
): OverlayCardDrawable = OverlayCardDrawable(
    fillColor = fill,
    strokeColor = colors.outline,
    highlightColor = colors.edgeHighlight,
    shadowColor = colors.shadowColor,
    cornerRadiusPx = radiusDp * density,
    shadowRadiusPx = shadowRadiusDp * density,
    hairlinePx = density.coerceAtLeast(1f)
)

/**
 * Give [this] the raised overlay surface, with [horizontalDp]/[verticalDp] of
 * content padding *inside* it.
 *
 * Padding is set here rather than left to the caller because the shadow lives
 * outside the card and needs its own room in the view: a `setPadding` call
 * after `setBackground` silently discards the drawable's own padding, which is
 * exactly how a shadow ends up clipped to a hard square edge.
 */
fun View.applyOverlayCard(
    colors: OverlaySkin,
    horizontalDp: Int,
    verticalDp: Int,
    radiusDp: Float = colors.cardRadiusDp,
    fill: Int = colors.surface
) {
    val density = resources.displayMetrics.density
    val card = overlayCardBackground(density, colors, radiusDp, fill)
    background = card
    val pad = card.shadowPadPx
    setPadding(
        pad + (horizontalDp * density).toInt(),
        pad + (verticalDp * density).toInt(),
        pad + (horizontalDp * density).toInt(),
        pad + (verticalDp * density).toInt()
    )
}
