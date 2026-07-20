package com.gotcha.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils

/**
 * A translucent, glossy "liquid glass" circular button background.
 *
 * Everything is drawn as circular radial/linear gradients that fade to
 * transparent well before the (square) bounds, so the button never shows a
 * square edge and never fully occludes what's behind it:
 *  - a soft circular halo / glow just outside the sphere (doubles as the drop
 *    shadow and the breathing aura — [glow] intensifies it),
 *  - a translucent tinted sphere ([fillAlpha] controls see-through-ness),
 *  - a specular gloss near the top and a rim light.
 *
 * The visible sphere is inset from the bounds so the halo has room to fade out
 * as a circle within the same square view — no layout/size change required.
 */
class GlassButtonDrawable(tint: Int) : Drawable() {

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var baseShaderValid = false
    private var specShaderValid = false
    private var rimShaderValid = false

    private var sphereR = 0f
    private var haloOuter = 0f

    /** Base sphere opacity (0..255). Lower = more transparent / less blocking. */
    var fillAlpha: Int = 125
        set(value) {
            field = value.coerceIn(0, 255)
            invalidateSelf()
        }

    var tintColor: Int = tint
        set(value) {
            field = value
            baseShaderValid = false
            rimShaderValid = false
            invalidateSelf()
        }

    /** 0..1 extra glow — brightens the halo, gloss and rim (breathing). */
    var glow: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidateSelf()
        }

    override fun onBoundsChange(bounds: Rect) {
        val rBox = minOf(bounds.width(), bounds.height()) / 2f
        sphereR = rBox * 0.72f
        haloOuter = rBox * 0.99f
        baseShaderValid = false
        specShaderValid = false
        rimShaderValid = false
    }

    private fun lighten(c: Int, f: Float) = ColorUtils.blendARGB(c, Color.WHITE, f)
    private fun darken(c: Int, f: Float) = ColorUtils.blendARGB(c, Color.BLACK, f)

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty || sphereR <= 0f) return
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()

        // Circular halo / glow — the soft drop shadow at rest, the breathing
        // aura when [glow] rises. Peaks just outside the sphere and fades to
        // transparent before the bounds, so it reads as a circle, not a square.
        val peakA = (HALO_BASE_ALPHA + glow * HALO_GLOW_ALPHA).toInt().coerceIn(0, 255)
        haloPaint.shader = RadialGradient(
            cx, cy, haloOuter,
            intArrayOf(
                ColorUtils.setAlphaComponent(tintColor, peakA / 4),
                ColorUtils.setAlphaComponent(tintColor, peakA),
                Color.TRANSPARENT
            ),
            floatArrayOf((sphereR * 0.62f) / haloOuter, sphereR / haloOuter, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, haloOuter, haloPaint)

        // Translucent tinted sphere, highlight biased toward the upper-left.
        if (!baseShaderValid) {
            basePaint.shader = RadialGradient(
                cx - sphereR * 0.30f, cy - sphereR * 0.36f, sphereR * 1.35f,
                intArrayOf(lighten(tintColor, 0.40f), tintColor, darken(tintColor, 0.32f)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            baseShaderValid = true
        }
        basePaint.alpha = fillAlpha
        canvas.drawCircle(cx, cy, sphereR, basePaint)

        // Soft specular gloss concentrated near the top.
        if (!specShaderValid) {
            specPaint.shader = RadialGradient(
                cx, cy - sphereR * 0.44f, sphereR * 0.78f,
                intArrayOf(Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            specShaderValid = true
        }
        specPaint.alpha = (28f + glow * 70f).toInt().coerceIn(0, 160)
        canvas.drawCircle(cx, cy, sphereR, specPaint)

        // Rim light — bright at the top, fading to a darker tint at the bottom.
        if (!rimShaderValid) {
            rimPaint.shader = LinearGradient(
                cx, cy - sphereR, cx, cy + sphereR,
                intArrayOf(
                    ColorUtils.setAlphaComponent(Color.WHITE, 150),
                    ColorUtils.setAlphaComponent(Color.WHITE, 30),
                    ColorUtils.setAlphaComponent(darken(tintColor, 0.25f), 90)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            rimPaint.strokeWidth = sphereR * 0.07f
            rimShaderValid = true
        }
        rimPaint.alpha = (150 + glow * 90f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, sphereR - rimPaint.strokeWidth * 0.3f, rimPaint)
    }

    override fun setAlpha(alpha: Int) {
        haloPaint.alpha = alpha
        basePaint.alpha = alpha
        specPaint.alpha = alpha
        rimPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        haloPaint.colorFilter = colorFilter
        basePaint.colorFilter = colorFilter
        specPaint.colorFilter = colorFilter
        rimPaint.colorFilter = colorFilter
    }

    @Suppress("Deprecated")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val HALO_BASE_ALPHA = 22f
        const val HALO_GLOW_ALPHA = 80f
    }
}
