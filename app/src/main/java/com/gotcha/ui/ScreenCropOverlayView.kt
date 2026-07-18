package com.gotcha.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Full-screen premium "Lens" overlay. The user draws ANY free-form shape; on
 * release its bounding rectangle (max width/height of the drawn path) becomes the
 * selection. [onSelection] fires with that [Rect] in view pixels; a tap (no real
 * drag) calls [onCancel].
 *
 * Visual treatment (matches the app logo's pink/blue theme):
 *  - a soft pink glow bleeding in from all four screen edges,
 *  - drifting glowing particles (pink + blue) whose density is higher near the
 *    edges and sparse in the centre,
 *  - while drawing, the traced path is stroked in a pink→blue gradient and its
 *    live bounding box is outlined.
 *
 * The particle field animates via [postInvalidateOnAnimation]; it is deterministic
 * only in look, not in exact positions (seeded from the view size).
 */
@SuppressLint("ViewConstructor")
class ScreenCropOverlayView(
    context: Context,
    private val onSelection: (Rect) -> Unit,
    private val onCancel: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density

    // ---- Free-form drawing state ----
    private val path = Path()
    private var minX = 0f
    private var minY = 0f
    private var maxX = 0f
    private var maxY = 0f
    private var drawing = false
    private var moved = false

    /** A finalized selection to keep drawn while the action menu is shown. */
    private var frozenRect: RectF? = null

    /**
     * When true, all overlay chrome (scrim, glow, particles, selection) is hidden
     * so a screenshot taken behind us does not capture it. Restored right after.
     */
    private var captureMode = false

    // ---- Paints ----
    private val dimPaint = Paint().apply { color = Color.parseColor("#66101018") }
    private val edgeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Thin premium pinkish-blue glowing border around the whole screen edge. */
    private val screenBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.parseColor("#B0568CD8")
    }
    private val screenBorderGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f * density
        color = Color.parseColor("#55A0459E")
        maskFilter = android.graphics.BlurMaskFilter(14f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    /** Selection-rectangle border: premium pinkish-blue, glowing. */
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.parseColor("#D0B06FD0")
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f * density, 8f * density), 0f)
    }
    private val boxGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f * density
        color = Color.parseColor("#55568CD8")
        maskFilter = android.graphics.BlurMaskFilter(12f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * density
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 0f, Color.parseColor("#E081C0"))
    }

    private val particles = ArrayList<Particle>()
    private var lastFrameNs = 0L

    init {
        isFocusableInTouchMode = true
        // Software layer keeps radial/linear gradient + shadow rendering correct.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildEdgeGlow(w, h)
        seedParticles(w, h)
    }

    private fun buildEdgeGlow(w: Int, h: Int) {
        // A large radial gradient that is transparent in the centre and pink at the
        // rim, giving an edge-vignette wash.
        val cx = w / 2f
        val cy = h / 2f
        val radius = max(w, h) * 0.75f
        edgeGlowPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                Color.parseColor("#22E081C0"),
                Color.parseColor("#553E7BFF"),
                Color.parseColor("#66FF5FBF")
            ),
            floatArrayOf(0f, 0.45f, 0.72f, 0.9f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun seedParticles(w: Int, h: Int) {
        particles.clear()
        if (w == 0 || h == 0) return
        val rnd = Random(w * 31L + h)
        // Density scales with screen area; edge-biased placement via rejection.
        val target = ((w * h) / (26000f * density)).toInt().coerceIn(40, 220)
        var placed = 0
        var guard = 0
        while (placed < target && guard < target * 12) {
            guard++
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            // edgeScore ~1 at edges, ~0 at centre.
            val ex = 1f - (2f * x / w - 1f).let { it * it }
            val ey = 1f - (2f * y / h - 1f).let { it * it }
            val centreness = (1f - ex) * (1f - ey) // ~1 at centre corners... invert below
            // Keep more particles near edges: accept with prob higher when centreness low.
            val keepProb = 0.15f + 0.85f * (1f - centreness)
            if (rnd.nextFloat() > keepProb) continue
            particles.add(
                Particle(
                    x = x,
                    y = y,
                    vx = (rnd.nextFloat() - 0.5f) * 14f * density,
                    vy = (rnd.nextFloat() - 0.5f) * 14f * density,
                    radius = (1.2f + rnd.nextFloat() * 2.6f) * density,
                    pink = rnd.nextBoolean(),
                    phase = rnd.nextFloat() * 6.283f,
                    twinkle = 0.6f + rnd.nextFloat() * 1.8f
                )
            )
            placed++
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (captureMode) {
            // Draw nothing this frame so a screenshot behind us stays clean, but
            // keep animating so we resume smoothly.
            postInvalidateOnAnimation()
            return
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), edgeGlowPaint)

        stepAndDrawParticles(canvas)

        drawScreenBorder(canvas)

        val frozen = frozenRect
        when {
            drawing && moved -> {
                // Gradient stroke for the traced path.
                pathPaint.shader = LinearGradient(
                    minX, minY, maxX, maxY,
                    Color.parseColor("#FF6FC0"), Color.parseColor("#5B8CFF"),
                    Shader.TileMode.CLAMP
                )
                canvas.drawPath(path, pathPaint)
                drawSelectionBox(canvas, RectF(minX, minY, maxX, maxY))
            }
            frozen != null -> drawSelectionBox(canvas, frozen)
            else -> canvas.drawText(
                "Draw around anything • tap to cancel",
                width / 2f,
                height * 0.12f,
                hintPaint
            )
        }
        postInvalidateOnAnimation()
    }

    /** A thin, premium pinkish-blue glowing frame just inside the screen edges. */
    private fun drawScreenBorder(canvas: Canvas) {
        val inset = screenBorderGlowPaint.strokeWidth / 2f + 1f
        val r = RectF(inset, inset, width - inset, height - inset)
        val radius = 24f * density
        canvas.drawRoundRect(r, radius, radius, screenBorderGlowPaint)
        canvas.drawRoundRect(r, radius, radius, screenBorderPaint)
    }

    private fun drawSelectionBox(canvas: Canvas, rect: RectF) {
        val radius = 10f * density
        canvas.drawRoundRect(rect, radius, radius, boxGlowPaint)
        canvas.drawRoundRect(rect, radius, radius, boxPaint)
    }

    /** Freeze [rect] (view px) so it stays drawn while the action menu is shown. */
    fun freezeSelection(rect: Rect) {
        frozenRect = RectF(rect)
        drawing = false
        postInvalidateOnAnimation()
    }

    /** Toggle capture mode: when true the overlay draws nothing (see [onDraw]). */
    fun setCaptureMode(enabled: Boolean) {
        captureMode = enabled
        postInvalidateOnAnimation()
    }

    private fun stepAndDrawParticles(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0.016f else ((now - lastFrameNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastFrameNs = now
        val t = now / 1_000_000_000f
        val w = width.toFloat()
        val h = height.toFloat()
        for (p in particles) {
            p.x += p.vx * dt
            p.y += p.vy * dt
            // Wrap around edges so the field stays populated.
            if (p.x < -p.radius) p.x = w + p.radius
            if (p.x > w + p.radius) p.x = -p.radius
            if (p.y < -p.radius) p.y = h + p.radius
            if (p.y > h + p.radius) p.y = -p.radius

            val glowAlpha = (0.35f + 0.65f * (0.5f + 0.5f * kotlin.math.sin(t * p.twinkle + p.phase)))
            val core = if (p.pink) 0xFF5FBF else 0x5B8CFF
            val a = (glowAlpha * 255).toInt().coerceIn(0, 255)
            // Outer glow.
            particlePaint.shader = RadialGradient(
                p.x, p.y, p.radius * 3.2f,
                Color.argb((a * 0.5f).toInt(), (core shr 16) and 0xFF, (core shr 8) and 0xFF, core and 0xFF),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(p.x, p.y, p.radius * 3.2f, particlePaint)
            // Bright core.
            particlePaint.shader = null
            particlePaint.color = Color.argb(a, 255, 255, 255)
            canvas.drawCircle(p.x, p.y, p.radius * 0.6f, particlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Once a selection is frozen (menu shown) or during capture, ignore touches.
        if (frozenRect != null || captureMode) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                drawing = true
                moved = false
                path.reset()
                path.moveTo(event.x, event.y)
                minX = event.x
                maxX = event.x
                minY = event.y
                maxY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                path.lineTo(event.x, event.y)
                minX = min(minX, event.x)
                maxX = max(maxX, event.x)
                minY = min(minY, event.y)
                maxY = max(maxY, event.y)
                if (max(maxX - minX, maxY - minY) > 8f * density) moved = true
            }
            MotionEvent.ACTION_UP -> {
                drawing = false
                val rect = Rect(minX.toInt(), minY.toInt(), maxX.toInt(), maxY.toInt())
                // Accept any real drag (even a thin line — the controller expands it
                // to a minimum band). A tap with no movement cancels.
                if (moved && max(rect.width(), rect.height()) > MIN_SIZE_PX) {
                    onSelection(rect)
                } else {
                    onCancel()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                drawing = false
                onCancel()
            }
        }
        return true
    }

    @Suppress("LongParameterList")
    private class Particle(
        var x: Float,
        var y: Float,
        val vx: Float,
        val vy: Float,
        val radius: Float,
        val pink: Boolean,
        val phase: Float,
        val twinkle: Float
    )

    private companion object {
        const val MIN_SIZE_PX = 24
    }
}
