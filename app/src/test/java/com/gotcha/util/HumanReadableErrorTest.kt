package com.gotcha.util

import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class HumanReadableErrorTest {

    @Test
    fun testFromHttpCodeKnownAndFallbackCodes() {
        assertTrue(HumanReadableError.fromHttpCode(400).contains("Bad request"))
        assertTrue(HumanReadableError.fromHttpCode(401).contains("Authentication failed"))
        assertTrue(HumanReadableError.fromHttpCode(403).contains("Access restricted"))
        assertTrue(HumanReadableError.fromHttpCode(404).contains("Resource not found"))
        assertTrue(HumanReadableError.fromHttpCode(408).contains("Request timeout"))
        assertTrue(HumanReadableError.fromHttpCode(429).contains("Rate limit"))
        assertTrue(HumanReadableError.fromHttpCode(500).contains("Server error"))
        assertTrue(HumanReadableError.fromHttpCode(502).contains("Bad gateway"))
        assertTrue(HumanReadableError.fromHttpCode(503).contains("Service unavailable"))
        assertTrue(HumanReadableError.fromHttpCode(504).contains("Gateway timeout"))

        val fallback = HumanReadableError.fromHttpCode(418, "I'm a teapot")
        assertTrue(fallback.contains("HTTP 418"))
        assertTrue(fallback.contains("I'm a teapot"))
    }

    @Test
    fun testFromSpeechRecognizerCodeKnownAndFallbackCodes() {
        val errTimeout = HumanReadableError.fromSpeechRecognizerCode(SpeechRecognizer.ERROR_NETWORK_TIMEOUT)
        assertTrue(errTimeout.contains("timed out"))

        val errNet = HumanReadableError.fromSpeechRecognizerCode(SpeechRecognizer.ERROR_NETWORK)
        assertTrue(errNet.contains("Network connection error"))

        val errAudio = HumanReadableError.fromSpeechRecognizerCode(SpeechRecognizer.ERROR_AUDIO)
        assertTrue(errAudio.contains("Microphone hardware error"))

        val errServer = HumanReadableError.fromSpeechRecognizerCode(SpeechRecognizer.ERROR_SERVER)
        assertTrue(errServer.contains("server error"))

        val errClient = HumanReadableError.fromSpeechRecognizerCode(SpeechRecognizer.ERROR_CLIENT)
        assertTrue(errClient.contains("client error"))

        val errSpeechTimeout = HumanReadableError.fromSpeechRecognizerCode(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        assertTrue(errSpeechTimeout.contains("No speech detected"))

        val errNoMatch = HumanReadableError.fromSpeechRecognizerCode(SpeechRecognizer.ERROR_NO_MATCH)
        assertTrue(errNoMatch.contains("Could not understand speech"))

        val errBusy = HumanReadableError.fromSpeechRecognizerCode(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        assertTrue(errBusy.contains("recognizer is busy"))

        val errPerm = HumanReadableError.fromSpeechRecognizerCode(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        assertTrue(errPerm.contains("Microphone permission"))

        val errRate = HumanReadableError.fromSpeechRecognizerCode(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS)
        assertTrue(errRate.contains("Too many speech recognition"))

        val errDisc = HumanReadableError.fromSpeechRecognizerCode(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
        assertTrue(errDisc.contains("disconnected"))

        val fallback = HumanReadableError.fromSpeechRecognizerCode(99)
        assertTrue(fallback.contains("code 99"))
    }

    @Test
    fun testFromTtsCodeKnownAndFallbackCodes() {
        val synthErr = HumanReadableError.fromTtsCode(TextToSpeech.ERROR_SYNTHESIS)
        assertTrue(synthErr.contains("Speech synthesis error"))

        val svcErr = HumanReadableError.fromTtsCode(TextToSpeech.ERROR_SERVICE)
        assertTrue(svcErr.contains("service error"))

        val fallback = HumanReadableError.fromTtsCode(99)
        assertTrue(fallback.contains("code 99"))
    }

    @Test
    fun testFormatExceptions() {
        val httpEx = HttpException(Response.error<String>(401, "".toResponseBody(null)))
        assertTrue(HumanReadableError.format(httpEx).contains("Authentication failed"))

        val unknownHost = UnknownHostException("api.openai.com")
        assertTrue(HumanReadableError.format(unknownHost).contains("Cannot connect to server"))

        val connectEx = ConnectException("Failed to connect")
        assertTrue(HumanReadableError.format(connectEx).contains("Connection refused"))

        val timeout = SocketTimeoutException("Read timed out")
        assertTrue(HumanReadableError.format(timeout).contains("Connection timed out"))

        val ioWithHttp = IOException("HTTP 429: Too Many Requests")
        assertTrue(HumanReadableError.format(ioWithHttp).contains("Rate limit"))

        val genericIo = IOException("Disk read failed")
        assertTrue(HumanReadableError.format(genericIo).contains("Network problem: Disk read failed"))

        val secEx = SecurityException("RECORD_AUDIO missing")
        assertTrue(HumanReadableError.format(secEx).contains("Permission denied"))

        val genericEx = IllegalStateException("State invalid")
        assertTrue(HumanReadableError.format(genericEx).contains("Error: State invalid"))

        val tierGating403 = HttpException(
            Response.error<String>(
                403,
                "{\"detail\":\"Upgrade to Pro to access this model\"}".toResponseBody(null)
            )
        )
        assertTrue(HumanReadableError.format(tierGating403).contains("Upgrade to Pro"))

        val emptyEx = RuntimeException("")
        assertTrue(HumanReadableError.format(emptyEx).contains("An unexpected error occurred"))
    }
}
