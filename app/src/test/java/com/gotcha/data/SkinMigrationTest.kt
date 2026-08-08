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
     * SYSTEM was the default, so the overwhelming majority of installs are on
     * it and never chose a theme. Vellum is the new default landing place.
     */
    @Test
    fun `someone who never chose a theme lands on the Vellum default`() {
        assertEquals(SKIN_VELLUM, migrateSkinId("SYSTEM"))
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
