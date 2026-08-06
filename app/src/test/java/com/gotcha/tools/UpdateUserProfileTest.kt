package com.gotcha.tools

import com.gotcha.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The merge/cap behaviour behind the `update_user_profile` tool. Pure logic, so
 * no Robolectric needed: the settings snapshot is a plain data class.
 */
class UpdateUserProfileTest {

    private val current = Settings(
        userOccupation = "Backend engineer",
        userBackground = "Works on payments infrastructure. Uses Kotlin and Go.",
        userResponseStyle = "Keep replies short, no bullet lists."
    )

    private fun applied(update: ProfileUpdate, base: Settings = current): ProfileUpdateResult? =
        mergeProfileUpdate(base, update)

    @Test
    fun `occupation is replaced when provided`() {
        val result = applied(ProfileUpdate(occupation = "Engineering manager"))!!
        assertEquals("Engineering manager", result.updated.userOccupation)
        assertEquals(listOf("occupation"), result.changedFields)
    }

    @Test
    fun `occupation is not erased by a blank value`() {
        val result = applied(ProfileUpdate(occupation = "   "))
        assertNull(result)
        assertEquals("Backend engineer", current.userOccupation)
    }

    @Test
    fun `background is extended while prior facts survive`() {
        val result = applied(
            ProfileUpdate(
                background = "Works on payments infrastructure. Uses Kotlin and Go. " +
                    "Recently moved to a platform team."
            )
        )!!
        assertEquals(
            "Works on payments infrastructure. Uses Kotlin and Go. Recently moved to a platform team.",
            result.updated.userBackground
        )
        assertEquals(listOf("background"), result.changedFields)
    }

    @Test
    fun `background is capped at 250 words`() {
        val long = List(400) { "word" }.joinToString(" ")
        val result = applied(ProfileUpdate(background = long))!!
        assertEquals(250, profileWordCount(result.updated.userBackground))
        assertEquals(250, result.updated.userBackground.split(" ").size)
    }

    @Test
    fun `reply style is capped at 50 words`() {
        val long = List(120) { "word" }.joinToString(" ")
        val result = applied(ProfileUpdate(replyStyle = long))!!
        assertEquals(50, profileWordCount(result.updated.userResponseStyle))
    }

    @Test
    fun `unchanged values are a no-op`() {
        val result = applied(
            ProfileUpdate(
                occupation = current.userOccupation,
                background = current.userBackground,
                replyStyle = current.userResponseStyle
            )
        )
        assertNull(result)
    }

    @Test
    fun `whitespace is collapsed and trimmed`() {
        val result = applied(ProfileUpdate(background = "  Works   on    payments.\n  Uses  Kotlin.  "))!!
        assertEquals("Works on payments. Uses Kotlin.", result.updated.userBackground)
    }

    @Test
    fun `several fields update in one call`() {
        val result = applied(
            ProfileUpdate(
                occupation = "Staff engineer",
                background = "Works on payments infrastructure. Uses Kotlin and Go. Leading the billing squad.",
                replyStyle = "Keep replies short, no bullet lists. Always state the command run."
            )
        )!!
        assertEquals("Staff engineer", result.updated.userOccupation)
        assertEquals(
            "Works on payments infrastructure. Uses Kotlin and Go. Leading the billing squad.",
            result.updated.userBackground
        )
        assertEquals(
            "Keep replies short, no bullet lists. Always state the command run.",
            result.updated.userResponseStyle
        )
        assertEquals(listOf("occupation", "background", "reply_style"), result.changedFields)
    }

    @Test
    fun `a partial update touches only the provided field`() {
        val result = applied(ProfileUpdate(replyStyle = "No bullet lists."))!!
        assertEquals(current.userOccupation, result.updated.userOccupation)
        assertEquals(current.userBackground, result.updated.userBackground)
        assertEquals("No bullet lists.", result.updated.userResponseStyle)
        assertEquals(listOf("reply_style"), result.changedFields)
    }

    @Test
    fun `blank proposed background never clears the field`() {
        val result = applied(ProfileUpdate(background = "\n\n"))
        assertNull(result)
        assertEquals(current.userBackground, current.userBackground)
    }

    @Test
    fun `normalized equality counts as no change`() {
        val result = applied(ProfileUpdate(background = "  Works on payments  infrastructure. Uses Kotlin and Go.  "))
        assertNull("whitespace-only differences are a no-op", result)
    }
}
