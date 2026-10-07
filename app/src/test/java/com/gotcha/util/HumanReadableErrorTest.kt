package com.gotcha.util

import android.content.Context
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.gotcha.i18n.stringLookup
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
class HumanReadableErrorTest {

    private val strings = ApplicationProvider.getApplicationContext<Context>().stringLookup()

    @Test
    fun testFromHttpCodeKnownAndFallbackCodes() {
        assertTrue(HumanReadableError.fromHttpCode(400, strings = strings).contains("Bad request"))
        assertTrue(HumanReadableError.fromHttpCode(401, strings = strings).contains("Authentication failed"))
        assertTrue(HumanReadableError.fromHttpCode(403, strings = strings).contains("Access restricted"))
        assertTrue(HumanReadableError.fromHttpCode(404, strings = strings).contains("Resource not found"))
        assertTrue(HumanReadableError.fromHttpCode(408, strings = strings).contains("Request timeout"))
        assertTrue(HumanReadableError.fromHttpCode(429, strings = strings).contains("Rate limit"))
        assertTrue(HumanReadableError.fromHttpCode(500, strings = strings).contains("Server error"))
        assertTrue(HumanReadableError.fromHttpCode(502, strings = strings).contains("Bad gateway"))
        assertTrue(HumanReadableError.fromHttpCode(503, strings = strings).contains("Service unavailable"))
        assertTrue(HumanReadableError.fromHttpCode(504, strings = strings).contains("Gateway timeout"))

        val fallback = HumanReadableError.fromHttpCode(418, "I'm a teapot", strings = strings)
        assertTrue(fallback.contains("HTTP 418"))
        assertTrue(fallback.contains("I'm a teapot"))
    }

    @Test
    fun testFromSpeechRecognizerCodeKnownAndFallbackCodes() {
        val errTimeout = HumanReadableError.fromSpeechRecognizerCode(
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            strings = strings
        )
        assertTrue(errTimeout.contains("timed out"))

        val errNet = HumanReadableError.fromSpeechRecognizerCode(SpeechRecognizer.ERROR_NETWORK, strings = strings)
        assertTrue(errNet.contains("Network connection error"))

        val errAudio = HumanReadableError.fromSpeechRecognizerCode(SpeechRecognizer.ERROR_AUDIO, strings = strings)
        assertTrue(errAudio.contains("Microphone hardware error"))

        val errServer = HumanReadableError.fromSpeechRecognizerCode(SpeechRecognizer.ERROR_SERVER, strings = strings)
        assertTrue(errServer.contains("server error"))

        val errClient = HumanReadableError.fromSpeechRecognizerCode(SpeechRecognizer.ERROR_CLIENT, strings = strings)
        assertTrue(errClient.contains("client error"))

        val errSpeechTimeout = HumanReadableError.fromSpeechRecognizerCode(
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            strings = strings
        )
        assertTrue(errSpeechTimeout.contains("No speech detected"))

        val errNoMatch = HumanReadableError.fromSpeechRecognizerCode(SpeechRecognizer.ERROR_NO_MATCH, strings = strings)
        assertTrue(errNoMatch.contains("Could not understand speech"))

        val errBusy = HumanReadableError.fromSpeechRecognizerCode(
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            strings = strings
        )
        assertTrue(errBusy.contains("recognizer is busy"))

        val errPerm = HumanReadableError.fromSpeechRecognizerCode(
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            strings = strings
        )
        assertTrue(errPerm.contains("Microphone permission"))

        val errRate = HumanReadableError.fromSpeechRecognizerCode(
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
            strings = strings
        )
        assertTrue(errRate.contains("Too many speech recognition"))

        val errDisc = HumanReadableError.fromSpeechRecognizerCode(
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            strings = strings
        )
        assertTrue(errDisc.contains("disconnected"))

        val fallback = HumanReadableError.fromSpeechRecognizerCode(99, strings = strings)
        assertTrue(fallback.contains("code 99"))
    }

    @Test
    fun testFromTtsCodeKnownAndFallbackCodes() {
        val synthErr = HumanReadableError.fromTtsCode(TextToSpeech.ERROR_SYNTHESIS, strings = strings)
        assertTrue(synthErr.contains("Speech synthesis error"))

        val svcErr = HumanReadableError.fromTtsCode(TextToSpeech.ERROR_SERVICE, strings = strings)
        assertTrue(svcErr.contains("service error"))

        val fallback = HumanReadableError.fromTtsCode(99, strings = strings)
        assertTrue(fallback.contains("code 99"))
    }

    @Test
    fun testFormatExceptions() {
        val httpEx = HttpException(Response.error<String>(401, "".toResponseBody(null)))
        assertTrue(HumanReadableError.format(httpEx, strings = strings).contains("Authentication failed"))

        val unknownHost = UnknownHostException("api.openai.com")
        assertTrue(HumanReadableError.format(unknownHost, strings = strings).contains("Cannot connect to server"))

        val connectEx = ConnectException("Failed to connect")
        assertTrue(HumanReadableError.format(connectEx, strings = strings).contains("Connection refused"))

        val timeout = SocketTimeoutException("Read timed out")
        assertTrue(HumanReadableError.format(timeout, strings = strings).contains("Connection timed out"))

        val ioWithHttp = IOException("HTTP 429: Too Many Requests")
        assertTrue(HumanReadableError.format(ioWithHttp, strings = strings).contains("Rate limit"))

        val genericIo = IOException("Disk read failed")
        assertTrue(
            HumanReadableError.format(genericIo, strings = strings).contains("Network problem: Disk read failed")
        )

        val secEx = SecurityException("RECORD_AUDIO missing")
        assertTrue(HumanReadableError.format(secEx, strings = strings).contains("Permission denied"))

        val genericEx = IllegalStateException("State invalid")
        assertTrue(HumanReadableError.format(genericEx, strings = strings).contains("Error: State invalid"))

        val tierGating403 = HttpException(
            Response.error<String>(
                403,
                "{\"detail\":\"Upgrade to Pro to access this model\"}".toResponseBody(null)
            )
        )
        assertTrue(HumanReadableError.format(tierGating403, strings = strings).contains("Upgrade to Pro"))

        val emptyEx = RuntimeException("")
        assertTrue(HumanReadableError.format(emptyEx, strings = strings).contains("An unexpected error occurred"))
    }

    @Test
    fun `an HTTP rejection that names images explains how to recover`() {
        val msg = HumanReadableError.fromHttpCode(400, "Too many images in request. Max is 3.", strings = strings)
        assertTrue(msg.contains("rejected the attached images"))
        assertTrue(msg.contains("Too many images in request. Max is 3."))
        assertTrue(msg.contains("Remove some attachments"))

        val tooLarge = HumanReadableError.fromHttpCode(413, "image exceeds 5 MB maximum", strings = strings)
        assertTrue(tooLarge.contains("rejected the attached images"))
    }

    @Test
    fun `an HTTP rejection without images keeps its usual explanation`() {
        assertTrue(HumanReadableError.fromHttpCode(400, "bad json", strings = strings).contains("Bad request"))
        assertTrue(HumanReadableError.fromHttpCode(413, strings = strings).contains("Request too large"))
        // Only request errors are reinterpreted, not e.g. auth failures that mention images.
        assertTrue(
            HumanReadableError.fromHttpCode(
                401,
                "image model requires auth",
                strings = strings
            ).contains("Authentication failed")
        )
    }
}
