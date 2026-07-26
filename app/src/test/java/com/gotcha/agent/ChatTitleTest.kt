package com.gotcha.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatTitleTest {

    @Test
    fun `accepts a plain short title`() {
        assertEquals("Write notes file", ChatTitle.sanitize("Write notes file"))
    }

    @Test
    fun `strips surrounding quotes and trailing period`() {
        assertEquals("Trip to Goa", ChatTitle.sanitize("\"Trip to Goa.\""))
        assertEquals("Trip to Goa", ChatTitle.sanitize("'Trip to Goa'"))
    }

    @Test
    fun `takes the first non-blank line`() {
        assertEquals("Battery drain check", ChatTitle.sanitize("\n\nBattery drain check\nsome rambling\n"))
    }

    /** The observed mimo-v2.5 failure: the model answered the message instead of titling it. */
    @Test
    fun `rejects an assistant reply`() {
        assertNull(ChatTitle.sanitize("Sure! I've created the file `notes.md` with the content you specified."))
    }

    @Test
    fun `rejects other acknowledgement openers`() {
        assertNull(ChatTitle.sanitize("Done — the alarm is set"))
        assertNull(ChatTitle.sanitize("I have added that event"))
        assertNull(ChatTitle.sanitize("Here's what I found"))
        assertNull(ChatTitle.sanitize("of course, happy to help"))
    }

    @Test
    fun `rejects prose that is too long or too wordy`() {
        assertNull(ChatTitle.sanitize("x".repeat(61)))
        assertNull(ChatTitle.sanitize("one two three four five six seven eight nine ten eleven"))
    }

    @Test
    fun `rejects blank and null input`() {
        assertNull(ChatTitle.sanitize(null))
        assertNull(ChatTitle.sanitize("   "))
        assertNull(ChatTitle.sanitize("\"\""))
    }

    @Test
    fun `accepts a title at the length and word boundaries`() {
        val sixty = "x".repeat(60)
        assertEquals(sixty, ChatTitle.sanitize(sixty))
        assertEquals(
            "one two three four five six seven eight nine ten",
            ChatTitle.sanitize("one two three four five six seven eight nine ten")
        )
    }
}
