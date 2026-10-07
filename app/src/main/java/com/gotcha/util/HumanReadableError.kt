package com.gotcha.util

import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.gotcha.R
import com.gotcha.i18n.StringLookup
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

    /**
     * Maps HTTP status codes to human-readable explanations. A rejection that
     * names images (e.g. a provider allowing fewer images per request than the
     * user attached) gets its own explanation, since the fix is on the user's side.
     */
    fun fromHttpCode(code: Int, rawMessage: String? = null, strings: StringLookup): String = when {
        code in IMAGE_REJECTION_CODES && rawMessage?.contains("image", ignoreCase = true) == true ->
            strings(R.string.error_the_model_rejected_the_attached, code, rawMessage)
        else -> fromHttpCodeOnly(code, rawMessage, strings)
    }

    private val IMAGE_REJECTION_CODES = setOf(400, 413, 422)

    private fun fromHttpCodeOnly(code: Int, rawMessage: String?, strings: StringLookup): String = when (code) {
        400 -> strings(R.string.error_bad_request_http_400_the)
        401 -> strings(R.string.error_authentication_failed_http_401_please)
        403 -> {
            if (!rawMessage.isNullOrBlank()) {
                rawMessage
            } else {
                strings(R.string.error_access_restricted_http_403_this)
            }
        }
        404 -> strings(R.string.error_resource_not_found_http_404)
        408 -> strings(R.string.error_request_timeout_http_408_the)
        413 -> strings(R.string.error_request_too_large_http_413)
        429 -> strings(R.string.error_rate_limit_or_quota_exceeded)
        500 -> strings(R.string.error_server_error_http_500_the)
        502 -> strings(R.string.error_bad_gateway_http_502_the)
        503 -> strings(R.string.error_service_unavailable_http_503_the)
        504 -> strings(R.string.error_gateway_timeout_http_504_the)
        else -> {
            if (rawMessage.isNullOrBlank()) {
                strings(R.string.error_http_generic, code)
            } else {
                strings(R.string.error_http_generic_detail, code, rawMessage)
            }
        }
    }

    /** Maps Android [SpeechRecognizer] integer error codes (1–13) to clear explanations. */
    fun fromSpeechRecognizerCode(code: Int, strings: StringLookup): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            strings(R.string.error_network_connection_timed_out_while)
        SpeechRecognizer.ERROR_NETWORK ->
            strings(R.string.error_network_connection_error_during_speech)
        SpeechRecognizer.ERROR_AUDIO ->
            strings(R.string.error_microphone_hardware_error_ensure_your)
        SpeechRecognizer.ERROR_SERVER ->
            strings(R.string.error_device_speech_recognition_server_error)
        SpeechRecognizer.ERROR_CLIENT ->
            strings(R.string.error_android_speech_recognition_client_error)
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            strings(R.string.error_no_speech_detected_please_speak)
        SpeechRecognizer.ERROR_NO_MATCH ->
            strings(R.string.error_could_not_understand_speech_please)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            strings(R.string.error_speech_recognizer_is_busy_please)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            strings(R.string.error_microphone_permission_is_required_for)
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
            strings(R.string.error_too_many_speech_recognition_requests)
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            strings(R.string.error_speech_recognition_service_disconnected_reconnecting)
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            strings(R.string.error_selected_language_is_not_supported)
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            strings(R.string.error_language_pack_for_selected_language)
        else -> strings(R.string.error_speech_recognition_error_code, code)
    }

    /** Maps Android [TextToSpeech] status codes to human-readable explanations. */
    fun fromTtsCode(code: Int, strings: StringLookup): String = when (code) {
        TextToSpeech.ERROR_SYNTHESIS -> strings(R.string.error_speech_synthesis_error_while_generating)
        TextToSpeech.ERROR_SERVICE -> strings(R.string.error_android_texttospeech_engine_service_error)
        TextToSpeech.ERROR_OUTPUT -> strings(R.string.error_audio_output_stream_error_during)
        TextToSpeech.ERROR_NETWORK -> strings(R.string.error_network_failure_during_online_speech)
        TextToSpeech.ERROR_NETWORK_TIMEOUT -> strings(R.string.error_network_timeout_during_speech_synthesis)
        TextToSpeech.ERROR_INVALID_REQUEST -> strings(R.string.error_invalid_text_input_or_parameters)
        TextToSpeech.ERROR_NOT_INSTALLED_YET -> strings(R.string.error_tts_voice_data_is_still)
        else -> strings(R.string.error_text_to_speech_error_code, code)
    }

    /** Safely parses error JSON from an HttpException response body once and extracts detail, message, or error. */
    fun extractHttpErrorDetail(e: HttpException): String? {
        return try {
            val body = e.response()?.errorBody()?.string()
            if (!body.isNullOrBlank()) {
                val json = Json { ignoreUnknownKeys = true }
                val elem = json.parseToJsonElement(body)
                val obj = elem as? JsonObject
                obj?.get("detail")?.let {
                    if (it is JsonPrimitive) it.content else it.toString()
                } ?: obj?.get("message")?.let {
                    if (it is JsonPrimitive) it.content else it.toString()
                } ?: obj?.get("error")?.let {
                    if (it is JsonPrimitive) it.content else it.toString()
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Translates any exception into a user-friendly error message. */
    fun format(e: Throwable, strings: StringLookup): String = when {
        e is HttpException -> {
            val bodyDetail = extractHttpErrorDetail(e)
            fromHttpCode(e.code(), bodyDetail ?: e.message(), strings)
        }
        e is UnknownHostException ->
            strings(R.string.error_cannot_connect_to_server_check)
        e is ConnectException ->
            strings(R.string.error_connection_refused_by_server_ensure)
        e is SocketTimeoutException ->
            strings(R.string.error_connection_timed_out_waiting_for)
        e is IOException && e.message?.contains("HTTP ") == true -> {
            val codeStr = e.message?.substringAfter("HTTP ")?.substringBefore(":")?.trim()
            val code = codeStr?.toIntOrNull()
            if (code != null) {
                fromHttpCode(code, e.message, strings)
            } else {
                strings(R.string.error_network_problem, e.message.orEmpty())
            }
        }
        e is IOException ->
            strings(R.string.error_network_problem_check, e.message ?: strings(R.string.error_could_not_reach_server))
        e is SecurityException ->
            strings(R.string.error_permission_denied, e.message ?: strings(R.string.error_permission_not_granted))
        !e.message.isNullOrBlank() ->
            strings(R.string.error_generic, e.message.orEmpty())
        else -> strings(R.string.error_an_unexpected_error_occurred, e.javaClass.simpleName)
    }
}
