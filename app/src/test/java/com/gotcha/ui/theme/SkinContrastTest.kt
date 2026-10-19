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

    private companion object {
        const val MIN_TEXT = 4.5f
    }
}
