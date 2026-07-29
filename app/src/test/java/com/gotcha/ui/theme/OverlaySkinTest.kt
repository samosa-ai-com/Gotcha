package com.gotcha.ui.theme

import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The overlays are View code and cannot read [LocalSkin], so [overlaySkin] is
 * the only thing keeping them on the same design as the app. Before it existed
 * they had drifted to a hardcoded `#1E1E1E` panel with a `Color.CYAN` stroke —
 * which nobody noticed, because nothing asserted otherwise.
 */
@RunWith(RobolectricTestRunner::class)
class OverlaySkinTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `radii come from the chosen skin`() {
        val orchid = overlaySkin(context, Skins.Orchid.id)
        assertEquals(Skins.Orchid.corner.value, orchid.cardRadiusDp, 0.001f)
        assertEquals(Skins.Orchid.cornerSmall.value, orchid.buttonRadiusDp, 0.001f)

        val nocturne = overlaySkin(context, Skins.Nocturne.id)
        assertTrue(
            "the skins have different shapes, so their overlays must too",
            orchid.cardRadiusDp != nocturne.cardRadiusDp
        )
    }

    @Test
    fun `the accent is the skin's own`() {
        Skins.all.forEach { skin ->
            assertEquals(
                skin.id,
                skin.opaque().scheme.primary.toArgb(),
                overlaySkin(context, skin.id).accent
            )
        }
    }

    /**
     * An overlay draws over another app. Anything see-through there is unreadable,
     * and there is no wallpaper of ours behind it to justify the attempt.
     */
    @Test
    fun `every overlay colour is fully opaque`() {
        Skins.all.forEach { skin ->
            val tokens = overlaySkin(context, skin.id)
            listOf(
                "surface" to tokens.surface,
                "onSurface" to tokens.onSurface,
                "outline" to tokens.outline,
                "buttonBg" to tokens.buttonBg,
                "buttonText" to tokens.buttonText,
                "accent" to tokens.accent
            ).forEach { (role, argb) ->
                assertEquals("${skin.id} $role", 255, Color.alpha(argb))
            }
        }
    }

    @Test
    fun `an unknown skin falls back rather than throwing`() {
        val fallback = overlaySkin(context, "a-skin-from-a-future-build")
        assertEquals(overlaySkin(context, Skins.DEFAULT_ID).accent, fallback.accent)
    }

    /**
     * Depth over another app's screen comes from the edge, not from blur — so
     * the edge has to actually be visible against the surface it sits on. Which
     * direction that is depends on the skin: a white hairline on Vellum's
     * near-white panel would be nothing at all.
     */
    @Test
    fun `the top edge is lit against the surface, whichever way the skin runs`() {
        Skins.all.forEach { skin ->
            val tokens = overlaySkin(context, skin.id)
            val surface = luminance(tokens.surface)
            val edge = luminance(tokens.edgeHighlight)
            if (skin.brightness == Brightness.DARK) {
                assertTrue(
                    "${skin.id}: the edge must be lighter than the surface ($edge vs $surface)",
                    edge > surface
                )
            } else {
                assertTrue(
                    "${skin.id}: the edge must be darker than the surface ($edge vs $surface)",
                    edge < surface
                )
            }
        }
    }

    /** Far enough to read, close enough not to look like a second border. */
    @Test
    fun `the edge stays a hairline rather than becoming a band of its own`() {
        Skins.all.forEach { skin ->
            val tokens = overlaySkin(context, skin.id)
            val delta = kotlin.math.abs(luminance(tokens.edgeHighlight) - luminance(tokens.surface))
            assertTrue("${skin.id}: edge is invisible ($delta)", delta > 0.01)
            assertTrue("${skin.id}: edge is shouting ($delta)", delta < 0.35)
        }
    }

    @Test
    fun `the shadow is translucent black and has somewhere to fall`() {
        Skins.all.forEach { skin ->
            val tokens = overlaySkin(context, skin.id)
            val alpha = Color.alpha(tokens.shadowColor)
            assertTrue("${skin.id}: an opaque shadow is a black box", alpha in 1..254)
            assertTrue("${skin.id}: a shadow needs room to spread", tokens.shadowRadiusDp > 0f)
        }
    }

    private fun luminance(argb: Int): Double = ColorUtils.calculateLuminance(argb)

    /** Type sizes track the app's scale rather than being chosen per overlay. */
    @Test
    fun `type sizes come from the app typography`() {
        val tokens = overlaySkin(context, Skins.Aura.id)
        assertEquals(Typography.titleMedium.fontSize.value, tokens.titleSp, 0.001f)
        assertEquals(Typography.bodyMedium.fontSize.value, tokens.bodySp, 0.001f)
        assertEquals(Typography.labelMedium.fontSize.value, tokens.labelSp, 0.001f)
    }
}
