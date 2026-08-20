package com.gotcha.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReferralClipboardHelperTest {

    private val regex = Regex("AIR-[A-Z0-9]{5,8}")

    private fun extractCode(input: String?): String? {
        if (input.isNullOrBlank()) return null
        return regex.find(input.uppercase().trim())?.value
    }

    @Test
    fun `extracts valid referral code from plain text`() {
        assertEquals("AIR-K9X2P7", extractCode("AIR-K9X2P7"))
        assertEquals("AIR-K9X2P7", extractCode("air-k9x2p7"))
    }

    @Test
    fun `extracts referral code embedded in URL or text message`() {
        assertEquals("AIR-K9X2P7", extractCode("Hey try Gotcha! https://api.samosa-ai.com/join?ref=AIR-K9X2P7"))
        assertEquals("AIR-K9X2P7", extractCode("Join Gotcha using code: air-k9x2p7 for bonus credits"))
    }

    @Test
    fun `handles various code lengths from 5 to 8 chars`() {
        assertEquals("AIR-12345", extractCode("AIR-12345"))
        assertEquals("AIR-ABCDEF12", extractCode("AIR-ABCDEF12"))
    }

    @Test
    fun `returns null for invalid or missing codes`() {
        assertNull(extractCode(null))
        assertNull(extractCode(""))
        assertNull(extractCode("   "))
        assertNull(extractCode("Hello world"))
        assertNull(extractCode("AIR-123")) // Too short (< 5)
        assertNull(extractCode("XYZ-K9X2P7")) // Wrong prefix
    }
}
