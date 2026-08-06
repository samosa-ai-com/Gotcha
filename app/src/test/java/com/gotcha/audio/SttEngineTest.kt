package com.gotcha.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Covers [SttEngine.isBenignSttError] — the classification a hands-free listen
 * loop uses to tell "the user stayed quiet" apart from a genuine STT failure.
 */
class SttEngineTest {

    // Android SpeechRecognizer.ERROR_* values (kept as literals so this pure
    // unit test never touches the android.jar stubs).
    private val errorSpeechTimeout = 6
    private val errorNoMatch = 7
    private val errorClient = 5
    private val errorRecognizerBusy = 8
    private val errorInsufficientPermissions = 9

    @Test
    fun `a blank success result is benign silence`() {
        assertTrue(SttEngine.isBenignSttError(null))
    }

    @Test
    fun `no speech detected is benign silence`() {
        assertTrue(SttEngine.isBenignSttError(Exception("No speech detected")))
    }

    @Test
    fun `recognizer no-match and speech-timeout errors are benign silence`() {
        assertTrue(SttEngine.isBenignSttError(Exception("Speech recognition failed: $errorSpeechTimeout")))
        assertTrue(SttEngine.isBenignSttError(Exception("Speech recognition failed: $errorNoMatch")))
    }

    @Test
    fun `real recognizer errors are not benign`() {
        assertFalse(SttEngine.isBenignSttError(Exception("Speech recognition failed: $errorClient")))
        assertFalse(SttEngine.isBenignSttError(Exception("Speech recognition failed: $errorRecognizerBusy")))
        assertFalse(SttEngine.isBenignSttError(Exception("Speech recognition failed: $errorInsufficientPermissions")))
    }

    @Test
    fun `api and network failures are not benign`() {
        assertFalse(SttEngine.isBenignSttError(Exception("API not configured")))
        assertFalse(SttEngine.isBenignSttError(IOException("Network problem: could not reach the server")))
        assertFalse(SttEngine.isBenignSttError(Exception("HTTP 401 Authentication failed")))
    }

    @Test
    fun `an error without a message is not benign`() {
        assertFalse(SttEngine.isBenignSttError(Exception()))
    }
}
