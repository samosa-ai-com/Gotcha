package com.gotcha.llm

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A chat message sent to or received from the LLM API.
 * [content] is either a string (JsonPrimitive) for text-only messages, or a
 * JsonArray of content parts for vision/multi-modal messages following the
 * OpenAI format: [{"type":"text","text":"..."},{"type":"image_url","image_url":{"url":"data:..."}}].
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null
) {
    /** The plain-text portion of this message, or empty string if there is none. */
    val textContent: String get() = when (content) {
        is JsonPrimitive -> content.content
        is JsonArray -> content.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
        else -> ""
    }

    /** True when this message has a non-blank text portion. */
    val hasText: Boolean get() = textContent.isNotBlank()

    /** True when this message carries an image (vision) part. */
    val hasImage: Boolean get() = imageUrl() != null

    /**
     * The data URI of this message's first image part (e.g.
     * "data:image/jpeg;base64,…"), or null when there is no image part.
     */
    fun imageUrl(): String? {
        val parts = content as? JsonArray ?: return null
        return parts.firstNotNullOfOrNull { part ->
            val obj = part as? JsonObject ?: return@firstNotNullOfOrNull null
            if ((obj["type"] as? JsonPrimitive)?.content != "image_url") {
                return@firstNotNullOfOrNull null
            }
            val img = obj["image_url"] as? JsonObject ?: return@firstNotNullOfOrNull null
            (img["url"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        }
    }
}

/** Build a vision-content ChatMessage with text + an image. */
fun visionUserMessage(text: String, imageBase64: String, imageFormat: String = "png"): ChatMessage {
    val dataUri = "data:image/$imageFormat;base64,$imageBase64"
    return ChatMessage(
        role = "user",
        content = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text.ifBlank { "What is in this image?" })
                }
            )
            add(
                buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") { put("url", dataUri) }
                }
            )
        }
    )
}

@Serializable
data class ToolCall(
    val id: String,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault
    val type: String = "function",
    val function: FunctionCall
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String
)

@Serializable
data class ToolDefinition(
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault
    val type: String = "function",
    val function: FunctionDefinition
)

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>? = null,
    val temperature: Float? = null,
    @SerialName("prompt_cache_key")
    val promptCacheKey: String? = null
)

@Serializable
data class ChatResponse(
    val choices: List<Choice>,
    val usage: Usage? = null
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0
)

@Serializable
data class Choice(
    val message: ChatMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

/** Response from GET /v1/models (OpenAI-compatible). */
@Serializable
data class ModelListResponse(
    val data: List<ModelInfo>
)

@Serializable
data class ModelInfo(
    val id: String,
    @SerialName("object")
    val objectType: String = "model",
    @SerialName("owned_by")
    val ownedBy: String = "",
    /** OpenAI-style task hint (e.g. "text-generation", "text-to-speech"). */
    val task: String? = null,
    /** Samosa-style provider hint (e.g. "llm", "tts", "stt"). */
    @SerialName("provider_type")
    val providerType: String? = null
)
