package com.gotcha.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioLanguageLabelsTest {

    @Test
    fun `plain and regional codes get English names`() {
        assertEquals("Hindi", AudioLanguageLabels.describe("hi"))
        assertEquals("English (United States)", AudioLanguageLabels.describe("en-us"))
        assertEquals("Portuguese (Brazil)", AudioLanguageLabels.describe("pt_BR"))
        assertEquals("Hawaiian", AudioLanguageLabels.describe("haw"))
    }

    @Test
    fun `codes the JDK does not know use overrides or return null`() {
        assertEquals("Javanese", AudioLanguageLabels.describe("jw"))
        assertEquals("Multiple languages", AudioLanguageLabels.describe("multilingual"))
        assertNull(AudioLanguageLabels.describe("qq"))
        assertNull(AudioLanguageLabels.describe(""))
    }

    @Test
    fun `label pairs the code with its name and falls back to the bare code`() {
        assertEquals("hi — Hindi", AudioLanguageLabels.label("hi"))
        assertEquals("qq", AudioLanguageLabels.label("qq"))
    }

    @Test
    fun `voice label infers language and gender from Kokoro ids`() {
        assertEquals("bm_george — English (United Kingdom), male", VoiceInfo(id = "bm_george").displayLabel)
        assertEquals("hf_alpha — Hindi, female", VoiceInfo(id = "hf_alpha").displayLabel)
        assertEquals("custom-voice", VoiceInfo(id = "custom-voice").displayLabel)
    }

    @Test
    fun `ids from other servers are not read as Kokoro ids`() {
        // Piper ids share Kokoro's first two letters but mean language plus region.
        assertNull(AudioLanguageLabels.languageFromVoiceId("am_ET-amharic-medium"))
        assertNull(AudioLanguageLabels.genderFromVoiceId("af_ZA-google-nwu"))
        assertEquals("am_ET-amharic-medium", VoiceInfo(id = "am_ET-amharic-medium").displayLabel)
    }

    @Test
    fun `a server-sent language is never paired with a gender guessed from the id`() {
        assertEquals(
            "af_heart — Afrikaans",
            VoiceInfo(id = "af_heart", language = "af").displayLabel
        )
    }

    @Test
    fun `voice label prefers server metadata and keeps a distinct name`() {
        assertEquals(
            "v1 — Priya, Hindi (India), female",
            VoiceInfo(id = "v1", name = "Priya", language = "hi-IN", gender = "female").displayLabel
        )
    }
}
