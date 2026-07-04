package com.gotcha.llm

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.security.MessageDigest

class LLMClient(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String = "gpt-4o",
    context: Context? = null,
    private val cache: LLMCache = LLMCache(context)
) {
    companion object {
        private const val DEFAULT_MODEL = "gpt-4o"
    }

    private val apiService: ApiService

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    init {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
            redactHeader("Authorization")
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $apiKey")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(logging)
            .build()

        val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        val retrofit = Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        apiService = retrofit.create(ApiService::class.java)
    }

    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        temperature: Float? = null
    ): ChatResponse {
        val cacheKey = buildCacheKey(model, messages, tools)

        if (temperature == null || temperature == 0f) {
            cache.get(cacheKey)?.let { return it }
        }

        val request = ChatRequest(
            model = model,
            messages = messages,
            tools = tools.ifEmpty { null },
            temperature = temperature
        )

        val response = apiService.chat(request)

        val shouldCache = (temperature == null || temperature == 0f)
                && response.choices.isNotEmpty()
                && response.choices.none { it.message.toolCalls?.isNotEmpty() == true }

        if (shouldCache) {
            cache.put(cacheKey, response)
        }

        return response
    }

    fun clearCache() {
        cache.clear()
    }

    private fun buildCacheKey(
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>
    ): String {
        val serialized = buildString {
            append(model)
            append("|")
            append(json.encodeToString(ListSerializer(ChatMessage.serializer()), messages))
            append("|")
            append(json.encodeToString(ListSerializer(ToolDefinition.serializer()), tools))
        }
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(serialized.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
