package com.gotcha.audio

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
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
    private val timeoutSeconds: Long = 30L
) {
    private val client: OkHttpClient

    init {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
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
                chain.proceed(request.build())
            }
            .addInterceptor(logging)
            .build()
    }

    /** Fetch available models from the API and categorize them by the `task` field. */
    fun listAudioModels(): List<AudioModel> {
        return try {
            val url = "${baseUrl.trimEnd('/')}/models"
            Log.d("AudioApi", "Fetching models from: $url")
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w("AudioApi", "Models request failed: HTTP ${response.code}")
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            Log.d("AudioApi", "Models response (first 200 chars): ${body.take(200)}")
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return emptyList()
            (0 until data.length()).mapNotNull { i ->
                val obj = data.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id", "") ?: return@mapNotNull null
                val task = obj.optString("task", null)
                val category = AudioModel.categorize(id, task)
                // Parse voice list for TTS models
                val voices = if (category == ModelCategory.TTS) {
                    val voicesArr = obj.optJSONArray("voices")
                    if (voicesArr != null) {
                        (0 until voicesArr.length()).mapNotNull { j ->
                            voicesArr.optJSONObject(j)?.optString("id", null)
                        }
                    } else {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
                AudioModel(id, category, voices)
            }
        } catch (e: Exception) {
            Log.e("AudioApi", "Failed to list models", e)
            emptyList()
        }
    }

    /** Speech-to-text: upload audio and return transcription. */
    fun transcribe(audioFile: File, model: String): Result<String> = runCatching {
        val url = "${baseUrl.trimEnd('/')}/audio/transcriptions"
        val boundary = "Boundary-${System.currentTimeMillis()}"
        val body = buildMultipartBody(
            boundary,
            audioFile,
            "file",
            "audio/m4a",
            mapOf("model" to model)
        )
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .post(body)
            .build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty response")
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}: $responseBody")
        val json = JSONObject(responseBody)
        json.optString("text", "") ?: ""
    }

    /**
     * Text-to-speech: synthesize speech and return audio bytes.
     * @param voice must be a valid voice ID from the model's voice list.
     */
    fun synthesize(text: String, model: String, voice: String): Result<ByteArray> = runCatching {
        val url = "${baseUrl.trimEnd('/')}/audio/speech"
        val json = JSONObject().apply {
            put("model", model)
            put("input", text)
            put("voice", voice)
            put("response_format", "wav")
        }
        val requestBody = RequestBody.create(
            "application/json".toMediaType(),
            json.toString()
        )
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errBody = response.body?.string() ?: "no body"
            throw IOException("HTTP ${response.code}: $errBody")
        }
        response.body?.bytes() ?: throw IOException("Empty response body")
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

        return RequestBody.create("multipart/form-data; boundary=$boundary".toMediaType(), bos.toByteArray())
    }
}
