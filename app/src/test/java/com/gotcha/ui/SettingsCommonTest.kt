package com.gotcha.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsCommonTest {

    @Test
    fun `formatRemainingClaimHours calculates correct remaining time`() {
        val now = Instant.parse("2026-08-20T12:00:00Z")

        // 10 hours ago -> 62 hours left
        val created10hAgo = "2026-08-20T02:00:00Z"
        assertEquals(
            "You have 62h left to claim an invite code.",
            formatRemainingClaimHours(created10hAgo, now = now)
        )

        // 71 hours ago -> 1 hour left
        val created71hAgo = "2026-08-17T13:00:00Z"
        assertEquals(
            "You have 1h left to claim an invite code.",
            formatRemainingClaimHours(created71hAgo, now = now)
        )

        // Exactly 72 hours ago -> 0 hours left -> returns null
        val created72hAgo = "2026-08-17T12:00:00Z"
        assertNull(formatRemainingClaimHours(created72hAgo, now = now))

        // 80 hours ago -> past window -> returns null
        val created80hAgo = "2026-08-17T04:00:00Z"
        assertNull(formatRemainingClaimHours(created80hAgo, now = now))
    }

    @Test
    fun `formatRemainingClaimHours handles invalid input safely`() {
        val now = Instant.parse("2026-08-20T12:00:00Z")
        assertNull(formatRemainingClaimHours(null, now = now))
        assertNull(formatRemainingClaimHours("", now = now))
        assertNull(formatRemainingClaimHours("invalid-date", now = now))
    }

    @Test
    fun `parseBadgeColor parses valid hex colors`() {
        val blue = parseBadgeColor("#0D6EFD")
        assertNotNull(blue)
        // Verify alpha is 1.0
        assertEquals(1.0f, blue.alpha)

        val green = parseBadgeColor("#198754")
        assertNotNull(green)
    }

    @Test
    fun `parseBadgeColor falls back to grey on invalid hex`() {
        val fallback = parseBadgeColor("not-a-color")
        assertEquals(Color(0xFF6C757D), fallback)

        val emptyFallback = parseBadgeColor("")
        assertEquals(Color(0xFF6C757D), emptyFallback)
    }

    @Test
    fun `formatScaledCredits scales by 1000 and formats correctly`() {
        assertEquals("1,000", formatScaledCredits(1.0))
        assertEquals("1,250", formatScaledCredits(1.25))
        assertEquals("0", formatScaledCredits(0.0))
    }
}
