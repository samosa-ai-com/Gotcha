package com.gotcha.i18n

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenPhrasesTest {

    @Test
    fun `every language returns a non-empty weighted turnStart list`() {
        for (lang in Language.entries) {
            val phrases = SpokenPhrases.turnStart(lang)
            assertTrue("turnStart(${lang.label}) should not be empty", phrases.isNotEmpty())
            assertTrue(
                "turnStart(${lang.label}) should have positive weights",
                phrases.all { it.second > 0 }
            )
        }
    }

    @Test
    fun `no non-English language returns the English turnStart phrase set`() {
        val english = SpokenPhrases.turnStart(Language.ENGLISH)
        for (lang in Language.entries - Language.ENGLISH) {
            assertNotEquals(
                "turnStart(${lang.label}) should not silently fall back to English",
                english,
                SpokenPhrases.turnStart(lang)
            )
        }
    }

    @Test
    fun `every language has a non-blank callStarted phrase`() {
        for (lang in Language.entries) {
            assertTrue(SpokenPhrases.callStarted(lang).isNotBlank())
        }
    }

    @Test
    fun `no non-English language returns the English callStarted phrase`() {
        val english = SpokenPhrases.callStarted(Language.ENGLISH)
        for (lang in Language.entries - Language.ENGLISH) {
            assertNotEquals(english, SpokenPhrases.callStarted(lang))
        }
    }

    @Test
    fun `every language has a non-blank confirmationNeeded phrase`() {
        for (lang in Language.entries) {
            assertTrue(SpokenPhrases.confirmationNeeded(lang).isNotBlank())
        }
    }

    @Test
    fun `no non-English language returns the English confirmationNeeded phrase`() {
        val english = SpokenPhrases.confirmationNeeded(Language.ENGLISH)
        for (lang in Language.entries - Language.ENGLISH) {
            assertNotEquals(english, SpokenPhrases.confirmationNeeded(lang))
        }
    }
}
