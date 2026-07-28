package com.gotcha.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The skin catalogue and the rules derived from it.
 *
 * These are the invariants the rest of the theme layer assumes without checking:
 * that an unknown id cannot crash the app, that a glass skin can always be made
 * solid, and that the transparent `background` role — which is what lets a
 * Scaffold show the wallpaper — survives exactly where it should and nowhere
 * else.
 */
class SkinTest {

    @Test
    fun `an unknown id falls back to the default rather than throwing`() {
        assertEquals(Skins.DeepSpaceDark, Skins.byId("a-skin-from-a-future-build"))
        assertEquals(Skins.DeepSpaceDark, Skins.byId(""))
    }

    @Test
    fun `the default id resolves to a real skin`() {
        assertEquals(Skins.DEFAULT_ID, Skins.byId(Skins.DEFAULT_ID).id)
    }

    @Test
    fun `ids are unique`() {
        val ids = Skins.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `only the glass skins draw a wallpaper`() {
        Skins.all.forEach { skin ->
            assertEquals(
                "${skin.id}: isGlass and backdrop disagree",
                skin.backdrop != Backdrop.NONE,
                skin.isGlass
            )
        }
    }

    /**
     * Scaffold paints its container from `background`. A glass skin has to leave
     * that transparent or the app lays an opaque sheet over its own wallpaper —
     * which is exactly the bug that made Orchid look like flat pink.
     */
    @Test
    fun `glass skins keep a transparent background role`() {
        Skins.all.filter { it.isGlass }.forEach { skin ->
            assertEquals(
                "${skin.id} must not paint over its own wallpaper",
                Color.Transparent,
                skin.scheme.background
            )
        }
    }

    @Test
    fun `opaque skins paint their own background`() {
        Skins.all.filterNot { it.isGlass }.forEach { skin ->
            assertNotEquals(
                "${skin.id} has no wallpaper, so something must paint the ground",
                Color.Transparent,
                skin.scheme.background
            )
        }
    }

    @Test
    fun `making a skin solid removes the wallpaper and every translucent surface`() {
        Skins.all.filter { it.isGlass }.forEach { skin ->
            val solid = skin.opaque()
            assertFalse("${skin.id} still reports glass", solid.isGlass)
            assertEquals("${skin.id} kept its grain", 0f, solid.grain, 0f)
            assertEquals("${skin.id} kept its scrim", 0f, solid.scrim, 0f)
            assertEquals("${skin.id} background", skin.ground, solid.scheme.background)
            listOf(
                "surface" to solid.scheme.surface,
                "surfaceVariant" to solid.scheme.surfaceVariant,
                "secondaryContainer" to solid.scheme.secondaryContainer
            ).forEach { (role, color) ->
                assertEquals("${skin.id} $role is still see-through", 1f, color.alpha, 0.001f)
            }
        }
    }

    @Test
    fun `making an already opaque skin solid changes nothing`() {
        assertEquals(Skins.DeepSpaceDark, Skins.DeepSpaceDark.opaque())
        assertEquals(Skins.DeepSpaceLight, Skins.DeepSpaceLight.opaque())
    }

    /**
     * Compositing a translucent panel onto its ground must land between the two,
     * not on either — a flatten that returns the ground has erased the panel.
     */
    @Test
    fun `flattening lands between the panel and the ground`() {
        val solid = Skins.Aura.opaque()
        val panel = solid.scheme.surface
        assertNotEquals(Skins.Aura.ground, panel)
        assertTrue("panel should sit above its ground", panel.red > Skins.Aura.ground.red)
    }

    /** System-bar icons follow the skin, never the device's dark-mode flag. */
    @Test
    fun `light skins ask for dark system bar icons`() {
        Skins.all.forEach { skin ->
            assertEquals(
                "${skin.id}",
                skin.brightness == Brightness.LIGHT,
                skin.darkSystemBarIcons
            )
        }
    }

    @Test
    fun `the launch ground is never transparent`() {
        Skins.all.forEach { skin ->
            assertEquals(
                "${skin.id} would flash a see-through window before first frame",
                1f,
                skin.launchGround.alpha,
                0.001f
            )
        }
    }

    @Test
    fun `the small radius is derived from the large one`() {
        Skins.all.forEach { skin ->
            assertEquals("${skin.id}", skin.corner / 2, skin.cornerSmall)
            assertTrue("${skin.id} has no radius at all", skin.corner > 0.dp)
        }
    }

    @Test
    fun `only skins with a wallpaper declare frost, grain or scrim`() {
        Skins.all.filterNot { it.isGlass }.forEach { skin ->
            assertEquals("${skin.id} grain", 0f, skin.grain, 0f)
            assertEquals("${skin.id} scrim", 0f, skin.scrim, 0f)
        }
    }
}
