package com.gotcha.llm

import android.content.Context
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.security.MessageDigest

class LLMClient(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String = "gpt-4o",
    context: Context? = null,
    private val apiTimeoutSeconds: Long = 0L,
    private val cache: LLMCache = LLMCache(context)
) {
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
            .connectTimeout(apiTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(apiTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(apiTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $apiKey")
                chain.proceed(builder.build())
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
        temperature: Float? = null,
        sessionId: String? = null
    ): ChatResponse {
        val cacheKey = buildCacheKey(model, messages, tools)

        if (temperature == null || temperature == 0f) {
            cache.get(cacheKey)?.let { return it }
        }

        val request = ChatRequest(
            model = model,
            messages = messages,
            tools = tools.ifEmpty { null },
            temperature = temperature,
            promptCacheKey = sessionId
        )

        var response = apiService.chat(request, sessionId)
        response = normalizeResponse(response)

        val shouldCache = (temperature == null || temperature == 0f) &&
            response.choices.isNotEmpty() &&
            response.choices.none { it.message.toolCalls?.isNotEmpty() == true }

        if (shouldCache) {
            cache.put(cacheKey, response)
        }

        return response
    }

    fun clearCache() {
        cache.clear()
    }

    suspend fun listModels(): Result<List<String>> = runCatching {
        val response = apiService.listModels()
        response.data.map { it.id }.sorted()
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

    private fun normalizeResponse(response: ChatResponse): ChatResponse {
        val newChoices = response.choices.map { choice ->
            var msg = choice.message
            var text = msg.textContent
            var reasoning = msg.reasoningContent ?: ""

            // 1. Extract <think> tags from text
            val thinkRegex = "(?s)<think>(.*?)</think>".toRegex()
            val thinkMatches = thinkRegex.findAll(text)
            for (match in thinkMatches) {
                if (reasoning.isNotBlank()) reasoning += "\n"
                reasoning += match.groupValues[1].trim()
            }
            if (thinkMatches.any()) {
                text = thinkRegex.replace(text, "").trim()
            }

            val toolCalls = msg.toolCalls?.toMutableList() ?: mutableListOf()

            // 2. Extract XML tool calls if native toolCalls is empty
            if (toolCalls.isEmpty()) {
                val combinedText = "$reasoning\n$text"
                val toolCallRegex = "(?s)<tool_call>\\s*<function=([a-zA-Z0-9_]+)>\\s*(.*?)\\s*</function>\\s*</tool_call>".toRegex()
                val matches = toolCallRegex.findAll(combinedText)
                for (match in matches) {
                    val name = match.groupValues[1]
                    val argsRaw = match.groupValues[2].trim()
                    val args = if (argsRaw.isBlank()) "{}" else argsRaw
                    toolCalls.add(
                        ToolCall(
                            id = "call_" + java.util.UUID.randomUUID().toString().substring(0, 8),
                            function = FunctionCall(name, args)
                        )
                    )
                }
            }

            msg = msg.copy(
                content = kotlinx.serialization.json.JsonPrimitive(text),
                reasoningContent = reasoning.trim().ifEmpty { null },
                toolCalls = toolCalls.ifEmpty { null }
            )
            choice.copy(message = msg)
        }
        return response.copy(choices = newChoices)
    }
}
