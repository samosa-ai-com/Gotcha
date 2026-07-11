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
                // Only add auth header if a real API key is provided
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
                // Parse the task field — this is the most reliable signal
                val task = obj.optString("task", null)
                AudioModel(id, AudioModel.categorize(id, task))
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
        val body = buildMultipartBody(boundary, audioFile, "file", "audio/m4a",
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

    /** Text-to-speech: synthesize speech and return audio bytes. */
    fun synthesize(text: String, model: String, voice: String = ""): Result<ByteArray> = runCatching {
        val url = "${baseUrl.trimEnd('/')}/audio/speech"
        val json = JSONObject().apply {
            put("model", model)
            put("input", text)
            put("voice", voice.ifBlank { "default" })
            put("response_format", "wav")
        }
        val requestBody = RequestBody.create(
            "application/json".toMediaType(), json.toString()
        )
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        response.body?.bytes() ?: throw IOException("Empty response body")
    }

    private fun buildMultipartBody(
        boundary: String, file: File, fieldName: String, contentType: String,
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
