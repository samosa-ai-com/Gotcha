package com.gotcha.data

import com.gotcha.audio.AudioProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {

    @Test
    fun `effectiveTtsApiKey falls back to effectiveApiKey when ttsApiKey is blank and provider is API`() {
        val settings = Settings(
            apiKey = "main-key",
            ttsProvider = AudioProvider.API,
            ttsApiKey = ""
        )
        assertEquals("main-key", settings.effectiveTtsApiKey)
    }

    @Test
    fun `effectiveTtsApiKey uses ttsApiKey when non-blank and provider is API`() {
        val settings = Settings(
            apiKey = "main-key",
            ttsProvider = AudioProvider.API,
            ttsApiKey = "custom-tts-key"
        )
        assertEquals("custom-tts-key", settings.effectiveTtsApiKey)
    }

    @Test
    fun `effectiveSttApiKey falls back to effectiveApiKey when sttApiKey is blank and provider is API`() {
        val settings = Settings(
            apiKey = "main-key",
            sttProvider = AudioProvider.API,
            sttApiKey = ""
        )
        assertEquals("main-key", settings.effectiveSttApiKey)
    }

    @Test
    fun `effectiveSttApiKey uses sttApiKey when non-blank and provider is API`() {
        val settings = Settings(
            apiKey = "main-key",
            sttProvider = AudioProvider.API,
            sttApiKey = "custom-stt-key"
        )
        assertEquals("custom-stt-key", settings.effectiveSttApiKey)
    }

    @Test
    fun `effectiveTtsApiKey is the Samosa session token when provider is SAMOSA_AI`() {
        val settings = Settings(
            apiKey = "main-key",
            ttsProvider = AudioProvider.SAMOSA_AI,
            samosaSessionToken = "samosa-jwt",
            ttsApiKey = "ignored-tts-key"
        )
        assertEquals("samosa-jwt", settings.effectiveTtsApiKey)
    }

    @Test
    fun `effectiveSttApiKey is the Samosa session token when provider is SAMOSA_AI`() {
        val settings = Settings(
            apiKey = "main-key",
            sttProvider = AudioProvider.SAMOSA_AI,
            samosaSessionToken = "samosa-jwt",
            sttApiKey = "ignored-stt-key"
        )
        assertEquals("samosa-jwt", settings.effectiveSttApiKey)
    }

    @Test
    fun `effectiveTtsApiKey is empty when provider is Android build-in`() {
        val settings = Settings(
            apiKey = "main-key",
            ttsProvider = AudioProvider.ANDROID
        )
        assertEquals("", settings.effectiveTtsApiKey)
    }

    @Test
    fun `effectiveSttApiKey is empty when provider is Android build-in`() {
        val settings = Settings(
            apiKey = "main-key",
            sttProvider = AudioProvider.ANDROID
        )
        assertEquals("", settings.effectiveSttApiKey)
    }

    @Test
    fun `effectiveTtsBaseUrl is the Samosa base URL when provider is SAMOSA_AI and session exists`() {
        val settings = Settings(
            ttsProvider = AudioProvider.SAMOSA_AI,
            samosaSessionToken = "samosa-jwt",
            ttsApiBaseUrl = "https://user-namespace.example/v1"
        )
        assertEquals(LlmProvider.SAMOSA_BASE_URL, settings.effectiveTtsBaseUrl)
    }

    @Test
    fun `effectiveSttBaseUrl is the Samosa base URL when provider is SAMOSA_AI and session exists`() {
        val settings = Settings(
            sttProvider = AudioProvider.SAMOSA_AI,
            samosaSessionToken = "samosa-jwt",
            sttApiBaseUrl = "https://user-namespace.example/v1"
        )
        assertEquals(LlmProvider.SAMOSA_BASE_URL, settings.effectiveSttBaseUrl)
    }

    @Test
    fun `effectiveTtsBaseUrl is empty when Samosa provider has no session token`() {
        val settings = Settings(
            ttsProvider = AudioProvider.SAMOSA_AI,
            samosaSessionToken = ""
        )
        // Empty so the engine refuses to make calls until the user re-signs in.
        assertEquals("", settings.effectiveTtsBaseUrl)
    }

    @Test
    fun `effectiveSttBaseUrl is empty when Samosa provider has no session token`() {
        val settings = Settings(
            sttProvider = AudioProvider.SAMOSA_AI,
            samosaSessionToken = ""
        )
        assertEquals("", settings.effectiveSttBaseUrl)
    }

    @Test
    fun `effectiveTtsBaseUrl is the user-provided URL when provider is API`() {
        val settings = Settings(
            ttsProvider = AudioProvider.API,
            ttsApiBaseUrl = "https://user-namespace.example/v1"
        )
        assertEquals("https://user-namespace.example/v1", settings.effectiveTtsBaseUrl)
    }

    @Test
    fun `effectiveSttBaseUrl is the user-provided URL when provider is API`() {
        val settings = Settings(
            sttProvider = AudioProvider.API,
            sttApiBaseUrl = "https://user-namespace.example/v1"
        )
        assertEquals("https://user-namespace.example/v1", settings.effectiveSttBaseUrl)
    }

    @Test
    fun `effectiveTtsBaseUrl is empty when provider is Android`() {
        val settings = Settings(ttsProvider = AudioProvider.ANDROID)
        assertEquals("", settings.effectiveTtsBaseUrl)
    }

    @Test
    fun `effectiveSttBaseUrl is empty when provider is Android`() {
        val settings = Settings(sttProvider = AudioProvider.ANDROID)
        assertEquals("", settings.effectiveSttBaseUrl)
    }

    @Test
    fun `Samosa audio providers work independently of the LLM provider`() {
        val settings = Settings(
            provider = LlmProvider.OPENAI_COMPATIBLE,
            apiKey = "openai-key",
            ttsProvider = AudioProvider.SAMOSA_AI,
            samosaSessionToken = "samosa-jwt"
        )
        // LLM uses OpenAI key…
        assertEquals("openai-key", settings.effectiveApiKey)
        // …TTS uses the Samosa session token.
        assertEquals("samosa-jwt", settings.effectiveTtsApiKey)
        assertEquals(LlmProvider.SAMOSA_BASE_URL, settings.effectiveTtsBaseUrl)
    }

    @Test
    fun `isSpeechConfigured is true when both providers are Android`() {
        val settings = Settings(
            ttsProvider = AudioProvider.ANDROID,
            sttProvider = AudioProvider.ANDROID
        )
        assertEquals(true, settings.isSpeechConfigured)
    }

    @Test
    fun `isSpeechConfigured is true when TTS is Samosa and STT is Android with session`() {
        val settings = Settings(
            ttsProvider = AudioProvider.SAMOSA_AI,
            sttProvider = AudioProvider.ANDROID,
            samosaSessionToken = "jwt"
        )
        assertEquals(true, settings.isSpeechConfigured)
    }

    @Test
    fun `isSpeechConfigured is false when Samosa is selected but no session token`() {
        val settings = Settings(
            ttsProvider = AudioProvider.SAMOSA_AI,
            sttProvider = AudioProvider.ANDROID,
            samosaSessionToken = ""
        )
        assertEquals(false, settings.isSpeechConfigured)
    }

    @Test
    fun `isSpeechConfigured is false when Samosa is selected for STT but not for TTS with no session`() {
        val settings = Settings(
            ttsProvider = AudioProvider.ANDROID,
            sttProvider = AudioProvider.SAMOSA_AI,
            samosaSessionToken = ""
        )
        assertEquals(false, settings.isSpeechConfigured)
    }

    @Test
    fun `isSpeechConfigured is true when API providers have base URLs configured`() {
        val settings = Settings(
            ttsProvider = AudioProvider.API,
            ttsApiBaseUrl = "https://user.example/v1",
            sttProvider = AudioProvider.API,
            sttApiBaseUrl = "https://user.example/v1"
        )
        assertEquals(true, settings.isSpeechConfigured)
    }

    @Test
    fun `isSpeechConfigured is false when API TTS provider has no base URL`() {
        val settings = Settings(
            ttsProvider = AudioProvider.API,
            ttsApiBaseUrl = "",
            sttProvider = AudioProvider.API,
            sttApiBaseUrl = "https://user.example/v1"
        )
        assertEquals(false, settings.isSpeechConfigured)
    }

    @Test
    fun `isSpeechConfigured is false when API STT provider has no base URL`() {
        val settings = Settings(
            ttsProvider = AudioProvider.API,
            ttsApiBaseUrl = "https://user.example/v1",
            sttProvider = AudioProvider.API,
            sttApiBaseUrl = ""
        )
        assertEquals(false, settings.isSpeechConfigured)
    }

    @Test
    fun `isSpeechConfigured is true when both providers are None`() {
        val settings = Settings(
            ttsProvider = AudioProvider.NONE,
            sttProvider = AudioProvider.NONE
        )
        assertEquals(true, settings.isSpeechConfigured)
    }
}
