package com.gotcha.llm

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    /** How many image (vision) parts this message carries. */
    val imageCount: Int get() = (content as? JsonArray)?.count { part ->
        ((part as? JsonObject)?.get("type") as? JsonPrimitive)?.content == "image_url"
    } ?: 0

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

/**
 * The text part of an image-only message. Multimodal APIs require a text part,
 * but inventing a prompt (e.g. "What is in this image?") would change what the
 * user asked, so a single space stands in for "no text".
 */
const val IMAGE_ONLY_TEXT = " "

/** Build a vision-content ChatMessage with text + an image. */
fun visionUserMessage(text: String, imageBase64: String, imageFormat: String = "png"): ChatMessage {
    val dataUri = "data:image/$imageFormat;base64,$imageBase64"
    return ChatMessage(
        role = "user",
        content = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text.ifBlank { IMAGE_ONLY_TEXT })
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

/**
 * Build a document-content ChatMessage: a single text part carrying the user's
 * question, an `[Attached file: …]` header, and the extracted document text.
 *
 * The header + body deliberately live in the FIRST (and only) text part: token
 * accounting, history trimming and compaction all read [ChatMessage.textContent],
 * which returns the first text part — a second part would silently disappear
 * from token counts and compaction summaries.
 */
fun documentUserMessage(
    userText: String,
    fileName: String,
    mimeType: String,
    extractedText: String,
    pageCount: Int? = null
): ChatMessage {
    val text = listOf(
        userText.ifBlank { "Answer questions about the attached file." },
        documentSection(DocumentPart(fileName, mimeType, extractedText, pageCount))
    ).joinToString("\n\n")
    return ChatMessage(
        role = "user",
        content = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            )
        }
    )
}

/** A document to inline into a user message: its name, type and extracted text. */
data class DocumentPart(
    val fileName: String,
    val mimeType: String,
    val extractedText: String,
    val pageCount: Int? = null
)

/** The `[Attached file: …]` header and extracted body for one document. */
private fun documentSection(doc: DocumentPart): String {
    val header = buildString {
        append("[Attached file: ${doc.fileName}")
        append(if (doc.mimeType.isNotBlank()) " (${doc.mimeType}" else " (document")
        doc.pageCount?.let { append(", $it pages") }
        append(")]")
    }
    val body = doc.extractedText.ifBlank {
        "(The document could not be read — ask the user for the details.)"
    }
    return "$header\n\n$body"
}

/**
 * Build a user message carrying any number of documents and images, in the
 * order the user picked them within each kind.
 *
 * The prompt and every document's header + body share the FIRST text part, for
 * the same reason as [documentUserMessage]: [ChatMessage.textContent] only reads
 * that part. Each image then follows as its own `image_url` part, so the model
 * receives every image separately.
 */
fun attachmentsUserMessage(
    userText: String,
    documents: List<DocumentPart>,
    imagesBase64: List<String>,
    imageFormat: String = "jpeg"
): ChatMessage {
    val prompt = userText.ifBlank {
        if (documents.isNotEmpty()) "Answer questions about the attached files." else IMAGE_ONLY_TEXT
    }
    val text = (listOf(prompt) + documents.map(::documentSection)).joinToString("\n\n")
    return ChatMessage(
        role = "user",
        content = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            )
            for (base64 in imagesBase64) {
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") { put("url", "data:image/$imageFormat;base64,$base64") }
                    }
                )
            }
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

/**
 * True when [arguments] parses as a JSON object — the shape a tool-call
 * `arguments` string must take. Used to neutralize model-emitted tool calls
 * whose arguments are invalid JSON: some OpenAI-compatible servers reject the
 * whole request with HTTP 400 when history contains such a call, bricking the
 * chat for every later message (issue #13).
 */
internal fun isParsableJsonObject(arguments: String): Boolean = try {
    Json.parseToJsonElement(arguments) is JsonObject
} catch (_: Exception) {
    false
}

/**
 * Copy of this message with any tool call whose `arguments` are not valid JSON
 * replaced by `"{}"`; valid calls are left untouched. Callers persist or send
 * this copy so a malformed call can never poison the request again.
 */
internal fun ChatMessage.withValidToolCallArguments(): ChatMessage {
    val calls = toolCalls ?: return this
    val sanitized = calls.map { call ->
        if (isParsableJsonObject(call.function.arguments)) {
            call
        } else {
            call.copy(function = call.function.copy(arguments = "{}"))
        }
    }
    return copy(toolCalls = sanitized)
}
