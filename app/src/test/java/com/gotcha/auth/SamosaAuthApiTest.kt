package com.gotcha.auth

import com.gotcha.ui.formatScaledCredits
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parsing and formatting rules for the Samosa `/me` profile and the ×1000
 * credit figure. The Json config mirrors `SamosaAuthApi.create()` so the tests
 * exercise the same deserialization the app uses.
 */
class SamosaAuthApiTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Test
    fun `me response parses the user envelope`() {
        val decoded = json.decodeFromString(
            MeResponse.serializer(),
            """
            {
              "user": {
                "id": "user-42",
                "email": "a@b.com",
                "display_name": "A",
                "profile_picture": "",
                "role": "user",
                "status": "active",
                "created_at": "…",
                "last_login": "…",
                "credits_remaining": 2.0
              }
            }
            """.trimIndent()
        )

        assertEquals("user-42", decoded.user.id)
        assertEquals("a@b.com", decoded.user.email)
        assertEquals(2.0, decoded.user.creditsRemaining)
    }

    @Test
    fun `me response tolerates missing credits`() {
        val decoded = json.decodeFromString(
            MeResponse.serializer(),
            """{"user": {"id": "user-42", "email": "a@b.com"}}"""
        )

        assertEquals("user-42", decoded.user.id)
        assertNull(decoded.user.creditsRemaining)
    }

    @Test
    fun `me response tolerates unknown top-level keys`() {
        val decoded = json.decodeFromString(
            MeResponse.serializer(),
            """
            {
              "extra": {"anything": true},
              "user": {"id": "user-42", "credits_remaining": 0.5}
            }
            """.trimIndent()
        )

        assertEquals("user-42", decoded.user.id)
        assertEquals(0.5, decoded.user.creditsRemaining)
    }

    @Test
    fun `register response still parses`() {
        val decoded = json.decodeFromString(
            RegisterResponse.serializer(),
            """
            {
              "token": "jwt",
              "user": {"id": "user-42", "email": "a@b.com", "credits_remaining": 2.0}
            }
            """.trimIndent()
        )

        assertEquals("jwt", decoded.token)
        assertEquals("user-42", decoded.user.id)
        assertEquals(2.0, decoded.user.creditsRemaining)
    }

    @Test
    fun `scaled credits rounds to the nearest whole thousand`() {
        assertEquals("2,000", formatScaledCredits(2.0))
        assertEquals("500", formatScaledCredits(0.5))
        assertEquals("2,501", formatScaledCredits(2.5006))
        assertEquals("0", formatScaledCredits(0.0))
    }

    @Test
    fun `scaled credits uses thousands separators for large values`() {
        assertEquals("1,234,567", formatScaledCredits(1234.5674))
    }

    @Test
    fun `me response parses tier, tags and referral metadata`() {
        val payload = """
            {
              "user": {
                "id": "c6a2e413-0000",
                "email": "user@example.com",
                "display_name": "Rishabh",
                "profile_picture": "https://example.com/pic.jpg",
                "role": "user",
                "status": "active",
                "created_at": "2026-08-19T10:00:00.000Z",
                "last_login": "2026-08-19T10:05:00.000Z",
                "credits_remaining": 1250.0,
                "tier": {
                  "id": "pro",
                  "display_name": "Pro",
                  "badge_color": "#0d6efd"
                },
                "tags": ["beta_tester", "early_adopter"],
                "referral": {
                  "code": "AIR-K9X2P7",
                  "share_url": "https://api.samosa-ai.com/join?ref=AIR-K9X2P7",
                  "total_referred": 4,
                  "credits_earned": 200.0,
                  "can_claim": true,
                  "referred_by": {
                    "id": "ref-1",
                    "display_name": "Alice",
                    "email": "alice@example.com"
                  }
                }
              }
            }
        """.trimIndent()

        val decoded = json.decodeFromString(MeResponse.serializer(), payload)
        val user = decoded.user

        assertEquals("c6a2e413-0000", user.id)
        assertEquals("user@example.com", user.email)
        assertEquals("Rishabh", user.displayName)
        assertEquals(1250.0, user.creditsRemaining)
        assertEquals("pro", user.tier.id)
        assertEquals("Pro", user.tier.displayName)
        assertEquals("#0d6efd", user.tier.badgeColor)
        assertEquals(listOf("beta_tester", "early_adopter"), user.tags)
        assertEquals("AIR-K9X2P7", user.referral.code)
        assertEquals("https://api.samosa-ai.com/join?ref=AIR-K9X2P7", user.referral.shareUrl)
        assertEquals(4, user.referral.totalReferred)
        assertEquals(200.0, user.referral.creditsEarned, 0.001)
        assertEquals(true, user.referral.canClaim)
        assertEquals("Alice", user.referral.referredBy?.displayName)
    }

    @Test
    fun `claim referral response parses successfully`() {
        val payload = """
            {
              "message": "Referral claimed",
              "referral": {
                "code": "AIR-K9X2P7",
                "referrer": {
                  "id": "ref-1",
                  "display_name": "Alice",
                  "email": "alice@example.com"
                },
                "referrer_reward": 50.0,
                "referee_reward": 50.0
              },
              "credits_remaining": 1300.0
            }
        """.trimIndent()

        val decoded = json.decodeFromString(ClaimReferralResponse.serializer(), payload)
        assertEquals("Referral claimed", decoded.message)
        assertEquals("AIR-K9X2P7", decoded.referral?.code)
        assertEquals(50.0, decoded.referral?.refereeReward)
        assertEquals(50.0, decoded.referral?.referrerReward)
        assertEquals(1300.0, decoded.creditsRemaining)
    }

    @Test
    fun `register request serializes optional referral_code`() {
        val withCode = RegisterRequest(idToken = "token123", referralCode = "AIR-K9X2P7")
        val jsonStr = json.encodeToString(RegisterRequest.serializer(), withCode)
        org.junit.Assert.assertTrue(jsonStr.contains("\"referral_code\":\"AIR-K9X2P7\""))

        val withoutCode = RegisterRequest(idToken = "token123", referralCode = null)
        val jsonStrNoCode = json.encodeToString(RegisterRequest.serializer(), withoutCode)
        org.junit.Assert.assertFalse(jsonStrNoCode.contains("referral_code"))
    }
}
