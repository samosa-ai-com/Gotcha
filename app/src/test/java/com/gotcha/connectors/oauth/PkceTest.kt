package com.gotcha.connectors.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {

    @Test
    fun `challenge matches RFC 7636 appendix B vector`() {
        // Verifier and expected S256 challenge from RFC 7636 Appendix B.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challengeS256(verifier))
    }

    @Test
    fun `generated verifier has requested length and legal charset`() {
        val verifier = Pkce.generateVerifier(64)
        assertEquals(64, verifier.length)
        assertTrue(verifier.all { it.isLetterOrDigit() || it in "-._~" })
    }

    @Test
    fun `generated verifiers are unique`() {
        assertNotEquals(Pkce.generateVerifier(), Pkce.generateVerifier())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `verifier length below 43 rejected`() {
        Pkce.generateVerifier(42)
    }
}
