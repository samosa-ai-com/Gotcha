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
}
