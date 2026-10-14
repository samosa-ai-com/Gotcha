package com.gotcha.audio

import android.app.Application
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import com.gotcha.i18n.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

/** Android Built-in push-to-talk with the recognizer the user picked (#122). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SttEngineAndroidSessionTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var stt: SttEngine

    @Before
    fun setUp() {
        stt = SttEngine(application)
        stt.configureAndroid(service = "", onDeviceOnly = false)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun latest() = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())

    private fun requestedTag() = latest().lastRecognizerIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)

    private fun installed(vararg tags: String) =
        RecognitionSupport.Builder().setInstalledOnDeviceLanguages(tags.toList()).build()

    @Test
    fun `the installed variant is asked for, not the default tag`() {
        stt.startAndroidListening(Language.ENGLISH)
        idle()
        latest().triggerSupportResult(installed("en-IN"))
        idle()

        assertEquals("a phone with only Indian English must get en-IN", "en-IN", requestedTag())
    }

    @Test
    fun `the default tag is used when the recognizer can't say`() {
        stt.startAndroidListening(Language.ENGLISH)
        idle()
        latest().triggerSupportError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
        idle()

        assertEquals("en-US", requestedTag())
    }

    @Test
    fun `a language the recognizer lacks ends the session at once with the reason`() {
        var reason: String? = null
        stt.startAndroidListening(Language.RUSSIAN) { reason = it }
        idle()
        latest().triggerSupportResult(installed("en-IN"))
        idle()
        val recognizer = latest()

        recognizer.triggerOnError(13) // ERROR_LANGUAGE_UNAVAILABLE
        idle()

        assertTrue(reason.orEmpty(), reason.orEmpty().contains("Russian"))
        assertTrue("the failed recognizer is not kept for the next session", recognizer.isDestroyed)
        assertNull("the reason went to the caller, not left for Stop", stt.androidFailure)
    }

    @Test
    fun `a new recognizer choice takes effect on the next session`() {
        stt.startAndroidListening(Language.ENGLISH)
        idle()
        latest().triggerSupportError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
        idle()
        val first = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        stt.cancelListening(AudioProvider.ANDROID)
        idle()

        stt.configureAndroid(service = "com.example/.Recognizer", onDeviceOnly = false)
        stt.startAndroidListening(Language.ENGLISH)
        idle()

        assertNotSame(first, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
    }
}
