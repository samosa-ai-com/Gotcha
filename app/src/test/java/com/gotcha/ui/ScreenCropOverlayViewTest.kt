package com.gotcha.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.gotcha.service.AnnotatedEntity
import com.gotcha.service.DetectedEntity
import com.gotcha.service.EntityType
import com.gotcha.service.SmartAction
import com.gotcha.ui.theme.Skins
import com.gotcha.ui.theme.overlaySkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Lens overlay draws two families of paint under different rules: functional
 * chrome follows the skin, decoration deliberately does not. Both halves need
 * pinning — a regression in either direction is invisible on the default skin,
 * which is exactly how the hardcoded `#FF00E5FF` survived. It happened to be
 * Deep Space Dark's own accent.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenCropOverlayViewTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun viewFor(skinId: String) = ScreenCropOverlayView(
        context,
        colors = overlaySkin(context, skinId),
        onSelection = {},
        onCancel = {}
    )

    @Test
    fun `every piece of chrome follows the skin`() {
        val orchid = viewFor(Skins.Orchid.id).chromeColors()
        val vellum = viewFor(Skins.Vellum.id).chromeColors()

        assertEquals("the two views must expose the same chrome slots", orchid.size, vellum.size)
        orchid.indices.forEach { i ->
            assertTrue(
                "chrome slot $i is the same colour in Orchid and Vellum, so it is hardcoded",
                orchid[i] != vellum[i]
            )
        }
    }

    @Test
    fun `the decoration is the same in every skin`() {
        val reference = viewFor(Skins.DeepSpaceDark.id).decorationColors()
        Skins.all.forEach { skin ->
            assertTrue(
                "${skin.id} changed the decoration, which is meant to be Lens's signature",
                reference.contentEquals(viewFor(skin.id).decorationColors())
            )
        }
    }

    @Test
    fun `the hint stays light on a light skin`() {
        // It is drawn onto the dim over somebody else's app, not onto a surface
        // of ours, so a light skin's near-black onSurface would be unreadable.
        val vellum = viewFor(Skins.Vellum.id).decorationColors()
        assertEquals(android.graphics.Color.WHITE, vellum[1])
    }

    // ---- Annotation chips ----

    private fun annotation(left: Int, top: Int, right: Int, bottom: Int, value: String, groupCount: Int = 1) =
        AnnotatedEntity(
            entity = DetectedEntity(
                type = EntityType.URL,
                rawValue = value,
                normalizedValue = value,
                span = 0..value.length,
                confidence = 0.9f,
                actions = listOf(
                    SmartAction(
                        label = "🌐 Open: $value",
                        prompt = "@@SMART:VIEW|$value",
                        isPrimary = true
                    )
                )
            ),
            boundsOnScreen = Rect(left, top, right, bottom),
            groupCount = groupCount
        )

    /** A laid-out view that has drawn one frame, so chips have been placed. */
    private fun drawnViewWith(
        entities: List<AnnotatedEntity>,
        onSelected: (String) -> Unit = {}
    ): ScreenCropOverlayView {
        val view = ScreenCropOverlayView(
            context,
            colors = overlaySkin(context, Skins.DEFAULT_ID),
            onSelection = {},
            onCancel = {},
            onAnnotatedEntitySelected = onSelected
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)
        view.setAnnotatedEntities(entities)
        view.draw(Canvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)))
        return view
    }

    private fun tap(view: View, x: Float, y: Float) {
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0L, 0L, action, x, y, 0)
            view.onTouchEvent(event)
            event.recycle()
        }
    }

    @Test
    fun `chips never overlap each other`() {
        // Rows 30px apart: every chip wants the same strip of screen, which is
        // how the PR list ended up with labels stacked on labels.
        val stacked = (0 until 5).map { i ->
            annotation(40, 300 + i * 30, 400, 320 + i * 30, "example.com/$i")
        }
        val rects = drawnViewWith(stacked).drawnChipRects()
        assertTrue("nothing was drawn at all", rects.isNotEmpty())
        for (i in rects.indices) {
            for (j in i + 1 until rects.size) {
                assertFalse(
                    "chip $i and chip $j overlap: ${rects[i]} vs ${rects[j]}",
                    RectF.intersects(rects[i], rects[j])
                )
            }
        }
    }

    @Test
    fun `only the top-ranked chip carries the verb`() {
        val view = drawnViewWith(
            listOf(
                annotation(40, 200, 400, 240, "example.com/first"),
                annotation(40, 600, 400, 640, "example.com/second")
            )
        )
        // Both chips found room, so the second one's compact label is what
        // narrowed the bar — not a placement failure.
        assertEquals(2, view.drawnChipRects().size)
        val (first, second) = view.drawnChipRects()
        assertTrue(
            "the compact chip should be narrower than the one carrying 'Open:'",
            second.width() < first.width()
        )
    }

    @Test
    fun `a tap inside nested bounds picks the innermost entity`() {
        // Accessibility bounds nest — a timestamp's bounds can climb to the whole
        // list container. List order used to decide, so the container won.
        var selected: String? = null
        val view = drawnViewWith(
            listOf(
                annotation(0, 0, WIDTH, HEIGHT, "outer-container"),
                annotation(100, 500, 300, 560, "inner-target")
            )
        ) { selected = it }

        tap(view, 200f, 530f)
        assertEquals("@@SMART:VIEW|inner-target", selected)
    }

    @Test
    fun `a grouped annotation is labelled by count`() {
        val view = drawnViewWith(
            listOf(annotation(40, 400, 400, 440, "example.com", groupCount = 7))
        )
        assertEquals(1, view.drawnChipRects().size)
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
    }
}
