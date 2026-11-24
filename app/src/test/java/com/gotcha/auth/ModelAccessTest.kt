package com.gotcha.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the server proxy's rule (`_tier_model_denied` in air-auth-manager). */
class ModelAccessTest {

    @Test
    fun noListOrAWildcardLocksNothing() {
        assertNull(ModelAccess.allowList(null))
        assertNull(ModelAccess.allowList(emptyList()))
        assertNull(ModelAccess.allowList(listOf("chai-small", "*")))
        assertFalse(ModelAccess.isLocked("chai-medium", null))
    }

    @Test
    fun aListLocksEverythingOutsideIt() {
        val allowed = ModelAccess.allowList(listOf("chai-small", "lassi-TTS-small"))
        assertEquals(setOf("chai-small", "lassi-TTS-small"), allowed)
        assertTrue(ModelAccess.isLocked("chai-medium", allowed))
        assertFalse(ModelAccess.isLocked("chai-small", allowed))
        // An empty choice means "same as the main model", never a locked model.
        assertFalse(ModelAccess.isLocked("", allowed))
    }
}
