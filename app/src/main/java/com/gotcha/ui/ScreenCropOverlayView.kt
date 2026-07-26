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
import com.gotcha.service.AnnotatedEntity
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

@SuppressLint("ViewConstructor")
@Suppress("TooManyFunctions", "LargeClass", "MaxLineLength")
class ScreenCropOverlayView(
    context: Context,
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
        color = Color.parseColor("#B0568CD8")
    }
    private val screenBorderGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f * density
        color = Color.parseColor("#55A0459E")
        maskFilter = android.graphics.BlurMaskFilter(14f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

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

    private val entityBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.parseColor("#FF00E5FF")
    }
    private val entityChipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#EE1E293B")
    }
    private val entityChipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        textAlign = Paint.Align.LEFT
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
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

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
        if (captureMode) {
            postInvalidateOnAnimation()
            return
        }
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
                pathPaint.shader = LinearGradient(
                    minX, minY, maxX, maxY,
                    Color.parseColor("#FF6FC0"), Color.parseColor("#5B8CFF"),
                    Shader.TileMode.CLAMP
                )
                canvas.drawPath(path, pathPaint)
                drawSelectionBox(canvas, RectF(minX, minY, maxX, maxY))
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
        val location = IntArray(2)
        getLocationOnScreen(location)
        val viewOffsetX = location[0].toFloat()
        val viewOffsetY = location[1].toFloat()

        for (item in annotatedEntities) {
            val bounds = item.boundsOnScreen
            val rectF = RectF(
                bounds.left.toFloat() - viewOffsetX,
                bounds.top.toFloat() - viewOffsetY,
                bounds.right.toFloat() - viewOffsetX,
                bounds.bottom.toFloat() - viewOffsetY
            )

            // Draw bounding box
            val radius = 8f * density
            canvas.drawRoundRect(rectF, radius, radius, entityBoxPaint)

            // Draw primary action chip pill anchored above the box
            val action = item.entity.primaryAction ?: continue
            val labelStr = action.label
            val textWidth = entityChipTextPaint.measureText(labelStr)
            val chipW = textWidth + 16f * density
            val chipH = 24f * density

            val chipLeft = (rectF.left).coerceIn(8f * density, width - chipW - 8f * density)
            val chipTop = (rectF.top - chipH - 4f * density).coerceAtLeast(8f * density)
            val chipRect = RectF(chipLeft, chipTop, chipLeft + chipW, chipTop + chipH)

            canvas.drawRoundRect(chipRect, 12f * density, 12f * density, entityChipBgPaint)
            canvas.drawRoundRect(chipRect, 12f * density, 12f * density, entityBoxPaint)
            canvas.drawText(labelStr, chipLeft + 8f * density, chipTop + 16f * density, entityChipTextPaint)
        }
    }

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
            val core = if (p.pink) 0xFF5FBF else 0x5B8CFF
            val a = (glowAlpha * 255).toInt().coerceIn(0, 255)
            particlePaint.shader = RadialGradient(
                p.x, p.y, p.radius * 3.2f,
                Color.argb((a * 0.5f).toInt(), (core shr 16) and 0xFF, (core shr 8) and 0xFF, core and 0xFF),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(p.x, p.y, p.radius * 3.2f, particlePaint)
            particlePaint.shader = null
            particlePaint.color = Color.argb(a, 255, 255, 255)
            canvas.drawCircle(p.x, p.y, p.radius * 0.6f, particlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (captureMode) return false

        // Check if touch hits an annotated entity box/chip
        if (event.action == MotionEvent.ACTION_UP && !moved) {
            val location = IntArray(2)
            getLocationOnScreen(location)
            val viewOffsetX = location[0].toFloat()
            val viewOffsetY = location[1].toFloat()

            val touchX = event.x
            val touchY = event.y
            for (item in annotatedEntities) {
                val bounds = item.boundsOnScreen
                val rectF = RectF(
                    bounds.left.toFloat() - viewOffsetX,
                    bounds.top.toFloat() - viewOffsetY,
                    bounds.right.toFloat() - viewOffsetX,
                    bounds.bottom.toFloat() - viewOffsetY
                )
                val chipH = 24f * density
                val expandedBounds = RectF(
                    rectF.left - 16f,
                    rectF.top - chipH - 16f,
                    rectF.right + 16f,
                    rectF.bottom + 16f
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
    }
}
