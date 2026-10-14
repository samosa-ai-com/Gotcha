package com.gotcha.audio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.gotcha.i18n.Language
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** On-device only never falls back to a network recognizer (#122). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SttEngineOnDeviceTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `on-device only on a phone without it refuses to start and says why`() {
        val stt = SttEngine(application)
        stt.configureAndroid(service = "", onDeviceOnly = true)

        assertFalse(stt.startAndroidListening(Language.RUSSIAN))
        assertTrue(stt.androidFailure.orEmpty(), stt.androidFailure.orEmpty().contains("On-device only"))
    }

    @Test
    fun `without on-device only there is no failure to report`() {
        val stt = SttEngine(application)
        stt.configureAndroid(service = "", onDeviceOnly = false)

        stt.startAndroidListening(Language.RUSSIAN)
        assertNull(stt.androidFailure)
        stt.cancelListening(AudioProvider.ANDROID)
    }
}
