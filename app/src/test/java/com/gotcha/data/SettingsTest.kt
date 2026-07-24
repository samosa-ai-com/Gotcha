package com.gotcha.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {

    @Test
    fun `effectiveTtsApiKey falls back to effectiveApiKey when ttsApiKey is blank`() {
        val settings = Settings(apiKey = "main-key", ttsApiKey = "")
        assertEquals("main-key", settings.effectiveTtsApiKey)
    }

    @Test
    fun `effectiveTtsApiKey uses ttsApiKey when non-blank`() {
        val settings = Settings(apiKey = "main-key", ttsApiKey = "custom-tts-key")
        assertEquals("custom-tts-key", settings.effectiveTtsApiKey)
    }

    @Test
    fun `effectiveSttApiKey falls back to effectiveApiKey when sttApiKey is blank`() {
        val settings = Settings(apiKey = "main-key", sttApiKey = "")
        assertEquals("main-key", settings.effectiveSttApiKey)
    }

    @Test
    fun `effectiveSttApiKey uses sttApiKey when non-blank`() {
        val settings = Settings(apiKey = "main-key", sttApiKey = "custom-stt-key")
        assertEquals("custom-stt-key", settings.effectiveSttApiKey)
    }
}
