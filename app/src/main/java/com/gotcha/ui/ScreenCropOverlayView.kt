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
import androidx.core.graphics.ColorUtils
import com.gotcha.service.AnnotatedEntity
import com.gotcha.ui.theme.OverlaySkin
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The full-screen Lens canvas.
 *
 * Two families of paint live here and they follow different rules:
 *
 * - **Decoration** — the dim, the edge glow, the particles and the pink→blue ink
 *   of the lasso itself — is deliberately *not* skinned. It is what makes Lens
 *   read as Lens rather than as a screenshot tool, and it is the same signature
 *   in every theme.
 * - **Functional chrome** — the screen border, the selection box, the entity
 *   boxes and their chips — comes from [colors], like every other overlay.
 *
 * The one deliberate exception is the hint text, which is drawn straight onto
 * the dim rather than onto a surface of ours. It stays white whatever the skin
 * is, for the same reason the dim stays dark: on a light skin `onSurface` is
 * near-black ink, and near-black ink on a 40% dark dim is unreadable.
 */
@SuppressLint("ViewConstructor")
@Suppress("TooManyFunctions", "LargeClass", "MaxLineLength")
class ScreenCropOverlayView(
    context: Context,
    private val colors: OverlaySkin,
    private val onSelection: (Rect) -> Unit,
    private val onCancel: () -> Unit,
    private val onReselectStart: () -> Unit = {},
    private val onAnnotatedEntitySelected: (prompt: String) -> Unit = {}
) : View(context) {

    private val density = resources.displayMetrics.density

    private val path = Path()
    private var minX = 0f
    private var minY = 0f
    private var maxX = 0f
    private var maxY = 0f
    private var drawing = false
    private var moved = false

    private var frozenRect: RectF? = null
    private var captureMode = false

    private var annotatedEntities: List<AnnotatedEntity> = emptyList()

    private val dimPaint = Paint().apply { color = Color.parseColor("#66101018") }
    private val edgeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val screenBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = ColorUtils.setAlphaComponent(colors.accent, BORDER_ALPHA)
    }
    private val screenBorderGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f * density
        color = ColorUtils.setAlphaComponent(colors.accent, GLOW_ALPHA)
        maskFilter = android.graphics.BlurMaskFilter(14f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = ColorUtils.setAlphaComponent(colors.accent, SELECTION_ALPHA)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f * density, 8f * density), 0f)
    }
    private val boxGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f * density
        color = ColorUtils.setAlphaComponent(colors.accent, GLOW_ALPHA)
        maskFilter = android.graphics.BlurMaskFilter(12f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    private val entityBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = colors.accent
    }
    private val entityChipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // Opaque, not the old #EE: a chip sits over an app we do not control, so
        // there is nothing behind it that showing through would help.
        color = colors.surface
    }
    private val entityChipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.onSurface
        textSize = colors.labelSp * density
        typeface = colors.sans
        textAlign = Paint.Align.LEFT
    }

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * One gradient per particle colour, built at unit radius around the origin and
     * moved into place with [glowMatrix] for each draw.
     *
     * The obvious way to draw these is a fresh [RadialGradient] per particle per
     * frame, which is what this used to do: 220 particles at refresh rate is well
     * over ten thousand shader allocations a second, all of it garbage. The
     * twinkle is applied through [Paint.setAlpha] instead of being baked into the
     * gradient's colours, which is what makes a shared shader possible at all.
     */
    private val pinkGlowShader by lazy { unitGlowShader(PARTICLE_PINK) }
    private val blueGlowShader by lazy { unitGlowShader(PARTICLE_BLUE) }
    private val glowMatrix = android.graphics.Matrix()

    /** Scratch, reused by [drawAnnotatedEntities] and [onTouchEvent]. */
    private val scratchLocation = IntArray(2)
    private val scratchRect = RectF()
    private val scratchChipRect = RectF()

    /** The bounds [pathPaint]'s gradient was last built for. */
    private var gradientMinX = Float.NaN
    private var gradientMinY = Float.NaN
    private var gradientMaxX = Float.NaN
    private var gradientMaxY = Float.NaN

    // White on purpose — see the class comment. This text has no surface of ours
    // under it, only the dim over somebody else's app.
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * density
        typeface = colors.sans
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 0f, Color.parseColor("#E081C0"))
    }

    private val particles = ArrayList<Particle>()
    private var lastFrameNs = 0L

    init {
        isFocusableInTouchMode = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /**
     * The functional chrome's colours, for tests. Every one of these must move
     * when the skin moves — that is the whole point of stage B, and the last
     * time nothing asserted it the overlay sat on a hardcoded cyan for five of
     * the six skins without anyone noticing.
     */
    @androidx.annotation.VisibleForTesting
    internal fun chromeColors(): IntArray = intArrayOf(
        screenBorderPaint.color,
        screenBorderGlowPaint.color,
        boxPaint.color,
        entityBoxPaint.color,
        entityChipBgPaint.color,
        entityChipTextPaint.color
    )

    /**
     * The decorative colours, for tests. These must *not* move with the skin:
     * the pink/blue signature is what makes Lens read as Lens, and the hint is
     * white because it is drawn on the dim rather than on a surface of ours.
     */
    @androidx.annotation.VisibleForTesting
    internal fun decorationColors(): IntArray = intArrayOf(dimPaint.color, hintPaint.color)

    fun setAnnotatedEntities(entities: List<AnnotatedEntity>) {
        this.annotatedEntities = entities
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildEdgeGlow(w, h)
        seedParticles(w, h)
    }

    private fun buildEdgeGlow(w: Int, h: Int) {
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
        val target = ((w * h) / (26000f * density)).toInt().coerceIn(40, 220)
        var placed = 0
        var guard = 0
        while (placed < target && guard < target * 12) {
            guard++
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            val ex = 1f - (2f * x / w - 1f).let { it * it }
            val ey = 1f - (2f * y / h - 1f).let { it * it }
            val centreness = (1f - ex) * (1f - ey)
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
        // Nothing is drawn in capture mode, so nothing needs scheduling either.
        // This used to keep the loop running at refresh rate while producing an
        // empty frame each time — during the screenshot, which is the one moment
        // the view should be getting out of the way. `setCaptureMode(false)`
        // restarts the loop.
        if (captureMode) return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), edgeGlowPaint)

        stepAndDrawParticles(canvas)
        drawScreenBorder(canvas)

        // Draw Auto-Annotated Bounding Boxes & Action Chips ONLY when NOT dragging a selection and NO selection is frozen
        if (!drawing && frozenRect == null) {
            drawAnnotatedEntities(canvas)
        }

        val frozen = frozenRect
        when {
            drawing && moved -> {
                // The gradient only depends on the selection bounds, which change
                // on a move event rather than on every frame.
                if (minX != gradientMinX || minY != gradientMinY ||
                    maxX != gradientMaxX || maxY != gradientMaxY
                ) {
                    gradientMinX = minX
                    gradientMinY = minY
                    gradientMaxX = maxX
                    gradientMaxY = maxY
                    pathPaint.shader = LinearGradient(
                        minX, minY, maxX, maxY,
                        STROKE_PINK, STROKE_BLUE,
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawPath(path, pathPaint)
                scratchRect.set(minX, minY, maxX, maxY)
                drawSelectionBox(canvas, scratchRect)
            }
            frozen != null -> drawSelectionBox(canvas, frozen)
            else -> if (annotatedEntities.isEmpty()) {
                canvas.drawText(
                    "Draw around anything • tap to cancel",
                    width / 2f,
                    height * 0.12f,
                    hintPaint
                )
            }
        }
        postInvalidateOnAnimation()
    }

    private fun drawAnnotatedEntities(canvas: Canvas) {
        getLocationOnScreen(scratchLocation)
        val viewOffsetX = scratchLocation[0].toFloat()
        val viewOffsetY = scratchLocation[1].toFloat()

        for (item in annotatedEntities) {
            val bounds = item.boundsOnScreen
            val rectF = scratchRect
            rectF.set(
                bounds.left.toFloat() - viewOffsetX,
                bounds.top.toFloat() - viewOffsetY,
                bounds.right.toFloat() - viewOffsetX,
                bounds.bottom.toFloat() - viewOffsetY
            )

            // Draw bounding box
            val radius = colors.buttonRadiusDp * density
            canvas.drawRoundRect(rectF, radius, radius, entityBoxPaint)

            // Draw primary action chip pill anchored above the box
            val action = item.entity.primaryAction ?: continue
            val labelStr = action.label
            val textWidth = entityChipTextPaint.measureText(labelStr)
            val chipW = textWidth + 16f * density
            val chipH = 24f * density

            val chipLeft = (rectF.left).coerceIn(8f * density, width - chipW - 8f * density)
            val chipTop = (rectF.top - chipH - 4f * density).coerceAtLeast(8f * density)
            val chipRect = scratchChipRect
            chipRect.set(chipLeft, chipTop, chipLeft + chipW, chipTop + chipH)

            canvas.drawRoundRect(chipRect, radius, radius, entityChipBgPaint)
            canvas.drawRoundRect(chipRect, radius, radius, entityBoxPaint)
            // Centred off the font's own metrics rather than a fixed baseline:
            // the label size now comes from the skin's type scale, so a hardcoded
            // offset would drift as soon as that scale moved.
            val fm = entityChipTextPaint.fontMetrics
            val baseline = chipRect.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(labelStr, chipLeft + 8f * density, baseline, entityChipTextPaint)
        }
    }

    private fun drawScreenBorder(canvas: Canvas) {
        val inset = screenBorderGlowPaint.strokeWidth / 2f + 1f
        val r = RectF(inset, inset, width - inset, height - inset)
        val radius = colors.cardRadiusDp * density
        canvas.drawRoundRect(r, radius, radius, screenBorderGlowPaint)
        canvas.drawRoundRect(r, radius, radius, screenBorderPaint)
    }

    private fun drawSelectionBox(canvas: Canvas, rect: RectF) {
        val radius = colors.buttonRadiusDp * density
        canvas.drawRoundRect(rect, radius, radius, boxGlowPaint)
        canvas.drawRoundRect(rect, radius, radius, boxPaint)
    }

    fun freezeSelection(rect: Rect) {
        frozenRect = RectF(rect)
        drawing = false
        postInvalidateOnAnimation()
    }

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
            if (p.x < -p.radius) p.x = w + p.radius
            if (p.x > w + p.radius) p.x = -p.radius
            if (p.y < -p.radius) p.y = h + p.radius
            if (p.y > h + p.radius) p.y = -p.radius

            val glowAlpha = (0.35f + 0.65f * (0.5f + 0.5f * kotlin.math.sin(t * p.twinkle + p.phase)))
            val a = (glowAlpha * 255).toInt().coerceIn(0, 255)

            val glowRadius = p.radius * GLOW_RADIUS_SCALE
            glowMatrix.setScale(glowRadius, glowRadius)
            glowMatrix.postTranslate(p.x, p.y)
            val shader = if (p.pink) pinkGlowShader else blueGlowShader
            shader.setLocalMatrix(glowMatrix)
            particlePaint.shader = shader
            // Modulates the shader rather than replacing its colours, which is
            // what lets every particle of a colour share one gradient.
            particlePaint.color = Color.WHITE
            particlePaint.alpha = a
            canvas.drawCircle(p.x, p.y, glowRadius, particlePaint)

            particlePaint.shader = null
            particlePaint.color = Color.argb(a, 255, 255, 255)
            canvas.drawCircle(p.x, p.y, p.radius * 0.6f, particlePaint)
        }
    }

    /**
     * A glow gradient at unit radius around the origin, to be positioned by a
     * local matrix. Half alpha at the centre, matching what the per-particle
     * gradients used to bake in before [Paint.setAlpha] took over the twinkle.
     */
    private fun unitGlowShader(core: Int): RadialGradient {
        val centre = Color.argb(
            GLOW_CENTRE_ALPHA,
            (core shr 16) and 0xFF,
            (core shr 8) and 0xFF,
            core and 0xFF
        )
        return RadialGradient(0f, 0f, 1f, centre, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (captureMode) return false

        // Check if touch hits an annotated entity box/chip
        if (event.action == MotionEvent.ACTION_UP && !moved) {
            getLocationOnScreen(scratchLocation)
            val viewOffsetX = scratchLocation[0].toFloat()
            val viewOffsetY = scratchLocation[1].toFloat()

            val touchX = event.x
            val touchY = event.y
            val chipH = 24f * density
            val expandedBounds = scratchRect
            for (item in annotatedEntities) {
                val bounds = item.boundsOnScreen
                expandedBounds.set(
                    bounds.left.toFloat() - viewOffsetX - 16f,
                    bounds.top.toFloat() - viewOffsetY - chipH - 16f,
                    bounds.right.toFloat() - viewOffsetX + 16f,
                    bounds.bottom.toFloat() - viewOffsetY + 16f
                )
                if (expandedBounds.contains(touchX, touchY)) {
                    val prompt = item.entity.primaryAction?.prompt
                    if (prompt != null) {
                        onAnnotatedEntitySelected(prompt)
                        return true
                    }
                }
            }
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (frozenRect != null) {
                    frozenRect = null
                    onReselectStart()
                }
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
        const val PARTICLE_PINK = 0xFF5FBF
        const val PARTICLE_BLUE = 0x5B8CFF
        const val GLOW_RADIUS_SCALE = 3.2f
        const val GLOW_CENTRE_ALPHA = 128
        const val STROKE_PINK = 0xFFFF6FC0.toInt()
        const val STROKE_BLUE = 0xFF5B8CFF.toInt()

        /** Alphas the accent is drawn at, matching what the old fixed colours carried. */
        const val BORDER_ALPHA = 0xB0
        const val SELECTION_ALPHA = 0xD0
        const val GLOW_ALPHA = 0x55
    }
}
