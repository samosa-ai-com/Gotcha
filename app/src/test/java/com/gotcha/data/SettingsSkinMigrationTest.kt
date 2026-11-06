package com.gotcha.data

import com.gotcha.ui.theme.Skins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The retired faceted theme ("nocturne") migrates to Samosa; every other
 * stored id passes through untouched.
 */
class SettingsSkinMigrationTest {

    @Test
    fun `nocturne maps to samosa`() {
        assertEquals(Skins.Samosa.id, retiredSkinIdReplacement("nocturne"))
    }

    @Test
    fun `current ids map to nothing`() {
        Skins.all.forEach { skin ->
            assertNull(
                "a current id must not migrate, got ${retiredSkinIdReplacement(skin.id)}",
                retiredSkinIdReplacement(skin.id)
            )
        }
    }

    @Test
    fun `a missing id maps to nothing`() {
        assertNull(retiredSkinIdReplacement(null))
    }
}
