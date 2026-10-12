package com.gotcha.audio

import android.app.Application
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.gotcha.i18n.Language
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech

/** Android text-to-speech without a voice for the language says nothing (#122). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtsEngineLanguageTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var ttsEngine: TtsEngine
    private lateinit var shadowTts: ShadowTextToSpeech

    @Before
    fun setUp() {
        ttsEngine = TtsEngine(application)
        shadowTts = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        shadowTts.onInitListener.onInit(TextToSpeech.SUCCESS)
    }

    @Test
    fun `a missing voice speaks nothing rather than falling back to English`() = runBlocking {
        val spoke = ttsEngine.speak("Привет", AudioProvider.ANDROID, language = Language.RUSSIAN)

        assertFalse(spoke)
        assertEquals(Language.RUSSIAN, ttsEngine.lastLanguageUnavailable)
        assertNull("nothing should reach the engine", shadowTts.lastSpokenText)
    }

    @Test
    fun `the next speak clears the missing language`() = runBlocking {
        ttsEngine.speak("Привет", AudioProvider.ANDROID, language = Language.RUSSIAN)
        ttsEngine.speak("Hello", AudioProvider.NONE)

        assertNull(ttsEngine.lastLanguageUnavailable)
    }

    @Test
    fun `androidSupports follows the installed voices`() = runBlocking {
        assertEquals(false, ttsEngine.androidSupports(Language.RUSSIAN))

        ShadowTextToSpeech.addLanguageAvailability(Language.RUSSIAN.locale)

        assertTrue(ttsEngine.androidSupports(Language.RUSSIAN) == true)
    }
}
