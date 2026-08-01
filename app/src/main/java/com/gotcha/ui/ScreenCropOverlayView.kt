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
import com.gotcha.service.SmartActionDetector
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
    private val onAnnotatedEntitySelected: (prompt: String) -> Unit = {},
    private val onAnnotatedGroupSelected: (AnnotatedEntity) -> Unit = {}
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

    /**
     * The hairline sitting exactly on the cut-out's edge.
     *
     * Solid and thin, where it used to be a fat dashed line with a blurred glow
     * behind it. Since the dim gained a hole, the edge of that hole already says
     * where the selection is; the dashes were a second, louder voice saying the
     * same thing. What is left just makes the boundary crisp instead of relying
     * on a soft luminance step.
     */
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ColorUtils.setAlphaComponent(colors.accent, SELECTION_ALPHA)
    }

    /** Corner brackets — the crop-UI idiom, and the part that reads as "capture". */
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        color = colors.accent
        strokeCap = Paint.Cap.ROUND
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

    /** Where each chip was actually drawn, so [onTouchEvent] can hit-test what is on screen. */
    private class ChipHit {
        val rect = RectF()
        var item: AnnotatedEntity? = null
    }

    private val chipHits = ArrayList<ChipHit>()
    private var drawnChipCount = 0

    /** Scratch, reused by [drawAnnotatedEntities] and [onTouchEvent]. */
    private val scratchLocation = IntArray(2)
    private val scratchRect = RectF()
    private val scratchChipRect = RectF()

    /** Held separately from [scratchRect], which [drawSelectionBox] is using. */
    private val scratchDim = RectF()

    /** Reused by [drawCornerBrackets], which runs every frame. */
    private val bracketPath = Path()
    private val scratchArc = RectF()

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

    /** Where chips actually landed on the last frame, for tests. */
    @androidx.annotation.VisibleForTesting
    internal fun drawnChipRects(): List<RectF> = (0 until drawnChipCount).map { RectF(chipHits[it].rect) }

    fun setAnnotatedEntities(entities: List<AnnotatedEntity>) {
        this.annotatedEntities = entities
        drawnChipCount = 0
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
        drawDim(canvas)
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

    /**
     * The dim, with the current selection cut out of it.
     *
     * It used to be one rect over the whole screen, selection included, which
     * meant that after drawing a region nothing on screen was actually
     * highlighted — the thing you had just picked was exactly as dark as the
     * thing you hadn't. Four rects around the hole is also less fill than one
     * rect over everything, so the highlight is cheaper than the flat dim was.
     */
    private fun drawDim(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val hole = currentSelection()
        if (hole == null) {
            canvas.drawRect(0f, 0f, w, h, dimPaint)
            return
        }
        val left = hole.left.coerceIn(0f, w)
        val top = hole.top.coerceIn(0f, h)
        val right = hole.right.coerceIn(0f, w)
        val bottom = hole.bottom.coerceIn(0f, h)
        canvas.drawRect(0f, 0f, w, top, dimPaint)
        canvas.drawRect(0f, bottom, w, h, dimPaint)
        canvas.drawRect(0f, top, left, bottom, dimPaint)
        canvas.drawRect(right, top, w, bottom, dimPaint)
    }

    /**
     * The region the user has picked, or null while there is nothing to
     * highlight. Writes into [scratchDim] rather than allocating, because this
     * runs every frame.
     */
    private fun currentSelection(): RectF? {
        frozenRect?.let { return it }
        if (drawing && moved) {
            scratchDim.set(minX, minY, maxX, maxY)
            return scratchDim
        }
        return null
    }

    /**
     * Boxes and chips for whatever the detector decided is worth showing.
     *
     * Two rules keep this legible on a busy screen. Only the top-ranked
     * annotation carries its full verb — "🌐 Open: github.com/…" — because the
     * verb is identical on every chip of a type and it is what made them wide
     * enough to bury the app underneath. And a chip that cannot find a clear spot
     * is not drawn at all: its box still marks the thing, which is better than a
     * label stacked on another label.
     */
    private fun drawAnnotatedEntities(canvas: Canvas) {
        getLocationOnScreen(scratchLocation)
        val viewOffsetX = scratchLocation[0].toFloat()
        val viewOffsetY = scratchLocation[1].toFloat()
        val radius = colors.buttonRadiusDp * density
        val padding = CHIP_PADDING_DP * density
        drawnChipCount = 0

        for ((index, item) in annotatedEntities.withIndex()) {
            val bounds = item.boundsOnScreen
            val rectF = scratchRect
            rectF.set(
                bounds.left.toFloat() - viewOffsetX,
                bounds.top.toFloat() - viewOffsetY,
                bounds.right.toFloat() - viewOffsetX,
                bounds.bottom.toFloat() - viewOffsetY
            )
            canvas.drawRoundRect(rectF, radius, radius, entityBoxPaint)

            val action = item.entity.primaryAction ?: continue
            val labelStr = if (index == 0 && item.groupCount == 1) {
                action.label
            } else {
                SmartActionDetector.chipLabel(item.entity, item.groupCount)
            }
            val chipW = entityChipTextPaint.measureText(labelStr) + padding * 2f
            val chipH = CHIP_HEIGHT_DP * density
            if (!placeChip(rectF, chipW, chipH)) continue

            val chipRect = scratchChipRect
            canvas.drawRoundRect(chipRect, radius, radius, entityChipBgPaint)
            canvas.drawRoundRect(chipRect, radius, radius, entityBoxPaint)
            // Centred off the font's own metrics rather than a fixed baseline:
            // the label size now comes from the skin's type scale, so a hardcoded
            // offset would drift as soon as that scale moved.
            val fm = entityChipTextPaint.fontMetrics
            val baseline = chipRect.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(labelStr, chipRect.left + padding, baseline, entityChipTextPaint)
            recordChip(chipRect, item)
        }
    }

    /**
     * Find a clear spot for a [w]×[h] chip near [box], writing it into
     * [scratchChipRect]. Above the box first, then below; false when both are
     * taken or off-screen, which the caller reads as "draw no label".
     */
    private fun placeChip(box: RectF, w: Float, h: Float): Boolean {
        val gap = CHIP_GAP_DP * density
        val margin = CHIP_MARGIN_DP * density
        val left = box.left.coerceIn(margin, (width - w - margin).coerceAtLeast(margin))
        val candidates = floatArrayOf(box.top - h - gap, box.bottom + gap)
        for (top in candidates) {
            if (top < margin || top + h > height - margin) continue
            scratchChipRect.set(left, top, left + w, top + h)
            var clear = true
            for (i in 0 until drawnChipCount) {
                if (RectF.intersects(chipHits[i].rect, scratchChipRect)) {
                    clear = false
                    break
                }
            }
            if (clear) return true
        }
        return false
    }

    /** Remember a drawn chip so a tap can find it. Pooled — this runs every frame. */
    private fun recordChip(rect: RectF, item: AnnotatedEntity) {
        while (chipHits.size <= drawnChipCount) chipHits.add(ChipHit())
        val hit = chipHits[drawnChipCount]
        hit.rect.set(rect)
        hit.item = item
        drawnChipCount++
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
        canvas.drawRoundRect(rect, radius, radius, boxPaint)
        drawCornerBrackets(canvas, rect)
    }

    /**
     * Four brackets that trace the selection's own rounded corners.
     *
     * The straightforward version — eight straight stubs inset from each corner
     * — does not work: the stubs sit *near* the rounded corner without following
     * it, so they read as four floating marks rather than as a frame. Each
     * bracket here is a line, the corner's actual arc, then another line, so it
     * lies exactly on the boundary the hairline already draws.
     */
    private fun drawCornerBrackets(canvas: Canvas, rect: RectF) {
        val radius = colors.buttonRadiusDp * density
        // Leave at least a small gap between opposite brackets, so a tight
        // selection does not end up with a solid outline.
        val room = (min(rect.width(), rect.height()) / 2f - radius) * BRACKET_MAX_FRACTION
        val len = min(BRACKET_LEN_DP * density, room)
        if (len <= 0f) return

        val l = rect.left
        val t = rect.top
        val r = rect.right
        val b = rect.bottom
        val d = radius * 2f

        bracketPath.reset()
        // top-left
        scratchArc.set(l, t, l + d, t + d)
        bracketPath.moveTo(l, t + radius + len)
        bracketPath.lineTo(l, t + radius)
        bracketPath.arcTo(scratchArc, 180f, 90f)
        bracketPath.lineTo(l + radius + len, t)
        // top-right
        scratchArc.set(r - d, t, r, t + d)
        bracketPath.moveTo(r - radius - len, t)
        bracketPath.lineTo(r - radius, t)
        bracketPath.arcTo(scratchArc, 270f, 90f)
        bracketPath.lineTo(r, t + radius + len)
        // bottom-right
        scratchArc.set(r - d, b - d, r, b)
        bracketPath.moveTo(r, b - radius - len)
        bracketPath.lineTo(r, b - radius)
        bracketPath.arcTo(scratchArc, 0f, 90f)
        bracketPath.lineTo(r - radius - len, b)
        // bottom-left
        scratchArc.set(l, b - d, l + d, b)
        bracketPath.moveTo(l + radius + len, b)
        bracketPath.lineTo(l + radius, b)
        bracketPath.arcTo(scratchArc, 90f, 90f)
        bracketPath.lineTo(l, b - radius - len)

        canvas.drawPath(bracketPath, bracketPaint)
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

    /**
     * Fire the annotation under ([touchX], [touchY]), if any.
     *
     * Chips are tested before boxes, and among boxes the *smallest* containing
     * one wins. Accessibility bounds nest — one entity's box can wholly contain
     * another's — and this used to take whichever came first in list order, so a
     * tap on an inner chip was answered by the container around it.
     */
    private fun selectAnnotationAt(touchX: Float, touchY: Float): Boolean {
        // Only `frozenRect`, deliberately not `drawing`: ACTION_DOWN sets that
        // flag before the ACTION_UP that gets us here, so testing it would reject
        // every tap. A tap is a press with no movement, and the chips it is
        // hit-testing against are the ones from the last frame before the press —
        // which is exactly what the user was looking at when they aimed.
        if (frozenRect != null) return false
        for (i in 0 until drawnChipCount) {
            val hit = chipHits[i]
            if (!hit.rect.contains(touchX, touchY)) continue
            val item = hit.item ?: continue
            if (fireAnnotation(item)) return true
        }

        getLocationOnScreen(scratchLocation)
        val viewOffsetX = scratchLocation[0].toFloat()
        val viewOffsetY = scratchLocation[1].toFloat()
        val slop = ENTITY_TOUCH_SLOP_DP * density
        val expanded = scratchRect
        var best: AnnotatedEntity? = null
        var bestArea = Float.MAX_VALUE

        for (item in annotatedEntities) {
            val bounds = item.boundsOnScreen
            expanded.set(
                bounds.left.toFloat() - viewOffsetX - slop,
                bounds.top.toFloat() - viewOffsetY - slop,
                bounds.right.toFloat() - viewOffsetX + slop,
                bounds.bottom.toFloat() - viewOffsetY + slop
            )
            if (!expanded.contains(touchX, touchY)) continue
            val area = expanded.width() * expanded.height()
            if (area < bestArea) {
                bestArea = area
                best = item
            }
        }

        return fireAnnotation(best ?: return false)
    }

    /**
     * Act on [item]: run its action, or open the group when the chip stands for
     * more than one detection. "12 prices" that converts a single price is a
     * chip lying about what it is.
     */
    private fun fireAnnotation(item: AnnotatedEntity): Boolean {
        if (item.groupCount > 1) {
            onAnnotatedGroupSelected(item)
            return true
        }
        val prompt = item.entity.primaryAction?.prompt ?: return false
        onAnnotatedEntitySelected(prompt)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (captureMode) return false

        // Check if touch hits an annotated entity box/chip
        if (event.action == MotionEvent.ACTION_UP && !moved) {
            if (selectAnnotationAt(event.x, event.y)) return true
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
        const val BRACKET_LEN_DP = 20f
        const val BRACKET_MAX_FRACTION = 0.6f
        const val CHIP_HEIGHT_DP = 24f
        const val CHIP_PADDING_DP = 8f
        const val CHIP_GAP_DP = 4f
        const val CHIP_MARGIN_DP = 8f
        const val ENTITY_TOUCH_SLOP_DP = 8f
    }
}
