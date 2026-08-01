package com.gotcha.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.ui.theme.Skins
import com.gotcha.ui.theme.overlaySkin
import org.junit.Assert.assertEquals
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
}
