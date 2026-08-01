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
     * SYSTEM was the default, and the overwhelming majority of installs are on
     * it. Dark is the honest landing place: it is what the app looked like for
     * most of them, and it is the skin the picker calls the original.
     */
    @Test
    fun `someone who never changed it lands on the original`() {
        assertEquals(SKIN_DEEP_SPACE_DARK, migrateSkinId("SYSTEM"))
        assertEquals(SKIN_DEEP_SPACE_DARK, migrateSkinId(null))
    }

    /** A value from a build that no longer exists must not throw. */
    @Test
    fun `an unrecognised legacy value falls back rather than failing`() {
        assertEquals(SKIN_DEEP_SPACE_DARK, migrateSkinId("AUTO_BATTERY"))
        assertEquals(SKIN_DEEP_SPACE_DARK, migrateSkinId(""))
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
