package com.gotcha.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The appearance migration runs exactly once per install, silently, and its
 * result is written back — so a mistake here is a mistake nobody reports and
 * nobody can undo without finding the setting again. It is worth pinning down.
 */
class SkinMigrationTest {

    @Test
    fun `someone who chose Light keeps a light theme`() {
        assertEquals(SKIN_DEEP_SPACE_LIGHT, migrateSkinId("LIGHT"))
    }

    @Test
    fun `someone who chose Dark keeps a dark theme`() {
        assertEquals(SKIN_DEEP_SPACE_DARK, migrateSkinId("DARK"))
    }

    /**
     * SYSTEM was the stored default, so it is the theme the app actually showed
     * for most installs. Flipping that population from dark to light silently
     * would be a regression — it stays on Deep Space Dark.
     */
    @Test
    fun `someone on SYSTEM keeps the dark Deep Space original`() {
        assertEquals(SKIN_DEEP_SPACE_DARK, migrateSkinId("SYSTEM"))
    }

    /**
     * A fresh install never writes `theme_mode` (the new skin system replaced
     * it entirely), so null is the only true "never chose" population. That is
     * who lands on the Vellum default.
     */
    @Test
    fun `a fresh install that never chose a theme lands on the Vellum default`() {
        assertEquals(SKIN_VELLUM, migrateSkinId(null))
    }

    /** A value from a build that no longer exists must not throw. */
    @Test
    fun `an unrecognised legacy value falls back to Vellum rather than failing`() {
        assertEquals(SKIN_VELLUM, migrateSkinId("AUTO_BATTERY"))
        assertEquals(SKIN_VELLUM, migrateSkinId(""))
    }

    /** The ids it produces have to be ids the picker actually knows. */
    @Test
    fun `every id the migration can produce is a real skin`() {
        val produced = listOf(null, "SYSTEM", "LIGHT", "DARK", "nonsense").map(::migrateSkinId)
        val known = com.gotcha.ui.theme.Skins.all.map { it.id }
        produced.forEach { id ->
            assert(id in known) { "migration produced '$id', which is not in Skins.all" }
        }
    }
}
