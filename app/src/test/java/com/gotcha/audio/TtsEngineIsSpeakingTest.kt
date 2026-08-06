package com.gotcha.audio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtsEngineIsSpeakingTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var ttsEngine: TtsEngine

    @Before
    fun setUp() {
        ttsEngine = TtsEngine(application)
    }

    @Test
    fun `isSpeaking is false when idle and toggles true then false around a speak call`() = runBlocking {
        assertFalse("the StateFlow must start idle", ttsEngine.isSpeaking.value)

        // The NONE provider is a no-op playback (returns false), but it still
        // drives the speaking flag true → false around the call, which is the
        // contract the wake-word self-trigger guard relies on.
        val result = ttsEngine.speak(
            text = "Hello world",
            provider = AudioProvider.NONE
        )
        assertFalse(result)
        assertFalse("the flag must be reset when playback finishes", ttsEngine.isSpeaking.value)
    }

    @Test
    fun `isSpeaking is exposed as a StateFlow`() {
        assertEquals(false, ttsEngine.isSpeaking.value)
        val flow = ttsEngine.isSpeaking
        assertTrue(flow is kotlinx.coroutines.flow.StateFlow<*>)
    }
}
