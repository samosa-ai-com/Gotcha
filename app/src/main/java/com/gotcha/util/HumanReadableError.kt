package com.gotcha.util

import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Centralized translator for raw error codes, HTTP statuses, and subsystem exceptions
 * into clear, user-understandable explanations.
 */
object HumanReadableError {

    /** Maps HTTP status codes to human-readable explanations. */
    fun fromHttpCode(code: Int, rawMessage: String? = null): String = when (code) {
        400 -> "Bad request (HTTP 400). The request format or parameters sent to the API were invalid."
        401 -> "Authentication failed (HTTP 401). Please check your API key in Settings."
        403 -> {
            val isTierGated = !rawMessage.isNullOrBlank() && (
                rawMessage.contains("Upgrade", ignoreCase = true) ||
                    rawMessage.contains("tier", ignoreCase = true) ||
                    rawMessage.contains("Pro", ignoreCase = true)
                )
            if (isTierGated) {
                rawMessage
            } else {
                "Access forbidden (HTTP 403). Your API key does not have permission for this resource or model."
            }
        }
        404 -> "Resource not found (HTTP 404). Check if the selected model ID or endpoint URL is correct in Settings."
        408 -> "Request timeout (HTTP 408). The server timed out waiting for the request."
        429 -> "Rate limit or quota exceeded (HTTP 429). Please wait a moment or check your API usage limits."
        500 -> "Server error (HTTP 500). The remote AI service encountered an internal error. Please try again."
        502 -> "Bad gateway (HTTP 502). The proxy or upstream service received an invalid response."
        503 -> "Service unavailable (HTTP 503). The remote AI service is temporarily down or overloaded."
        504 -> "Gateway timeout (HTTP 504). The upstream AI service took too long to respond."
        else -> {
            val suffix = if (!rawMessage.isNullOrBlank()) " ($rawMessage)" else ""
            "The API returned an error (HTTP $code)$suffix."
        }
    }

    /** Maps Android [SpeechRecognizer] integer error codes (1–13) to clear explanations. */
    fun fromSpeechRecognizerCode(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Network connection timed out while recognizing speech."
        SpeechRecognizer.ERROR_NETWORK ->
            "Network connection error during speech recognition. Check your internet connection."
        SpeechRecognizer.ERROR_AUDIO ->
            "Microphone hardware error. Ensure your microphone is not in use by another app."
        SpeechRecognizer.ERROR_SERVER ->
            "Device speech recognition server error. Please try again."
        SpeechRecognizer.ERROR_CLIENT ->
            "Android speech recognition client error."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "No speech detected. Please speak closer to the microphone."
        SpeechRecognizer.ERROR_NO_MATCH ->
            "Could not understand speech. Please try speaking clearer or closer to the mic."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "Speech recognizer is busy. Please wait a moment and try again."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission is required for voice input."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
            "Too many speech recognition requests sent. Please wait a moment."
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            "Speech recognition service disconnected. Reconnecting..."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            "Selected language is not supported by device speech recognition."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "Language pack for selected language is not installed on your device."
        else -> "Speech recognition error (code $code)."
    }

    /** Maps Android [TextToSpeech] status codes to human-readable explanations. */
    fun fromTtsCode(code: Int): String = when (code) {
        TextToSpeech.ERROR_SYNTHESIS -> "Speech synthesis error while generating audio."
        TextToSpeech.ERROR_SERVICE -> "Android TextToSpeech engine service error."
        TextToSpeech.ERROR_OUTPUT -> "Audio output stream error during speech playback."
        TextToSpeech.ERROR_NETWORK -> "Network failure during online speech synthesis."
        TextToSpeech.ERROR_NETWORK_TIMEOUT -> "Network timeout during speech synthesis."
        TextToSpeech.ERROR_INVALID_REQUEST -> "Invalid text input or parameters passed to TTS engine."
        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "TTS voice data is still downloading on your device."
        else -> "Text-to-speech error (code $code)."
    }

    /** Translates any exception into a user-friendly error message. */
    fun format(e: Throwable): String = when {
        e is HttpException -> {
            val bodyDetail = try {
                val body = e.response()?.errorBody()?.string()
                if (!body.isNullOrBlank()) {
                    val json = Json { ignoreUnknownKeys = true }
                    val elem = json.parseToJsonElement(body)
                    (elem as? JsonObject)?.get("detail")?.let {
                        if (it is JsonPrimitive) it.content else it.toString()
                    } ?: (elem as? JsonObject)?.get("error")?.let {
                        if (it is JsonPrimitive) it.content else it.toString()
                    }
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
            fromHttpCode(e.code(), bodyDetail ?: e.message())
        }
        e is UnknownHostException ->
            "Cannot connect to server. Check your internet connection or server URL in Settings."
        e is ConnectException ->
            "Connection refused by server. Ensure the server is running and accessible."
        e is SocketTimeoutException ->
            "Connection timed out waiting for server response. Check network speed or try a smaller request."
        e is IOException && e.message?.contains("HTTP ") == true -> {
            val codeStr = e.message?.substringAfter("HTTP ")?.substringBefore(":")?.trim()
            val code = codeStr?.toIntOrNull()
            if (code != null) {
                fromHttpCode(code, e.message)
            } else {
                "Network problem: ${e.message}"
            }
        }
        e is IOException ->
            "Network problem: ${e.message ?: "could not reach the server"}. Check your connection."
        e is SecurityException ->
            "Permission denied: ${e.message ?: "required permission was not granted"}."
        !e.message.isNullOrBlank() ->
            "Error: ${e.message}"
        else -> "An unexpected error occurred (${e.javaClass.simpleName})."
    }
}
