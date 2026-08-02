package com.gotcha.marketing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import com.gotcha.R

/**
 * Renders a [PosterContent] + [PosterStats] into a fixed-size 4:5 Instagram
 * Bitmap entirely on-device — a deterministic Canvas painter, deliberately not
 * Compose: it needs no window, no recomposer, and renders in one synchronous
 * pass, which is what makes it trivially unit-testable (and what the
 * "predefined functions convert info into graphics" design calls for).
 *
 * The design reuses the app's brand ramp and logo — no image-generation API.
 */
object PosterRenderer {

    /** Instagram feed portrait aspect ratio. */
    const val WIDTH_PX = 1080
    const val HEIGHT_PX = 1350

    /** The app's brand ramp, sampled from the launcher icon. */
    private val brandColors = intArrayOf(
        0xFF7E1F88.toInt(), // BrandViolet
        0xFFC82F92.toInt(), // BrandMagenta
        0xFFF04B8B.toInt(), // BrandRose
        0xFFFC6A85.toInt() // BrandCoral
    )

    /**
     * Renders the poster to a [Bitmap]. Pure CPU drawing — safe on any thread.
     */
    fun render(
        context: Context,
        content: PosterContent,
        stats: PosterStats,
        screenshot: Bitmap? = null
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val typeface = ResourcesCompat.getFont(context, R.font.figtree)
        val logo = runCatching {
            android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.gotcha_logo)
        }.getOrNull()
        PosterPainter(typeface, brandColors, logo).draw(canvas, content, stats, screenshot)
        return bitmap
    }

    /**
     * The actual drawing pass, isolated so it can be exercised directly by
     * tests without a Bitmap/Context round-trip.
     */
    internal class PosterPainter(
        private val typeface: Typeface?,
        private val colors: IntArray = brandColors,
        private val logo: Bitmap? = null
    ) {

        /** Vertical geometry of a laid-out poster, for overflow assertions. */
        internal data class LayoutMetrics(
            val lastElementBottom: Float,
            val ctaTop: Float,
            val footerBottom: Float
        )

        companion object {
            const val LOGO_SIZE = 160f
            const val LOGO_TOP = 90f
            const val CTA_PILL_H = 104f
            const val STAT_CHIP_H = 88f
        }

        /**
         * Computes the same vertical progression [draw] paints, without drawing.
         * Lets tests assert the content block never collides with the pinned
         * CTA/footer and never runs off the canvas.
         */
        internal fun measureLayout(content: PosterContent): LayoutMetrics {
            val x0 = 72f
            val x1 = WIDTH_PX - 72f
            var y = LOGO_TOP + LOGO_SIZE + 120f

            y += wrappedHeight(
                content.headline.ifBlank { "I asked Gotcha to handle it." },
                RectF(x0, y, x1, y + 240f),
                textPaint(78f, Color.WHITE, bold = true)
            )
            y += 40f
            y += wrappedHeight(
                content.subheadline,
                RectF(x0, y, x1, y + 140f),
                textPaint(50f, Color.WHITE)
            )
            y += 46f

            val ctaBandTop = HEIGHT_PX - 320f - CTA_PILL_H - 120f
            when {
                content.template == "recap" && content.achievements.isNotEmpty() -> {
                    val rows = achievementsThatFit(content.achievements, y, ctaBandTop)
                    y += rows * (52f + 24f)
                    y -= 24f
                    y += 46f
                }
                content.body.isNotBlank() -> {
                    y += wrappedHeight(content.body, RectF(x0, y, x1, y + 120f), textPaint(44f, Color.WHITE))
                    y += 34f
                }
            }
            y += STAT_CHIP_H

            val ctaTop = HEIGHT_PX - 320f
            val footerBottom = ctaTop + CTA_PILL_H + 132f
            return LayoutMetrics(
                lastElementBottom = y,
                ctaTop = ctaTop,
                footerBottom = footerBottom
            )
        }

        /** Number of recap rows that fit between [startY] and the CTA band. */
        private fun achievementsThatFit(list: List<String>, startY: Float, maxBottom: Float): Int {
            val rowHeight = 52f
            val lineGap = 24f
            val available = (maxBottom - startY - 20f).coerceAtLeast(40f)
            val maxRows = (available / (rowHeight + lineGap)).toInt().coerceAtLeast(1)
            return list.size.coerceAtMost(maxRows.coerceAtMost(5))
        }

        @Suppress("LongMethod")
        fun draw(
            canvas: Canvas,
            content: PosterContent,
            stats: PosterStats,
            screenshot: Bitmap?
        ) {
            drawBackground(canvas)

            val x0 = 72f
            val x1 = WIDTH_PX - 72f

            // Brand lockup: logo + wordmark.
            val logoSize = LOGO_SIZE
            val logoTop = LOGO_TOP
            canvas.drawCircle(
                WIDTH_PX / 2f,
                logoTop + logoSize / 2f,
                logoSize / 2f + 8f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            )
            if (logo != null) {
                canvas.drawBitmap(
                    logo,
                    null,
                    RectF(WIDTH_PX / 2f - logoSize / 2f, logoTop, WIDTH_PX / 2f + logoSize / 2f, logoTop + logoSize),
                    Paint(Paint.ANTI_ALIAS_FLAG)
                )
            }
            canvas.drawText(
                "GOTCHA",
                WIDTH_PX / 2f,
                logoTop + logoSize + 52f,
                textPaint(64f, Color.WHITE, letterSpacing = 20f, bold = true, center = true)
            )

            var y = logoTop + logoSize + 120f

            // Headline.
            y += drawWrapped(
                canvas,
                content.headline.ifBlank { "I asked Gotcha to handle it." },
                RectF(x0, y, x1, y + 240f),
                textPaint(78f, Color.WHITE, bold = true),
                align = Layout.Alignment.ALIGN_CENTER
            )
            y += 40f

            // Subheadline.
            y += drawWrapped(
                canvas,
                content.subheadline,
                RectF(x0, y, x1, y + 140f),
                textPaint(50f, Color.argb(235, 255, 255, 255)),
                align = Layout.Alignment.ALIGN_CENTER
            )
            y += 46f

            // Body / screenshot / achievements. The CTA band starts 320px up
            // from the bottom, so content above it must stop before that.
            val ctaBandTop = HEIGHT_PX - 320f - CTA_PILL_H - 120f
            when {
                content.template == "recap" && content.achievements.isNotEmpty() -> {
                    y = drawAchievements(canvas, content.achievements, y, ctaBandTop)
                    y += 46f
                }
                screenshot != null -> {
                    val sw = 460f
                    val sh = 345f
                    val left = (WIDTH_PX - sw) / 2f
                    canvas.drawRoundRect(
                        RectF(left - 12f, y - 12f, left + sw + 12f, y + sh + 12f),
                        40f,
                        40f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 255, 255, 255) }
                    )
                    canvas.save()
                    canvas.clipPath(
                        android.graphics.Path().apply {
                            addRoundRect(
                                RectF(left, y, left + sw, y + sh),
                                28f,
                                28f,
                                android.graphics.Path.Direction.CW
                            )
                        }
                    )
                    canvas.drawBitmap(screenshot, null, RectF(left, y, left + sw, y + sh), Paint(Paint.ANTI_ALIAS_FLAG))
                    canvas.restore()
                    y += sh + 40f
                }
                content.body.isNotBlank() -> {
                    y += drawWrapped(
                        canvas,
                        content.body,
                        RectF(x0, y, x1, y + 120f),
                        textPaint(44f, Color.argb(225, 255, 255, 255)),
                        align = Layout.Alignment.ALIGN_CENTER
                    )
                    y += 34f
                }
            }

            // Stat chips.
            y += drawStatChips(canvas, stats, y)

            // CTA + hashtags + footer pinned near the bottom.
            val cta = content.callToAction.ifBlank { "Meet Gotcha." }
            val ctaPaint = textPaint(54f, Color.WHITE, bold = true, center = true)
            val ctaWidth = ctaPaint.measureText(cta)
            val pillW = (ctaWidth + 170f).coerceAtLeast(380f)
            val pillH = CTA_PILL_H
            val pillLeft = (WIDTH_PX - pillW) / 2f
            val pillTop = HEIGHT_PX - 320f
            canvas.drawRoundRect(
                RectF(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH),
                pillH / 2f,
                pillH / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255) }
            )
            canvas.drawText(cta, WIDTH_PX / 2f, pillTop + pillH / 2f + 20f, ctaPaint)

            val hashtagPaint = textPaint(42f, Color.argb(230, 255, 255, 255), center = true)
            canvas.drawText(
                content.hashtags.joinToString(" "),
                WIDTH_PX / 2f,
                pillTop + pillH + 66f,
                hashtagPaint
            )
            canvas.drawText(
                "Built with ${stats.model.ifBlank { "an AI agent" }} · on Android",
                WIDTH_PX / 2f,
                pillTop + pillH + 132f,
                textPaint(36f, Color.argb(180, 255, 255, 255), center = true)
            )
        }

        private fun drawBackground(canvas: Canvas) {
            val bgShader = LinearGradient(
                0f,
                0f,
                0f,
                HEIGHT_PX.toFloat(),
                colors,
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(
                0f,
                0f,
                WIDTH_PX.toFloat(),
                HEIGHT_PX.toFloat(),
                Paint().apply { shader = bgShader }
            )
            // Soft radial highlight top-left.
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    WIDTH_PX * 0.2f, HEIGHT_PX * 0.1f, WIDTH_PX * 0.7f,
                    intArrayOf(Color.argb(40, 255, 255, 255), Color.TRANSPARENT), null,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, WIDTH_PX.toFloat(), HEIGHT_PX.toFloat(), glow)
        }

        /**
         * Renders recap achievements as compact single-line bullets sized to
         * fit the vertical budget between [startY] and [maxBottom] (the CTA
         * band). Shrinks the font and drops the tail until they fit — a recap
         * must never push content over the pinned CTA below it.
         */
        private fun drawAchievements(
            canvas: Canvas,
            achievements: List<String>,
            startY: Float,
            maxBottom: Float
        ): Float {
            val lineGap = 24f
            val rowHeight = 52f
            val available = (maxBottom - startY - 20f).coerceAtLeast(40f)
            val maxRows = (available / (rowHeight + lineGap)).toInt().coerceAtLeast(1)
            val rows = achievements.take(maxRows.coerceAtMost(5))
            var font = 44f
            while (font > 24f) {
                val paint = textPaint(font, Color.WHITE)
                if (rows.all { paint.measureText(it) <= WIDTH_PX - 360f }) break
                font -= 2f
            }
            val bulletPaint = textPaint(font, Color.WHITE, bold = true)
            val linePaint = textPaint(font, Color.argb(240, 255, 255, 255))
            var y = startY
            rows.forEach { line ->
                val label = TextUtils.ellipsize(line, linePaint, WIDTH_PX - 360f, TextUtils.TruncateAt.END)
                    .toString()
                canvas.drawText("✓", 130f, y + rowHeight - 8f, bulletPaint)
                canvas.drawText(label, 200f, y + rowHeight - 8f, linePaint)
                y += rowHeight + lineGap
            }
            return y - lineGap
        }

        private fun drawStatChips(canvas: Canvas, stats: PosterStats, startY: Float): Float {
            val chips = listOf(
                "⚡ ${stats.durationDisplay}",
                "🧰 ${stats.toolCount} tool" + if (stats.toolCount == 1) "" else "s",
                "🤖 ${stats.model.take(14)}"
            )
            val h = 88f
            val gap = 24f
            val sideMargin = 72f
            val maxChipW = (WIDTH_PX - 2 * sideMargin - 2 * gap) / 3f
            // Sizing loop: start at the target size and shrink the font until
            // every chip label (emoji included) fits its pill. This makes the
            // row immune to font-metrics drift — no clipping, ever.
            var chipPaint = textPaint(38f, Color.WHITE, center = true)
            var chipW = maxChipW
            while (chipW > 140f && chips.any { chipPaint.measureText(it) > chipW - 40f }) {
                chipPaint = textPaint(chipPaint.textSize - 2f, Color.WHITE, center = true)
                chipW = maxChipW
            }
            val rowWidth = chipW * chips.size + gap * (chips.size - 1)
            var x = (WIDTH_PX - rowWidth) / 2f
            chips.forEach { chip ->
                canvas.drawRoundRect(
                    RectF(x, startY, x + chipW, startY + h),
                    h / 2f,
                    h / 2f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
                )
                canvas.drawText(chip, x + chipW / 2f, startY + h / 2f + 13f, chipPaint)
                x += chipW + gap
            }
            return startY + h
        }

        /** Draws wrapped text and returns the number of lines * line height consumed. */
        private fun drawWrapped(
            canvas: Canvas,
            text: String,
            bounds: RectF,
            paint: TextPaint,
            align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL
        ): Float {
            if (text.isBlank()) return 0f
            // StaticLayout aligns lines internally within the given width, so
            // the paint must be LEFT-aligned (CENTER mis-anchors every line) and
            // the canvas is translated only to the bounds' left edge.
            val layout = StaticLayout.Builder.obtain(
                text,
                0,
                text.length,
                paint,
                bounds.width().toInt()
            )
                .setAlignment(align)
                .setMaxLines((bounds.height() / paint.textSize).toInt().coerceAtLeast(1))
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            canvas.save()
            canvas.translate(bounds.left, bounds.top)
            layout.draw(canvas)
            canvas.restore()
            return layout.height.toFloat()
        }

        /** Wrapped text height without drawing (mirrors [drawWrapped]). */
        private fun wrappedHeight(text: String, bounds: RectF, paint: TextPaint): Float {
            if (text.isBlank()) return 0f
            val layout = StaticLayout.Builder.obtain(
                text,
                0,
                text.length,
                paint,
                bounds.width().toInt()
            )
                .setMaxLines((bounds.height() / paint.textSize).toInt().coerceAtLeast(1))
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            return layout.height.toFloat()
        }

        /**
         * LEFT-aligned by default because StaticLayout requires it; pass
         * [center] = true only for direct [Canvas.drawText] calls anchored at
         * the canvas midpoint.
         */
        private fun textPaint(
            size: Float,
            color: Int,
            letterSpacing: Float = 0f,
            bold: Boolean = false,
            center: Boolean = false
        ): TextPaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                this.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
                this.letterSpacing = letterSpacing
                if (bold) {
                    typeface = if (typeface != null) {
                        Typeface.create(typeface, Typeface.BOLD)
                    } else {
                        Typeface.DEFAULT_BOLD
                    }
                } else {
                    typeface = typeface ?: Typeface.DEFAULT
                }
            }
    }
}
