package com.gotcha.audio

import android.util.Log
import com.gotcha.BuildConfig
import com.gotcha.util.GotchaLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the audio API (STT + TTS).
 * Uses OkHttp directly (not Retrofit) to handle streaming + multipart uploads easily.
 */
class AudioApi(
    private val baseUrl: String,
    private val apiKey: String,
    private val timeoutSeconds: Long = 30L,
    /** Invoked when the server returns HTTP 401 — fires the Samosa session
     *  invalidation path so the user re-signs in. */
    private val onUnauthorized: (() -> Unit)? = null
) {
    private val client: OkHttpClient
    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    init {
        val logging = HttpLoggingInterceptor().apply {
            // The request URL (the configured provider endpoint) is not user
            // content we should ship to release logcat; keep tracing debug-only.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
        }
        client = OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds * 2, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds * 2, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                if (apiKey.isNotBlank()) {
                    request.addHeader("Authorization", "Bearer $apiKey")
                }
                val response = chain.proceed(request.build())
                if (response.code == 401) {
                    onUnauthorized?.invoke()
                }
                response
            }
            .addInterceptor(logging)
            .build()
    }

    /** Fetch available models from the API and categorize them by the `task` field. */
    @Suppress("CyclomaticComplexMethod")
    fun listAudioModels(): List<AudioModel> {
        return try {
            val url = "${baseUrl.trimEnd('/')}/models"
            try { GotchaLog.d("AudioApi") { "Fetching models from: $url" } } catch (_: Throwable) {}
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    try { Log.w("AudioApi", "Models request failed: HTTP ${response.code}") } catch (_: Throwable) {}
                    return@use emptyList()
                }
                val body = response.body?.string() ?: return@use emptyList()
                try {
                    GotchaLog.d("AudioApi") { "Models response (first 200 chars): ${body.take(200)}" }
                } catch (_: Throwable) {
                }
                val jsonObj = jsonParser.parseToJsonElement(body).jsonObject
                val dataArr = jsonObj["data"]?.jsonArray ?: return@use emptyList()
                dataArr.mapNotNull { element ->
                    val modelObj = element.jsonObject
                    val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val task = modelObj["task"]?.jsonPrimitive?.contentOrNull
                    val providerType = modelObj["provider_type"]?.jsonPrimitive?.contentOrNull
                    val category = AudioModel.categorize(id, task, providerType)
                    val languages = when (val langElem = modelObj["language"]) {
                        is kotlinx.serialization.json.JsonArray -> langElem.mapNotNull { l ->
                            l.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
                        }
                        is JsonPrimitive -> listOfNotNull(langElem.contentOrNull?.takeIf { it.isNotBlank() })
                        else -> emptyList()
                    }
                    val voices = if (category == ModelCategory.TTS) {
                        modelObj["voices"]?.jsonArray?.mapNotNull innerMap@{ v ->
                            when (v) {
                                is JsonObject -> {
                                    val vId = v["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                                        ?: v["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                                        ?: return@innerMap null
                                    val vName = v["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val vLang = v["language"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val vGender = v["gender"]?.jsonPrimitive?.contentOrNull ?: ""
                                    VoiceInfo(id = vId, name = vName, language = vLang, gender = vGender)
                                }
                                is JsonPrimitive -> {
                                    val vId = v.contentOrNull?.takeIf { it.isNotBlank() } ?: return@innerMap null
                                    VoiceInfo(id = vId)
                                }
                                else -> null
                            }
                        } ?: emptyList()
                    } else {
                        emptyList()
                    }
                    AudioModel(id = id, category = category, languages = languages, voices = voices)
                }
            }
        } catch (e: Exception) {
            try { Log.e("AudioApi", "Failed to list models", e) } catch (_: Throwable) {}
            emptyList()
        }
    }

    /** Speech-to-text: upload audio and return transcription. */
    fun transcribe(
        audioFile: File,
        model: String,
        language: String? = null,
        contentType: String = "audio/m4a"
    ): Result<String> = runCatching {
        val url = "${baseUrl.trimEnd('/')}/audio/transcriptions"
        val boundary = "Boundary-${System.currentTimeMillis()}"
        val extraFields = mutableMapOf("model" to model)
        if (!language.isNullOrBlank()) {
            extraFields["language"] = language.trim()
        }
        val body = buildMultipartBody(
            boundary,
            audioFile,
            "file",
            contentType,
            extraFields
        )
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: throw IOException("Empty response")
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: $responseBody")
            val jsonObj = jsonParser.parseToJsonElement(responseBody).jsonObject
            jsonObj["text"]?.jsonPrimitive?.contentOrNull ?: ""
        }
    }

    /**
     * Text-to-speech: synthesize speech and return audio bytes.
     * @param voice must be a valid voice ID from the model's voice list.
     */
    fun synthesize(text: String, model: String, voice: String): Result<ByteArray> = runCatching {
        val url = "${baseUrl.trimEnd('/')}/audio/speech"
        val json = buildJsonObject {
            put("model", model)
            put("input", text)
            put("voice", voice)
            put("response_format", "wav")
        }
        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: "no body"
                throw IOException("HTTP ${response.code}: $errBody")
            }
            response.body?.bytes() ?: throw IOException("Empty response body")
        }
    }

    private fun buildMultipartBody(
        boundary: String,
        file: File,
        fieldName: String,
        contentType: String,
        extraFields: Map<String, String>
    ): RequestBody {
        val delimiter = "--$boundary\r\n".toByteArray()
        val closing = "--$boundary--\r\n".toByteArray()
        val bos = java.io.ByteArrayOutputStream()

        for ((key, value) in extraFields) {
            bos.write(delimiter)
            bos.write("Content-Disposition: form-data; name=\"$key\"\r\n\r\n".toByteArray())
            bos.write("$value\r\n".toByteArray())
        }

        bos.write(delimiter)
        bos.write("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"${file.name}\"\r\n".toByteArray())
        bos.write("Content-Type: $contentType\r\n\r\n".toByteArray())
        bos.write(file.readBytes())
        bos.write("\r\n".toByteArray())
        bos.write(closing)

        return bos.toByteArray().toRequestBody("multipart/form-data; boundary=$boundary".toMediaType())
    }
}
