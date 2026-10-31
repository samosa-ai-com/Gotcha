package com.gotcha.audio

import android.app.Application
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.gotcha.i18n.Language
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun `a picked engine rebinds Android text-to-speech, the same one does not`() = runBlocking {
        val first = ShadowTextToSpeech.getLastTextToSpeechInstance()
        ttsEngine.speak("Привет", AudioProvider.ANDROID, language = Language.RUSSIAN)
        assertSame("the phone's engine is already bound", first, ShadowTextToSpeech.getLastTextToSpeechInstance())

        // The new binding never initialises here, so speak gives up waiting; only the rebind matters.
        withTimeoutOrNull(500) {
            ttsEngine.speak(
                "Привет",
                AudioProvider.ANDROID,
                language = Language.RUSSIAN,
                androidEngine = "com.example.tts"
            )
        }
        assertNotSame(first, ShadowTextToSpeech.getLastTextToSpeechInstance())
        assertTrue("the old binding is released", shadowTts.isShutdown)
    }

    @Test
    fun `fallback skips the request, the memoized winner and the bound engine`() {
        // Blank request with Samsung bound: only Samsung is skipped, so the
        // default engine is never rebound under its own package name.
        assertEquals(
            setOf("com.samsung.SMT"),
            ttsSkipIds("", null, "com.samsung.SMT")
        )
        // Explicit request: it and the bound engine are skipped.
        assertEquals(
            setOf("com.google.android.tts"),
            ttsSkipIds("com.google.android.tts", null, "com.google.android.tts")
        )
        // A memoized winner being re-probed is skipped with the rest.
        assertEquals(
            setOf("com.google.android.tts", "com.samsung.SMT"),
            ttsSkipIds("", "com.google.android.tts", "com.samsung.SMT")
        )
        assertEquals(emptySet<String>(), ttsSkipIds("", null, null))
    }
}
