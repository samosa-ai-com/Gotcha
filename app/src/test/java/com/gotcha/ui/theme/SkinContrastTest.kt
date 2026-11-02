package com.gotcha.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text on dialogs and menus has to be readable in every skin.
 *
 * Popups are where this is testable: they sit on [Skin.menuContainer], one
 * opaque colour, so the contrast is exactly what the user sees. In-page chrome
 * sits on a wallpaper and isn't covered here.
 *
 * 4.5:1 is WCAG AA for body text — dialog titles, bodies and the TextButtons
 * along the bottom are all ordinary-sized text. Orchid's coral buttons once
 * came out at 1.7:1 on a lilac dialog, which nothing but a phone noticed.
 */
class SkinContrastTest {

    private val allMaterials = Skins.all + Skins.all.map { it.opaque() }

    @Test
    fun `dialog text is readable on the dialog in every skin`() {
        allMaterials.forEach { skin ->
            val popup = skin.menuContainer
            assertReadable(skin, "onSurface on the dialog", skin.scheme.onSurface, popup)
            assertReadable(skin, "onSurfaceVariant on the dialog", skin.scheme.onSurfaceVariant, popup)
        }
    }

    @Test
    fun `dialog buttons are readable on the dialog in every skin`() {
        allMaterials.forEach { skin ->
            assertReadable(skin, "primary (TextButton) on the dialog", skin.scheme.primary, skin.menuContainer)
            assertReadable(skin, "error on the dialog", skin.scheme.error, skin.menuContainer)
        }
    }

    @Test
    fun `content on an error fill is readable in every skin`() {
        allMaterials.forEach { skin ->
            assertReadable(skin, "onError on error", skin.scheme.onError, skin.scheme.error)
        }
    }

    /**
     * Links used to come out pure blue on every skin — 1.1:1 on Orchid's
     * violet — because the renderer was never given a style. Each skin now
     * names its link colour; it must clear 4.5:1 on the ground (assistant
     * prose), in the user bubble, and on every wallpaper blob composited over
     * the ground at full strength. The blur only softens the blobs, so full
     * strength is the pessimistic case; flat and opaque skins have no blobs
     * and skip that leg.
     */
    @Test
    fun `links are readable on the ground, in user bubbles and on wallpaper`() {
        allMaterials.forEach { skin ->
            assertReadable(skin, "link on the ground", skin.link, skin.ground)
            assertReadable(
                skin,
                "link in the user bubble",
                skin.link,
                skin.scheme.primaryContainer
            )
            skin.wallpaper.forEach { blob ->
                assertReadable(
                    skin,
                    "link on wallpaper blob $blob",
                    skin.link,
                    composite(blob, skin.ground)
                )
            }
        }
    }

    @Test
    fun `the retired nocturne id opens samosa, not the fallback`() {
        assertTrue(
            "a stored nocturne skin must land on its replacement",
            Skins.byId("nocturne") === Skins.Samosa
        )
    }

    @Test
    fun `the dialog is opaque in every skin`() {
        allMaterials.forEach { skin ->
            assertTrue("${skin.id}: dialogs must not be see-through", skin.menuContainer.alpha == 1f)
        }
    }

    private fun assertReadable(skin: Skin, what: String, fg: Color, bg: Color) {
        val ratio = contrast(fg, bg)
        assertTrue(
            "${skin.id}${if (skin.isGlass) "" else " (solid)"}: $what is %.2f:1, needs $MIN_TEXT:1".format(ratio),
            ratio >= MIN_TEXT
        )
    }

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    /** Alpha-composites a translucent wallpaper blob onto the ground. */
    private fun composite(fg: Color, bg: Color): Color {
        if (fg.alpha >= 1f) return fg
        return Color(
            red = fg.red * fg.alpha + bg.red * (1f - fg.alpha),
            green = fg.green * fg.alpha + bg.green * (1f - fg.alpha),
            blue = fg.blue * fg.alpha + bg.blue * (1f - fg.alpha),
            alpha = 1f
        )
    }

    private companion object {
        const val MIN_TEXT = 4.5f
    }
}
