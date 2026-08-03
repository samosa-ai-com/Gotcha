package com.gotcha.marketing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Smoke test that the poster renders to a fixed-size 4:5 bitmap without
 * throwing. The full visual is exercised on-device (the pixel content is
 * reviewed by hand against the emulator); this pins the size contract and the
 * render-not-crash path that the share flow depends on.
 *
 * (Robolectric's software canvas does not surface drawn pixels through
 * `Bitmap.getPixel`, so ink coverage is asserted by the painter's own layout
 * contract rather than by sampling the bitmap.)
 */
@RunWith(RobolectricTestRunner::class)
class PosterRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `hero renders at the four-by-five instagram size`() {
        val bitmap = PosterRenderer.render(
            context = context,
            content = PosterContent(
                eligible = true,
                template = "hero",
                headline = "I asked Gotcha to plan my Goa trip",
                subheadline = "…and it nailed it in 42 seconds.",
                body = "Flights, hotels and itinerary — all sorted.",
                callToAction = "Meet your agent.",
                hashtags = listOf("#Gotcha", "#AIAgent")
            ),
            stats = PosterStats(
                runCount = 1,
                totalDurationSeconds = 42,
                toolCount = 3,
                model = "deepseek-v4-flash"
            )
        )
        assertEquals(PosterRenderer.WIDTH_PX, bitmap.width)
        assertEquals(PosterRenderer.HEIGHT_PX, bitmap.height)
    }

    @Test
    fun `recap renders with achievements`() {
        val bitmap = PosterRenderer.render(
            context = context,
            content = PosterContent(
                eligible = true,
                template = "recap",
                headline = "Gotcha handled 7 things for me today",
                subheadline = "One assistant, no sweat.",
                body = "",
                achievements = listOf("Planned the trip", "Booked a cab", "Set an alarm"),
                callToAction = "Meet your agent.",
                hashtags = listOf("#Gotcha")
            ),
            stats = PosterStats(
                runCount = 7,
                totalDurationSeconds = 300,
                toolCount = 9,
                model = "gpt-4o"
            )
        )
        assertEquals(PosterRenderer.WIDTH_PX, bitmap.width)
        assertEquals(PosterRenderer.HEIGHT_PX, bitmap.height)
    }

    @Test
    fun `render tolerates blank content without throwing`() {
        val bitmap = PosterRenderer.render(
            context = context,
            content = PosterContent(), // all defaults, blank copy
            stats = PosterStats(runCount = 0, totalDurationSeconds = 0, toolCount = 0, model = "")
        )
        assertNotNull(bitmap)
        assertEquals(PosterRenderer.WIDTH_PX, bitmap.width)
    }

    @Test
    fun `download qr drawable exists and is square`() {
        val bmp = android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.gotcha_download_qr)
        assertNotNull(bmp)
        assertTrue(bmp.width > 0)
        assertEquals(bmp.width, bmp.height)
    }

    @Test
    fun `painter lays out within the canvas bounds`() {
        val painter = PosterRenderer.PosterPainter(typeface = null)
        val layout = painter.measureLayout(
            content = PosterContent(
                headline = "A very long headline that should wrap across multiple lines",
                subheadline = "and a subheadline that also wraps",
                body = "Some body text",
                achievements = listOf("one", "two", "three", "four", "five"),
                callToAction = "Meet Gotcha now",
                hashtags = listOf("#Gotcha", "#AIAgent", "#Android")
            )
        )
        // The footer (CTA + hashtags + QR + brand) must fit inside the canvas.
        // The CTA flows right after the chips — no gap — so the top of the
        // footer is at the bottom of the content block.
        assertTrue("footer inside canvas", layout.footerBottom <= PosterRenderer.HEIGHT_PX)
    }

    @Test
    fun `painter handles a very long achievement list without overflow`() {
        val painter = PosterRenderer.PosterPainter(typeface = null)
        val layout = painter.measureLayout(
            content = PosterContent(
                template = "recap",
                headline = "Gotcha handled 50 things today",
                subheadline = "",
                body = "",
                achievements = (1..50).map { "Accomplishment number $it that keeps going" },
                callToAction = "CTA",
                hashtags = emptyList()
            )
        )
        // The CTA pill is pinned to the bottom of the canvas; the content
        // block above it must not push past it. The footer (CTA + hashtags +
        // QR + brand) must fit inside the canvas.
        assertTrue("content must stay above CTA", layout.lastElementBottom <= layout.ctaTop)
        assertTrue("footer must stay inside canvas", layout.footerBottom <= PosterRenderer.HEIGHT_PX)
    }
}
