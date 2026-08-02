package com.gotcha.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageTest {

    @Test
    fun `fromLabel round-trips every entry`() {
        for (lang in Language.entries) {
            assertEquals(lang, Language.fromLabel(lang.label))
        }
    }

    @Test
    fun `fromLabel is case-insensitive and trims whitespace`() {
        assertEquals(Language.HINDI, Language.fromLabel("  hindi  "))
        assertEquals(Language.FRENCH, Language.fromLabel("FRENCH"))
    }

    @Test
    fun `fromLabel falls back to English for unknown, null, or blank`() {
        assertEquals(Language.ENGLISH, Language.fromLabel(null))
        assertEquals(Language.ENGLISH, Language.fromLabel(""))
        assertEquals(Language.ENGLISH, Language.fromLabel("Klingon"))
        assertEquals(Language.ENGLISH, Language.fromLabel("Esperanto"))
    }

    @Test
    fun `every bcp47 tag parses to a non-empty locale`() {
        for (lang in Language.entries) {
            val locale = lang.locale
            assertTrue("locale for ${lang.label} should have a language", locale.language.isNotBlank())
        }
    }

    @Test
    fun `labels matches the Settings dropdown source of truth`() {
        // Drift guard for design decision D1: the Settings dropdown is derived
        // from Language.labels, so this list is the single source of truth.
        val expected = listOf(
            "English", "Spanish", "French", "German", "Hindi",
            "Japanese", "Chinese", "Italian", "Portuguese"
        )
        assertEquals(expected, Language.labels)
    }
}
