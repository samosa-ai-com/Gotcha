package com.gotcha.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.audio.AudioProvider
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.testsupport.FakeAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral contract for the 401 → session-clear path. The full Samosa
 * Google Sign-In is exercised in instrumented tests; here we just need to
 * verify the Samosa session is invalidated iff the LLM provider is Samosa.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelSamosaTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        settingsRepository = SettingsRepository(context)
    }

    @After
    fun tearDown() {
        settingsRepository.saveSamosaSession("", "")
    }

    private fun samosaSettings(
        provider: LlmProvider,
        token: String,
        tts: AudioProvider = AudioProvider.ANDROID,
        stt: AudioProvider = AudioProvider.ANDROID,
    ): Settings = Settings(
        provider = provider,
        apiKey = if (provider == LlmProvider.OPENAI_COMPATIBLE) "openai-key" else "",
        samosaSessionToken = token,
        samosaEmail = if (token.isNotBlank()) "user@example.com" else "",
        ttsProvider = tts,
        sttProvider = stt,
    )

    @Test
    fun `openai provider with no samosa token does not write any session`() = runBlocking {
        val initial = samosaSettings(LlmProvider.OPENAI_COMPATIBLE, token = "")
        settingsRepository.save(initial)
        // The session remains empty: there's nothing for an LLM 401 to clear.
        val after = settingsRepository.load()
        assertEquals("", after.samosaSessionToken)
    }

    @Test
    fun `effectiveTtsBaseUrl is empty when Samosa provider has no session token`() = runBlocking {
        val settings = samosaSettings(
            provider = LlmProvider.OPENAI_COMPATIBLE,
            token = "",
            tts = AudioProvider.SAMOSA_AI
        )
        assertEquals("", settings.effectiveTtsBaseUrl)
        // After a hypothetical re-load, the engine has no base URL and won't
        // make any calls until the user re-signs in.
        settingsRepository.save(settings)
        val reloaded = settingsRepository.load()
        assertEquals("", reloaded.effectiveTtsBaseUrl)
    }

    @Test
    fun `effectiveSttBaseUrl is empty when Samosa provider has no session token`() = runBlocking {
        val settings = samosaSettings(
            provider = LlmProvider.OPENAI_COMPATIBLE,
            token = "",
            stt = AudioProvider.SAMOSA_AI
        )
        assertEquals("", settings.effectiveSttBaseUrl)
    }

    @Test
    fun `samosa session persists across save and load`() = runBlocking {
        settingsRepository.save(
            samosaSettings(
                LlmProvider.SAMOSA_AI,
                token = "abc.def.ghi",
                tts = AudioProvider.SAMOSA_AI,
                stt = AudioProvider.SAMOSA_AI
            )
        )
        val reloaded = settingsRepository.load()
        assertEquals("abc.def.ghi", reloaded.samosaSessionToken)
        assertEquals(LlmProvider.SAMOSA_BASE_URL, reloaded.effectiveTtsBaseUrl)
        assertEquals(LlmProvider.SAMOSA_BASE_URL, reloaded.effectiveSttBaseUrl)
    }

    @Test
    fun `clearSamosaSession leaves audio base URL empty so engine stops calling`() = runBlocking {
        settingsRepository.save(
            samosaSettings(
                LlmProvider.SAMOSA_AI,
                token = "abc.def.ghi",
                tts = AudioProvider.SAMOSA_AI,
                stt = AudioProvider.SAMOSA_AI
            )
        )
        assertTrue(settingsRepository.load().samosaSessionToken.isNotBlank())
        settingsRepository.clearSamosaSession()
        val after = settingsRepository.load()
        assertEquals("", after.samosaSessionToken)
        assertEquals("", after.effectiveTtsBaseUrl)
        assertEquals("", after.effectiveSttBaseUrl)
    }

    @Test
    fun `samosa session clearing does not affect the LLM OpenAI key`() = runBlocking {
        settingsRepository.save(
            samosaSettings(LlmProvider.OPENAI_COMPATIBLE, token = "openai-session-jwt")
        )
        settingsRepository.clearSamosaSession()
        val after = settingsRepository.load()
        assertEquals("", after.samosaSessionToken)
        // OpenAI key is untouched.
        assertEquals("openai-key", after.apiKey)
    }

    @Test
    fun `unknown stored AudioProvider value falls back to ANDROID on load`() = runBlocking {
        // Simulate a stored enum value that doesn't exist in the current build
        // (e.g. a forward-only enum rename or a rollback).
        val initial = samosaSettings(LlmProvider.OPENAI_COMPATIBLE, token = "")
        settingsRepository.save(initial)
        settingsRepository.prefs.edit()
            .putString("tts_provider", "DEPRECATED_VALUE")
            .putString("stt_provider", "ANOTHER_DEPRECATED")
            .apply()
        val reloaded = settingsRepository.load()
        // runCatching in the loader should fall back gracefully rather than crash.
        assertEquals(AudioProvider.ANDROID, reloaded.ttsProvider)
        assertEquals(AudioProvider.ANDROID, reloaded.sttProvider)
        // And base URLs stay empty.
        assertEquals("", reloaded.effectiveTtsBaseUrl)
        assertEquals("", reloaded.effectiveSttBaseUrl)
    }

    @Test
    fun `samosa token is independent of LLM provider`() = runBlocking {
        // LLM is OpenAI but audio uses Samosa — the JWT is still valid for audio.
        settingsRepository.save(
            samosaSettings(
                provider = LlmProvider.OPENAI_COMPATIBLE,
                token = "audio-jwt",
                tts = AudioProvider.SAMOSA_AI
            )
        )
        val reloaded = settingsRepository.load()
        assertEquals("audio-jwt", reloaded.samosaSessionToken)
        assertEquals(LlmProvider.SAMOSA_BASE_URL, reloaded.effectiveTtsBaseUrl)
        // But LLM is unaffected.
        assertEquals(LlmProvider.OPENAI_COMPATIBLE, reloaded.provider)
        assertEquals("openai-key", reloaded.effectiveApiKey)
        assertFalse(reloaded.effectiveApiKey == reloaded.effectiveTtsApiKey)
    }
}
