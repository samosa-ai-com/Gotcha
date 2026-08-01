package com.gotcha.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextSanitizerTest {

    @Test
    fun `strips simple emoji and trailing whitespace`() {
        assertEquals("Hello brother", SpeechTextSanitizer.stripEmoji("Hello brother 😊"))
    }

    @Test
    fun `strips emoji in the middle of a sentence`() {
        assertEquals("I am ready to help.", SpeechTextSanitizer.stripEmoji("I am ready 🙌 to help."))
    }

    @Test
    fun `strips multi-codepoint emoji with variation selectors and ZWJ`() {
        // Heavy check mark + variation selector, and a ZWJ family emoji.
        assertEquals("Done", SpeechTextSanitizer.stripEmoji("Done ✔️"))
        assertEquals("Family", SpeechTextSanitizer.stripEmoji("Family 👨‍👩‍👦"))
    }

    @Test
    fun `strips dingbats and misc symbol blocks`() {
        assertEquals("Great job", SpeechTextSanitizer.stripEmoji("Great job ✅⭐"))
    }

    @Test
    fun `leaves non-emoji text (including Hindi) untouched`() {
        val hindi = "हेलो भाई! मैं ठीक हूँ, आप कैसे हो?"
        assertEquals(hindi, SpeechTextSanitizer.stripEmoji(hindi))
    }

    @Test
    fun `all-emoji input collapses to blank`() {
        assertEquals("", SpeechTextSanitizer.stripEmoji("😊🙌✨"))
    }
}
