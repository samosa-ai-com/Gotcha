package com.gotcha.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-contract tests for the OpenAI-compatible API models. These pin the JSON
 * shapes the LLM endpoint expects; a regression here breaks tool calling silently.
 */
class ModelsTest {

    // Mirror of LLMClient's Json configuration.
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Test
    fun `textContent returns string content for plain messages`() {
        val msg = ChatMessage(role = "user", content = JsonPrimitive("hello"))
        assertEquals("hello", msg.textContent)
        assertTrue(msg.hasText)
    }

    @Test
    fun `textContent extracts text part from vision content array`() {
        val msg = visionUserMessage("describe this", "QUJD")
        assertEquals("describe this", msg.textContent)
        assertTrue(msg.hasText)
    }

    @Test
    fun `textContent is empty for null content`() {
        val msg = ChatMessage(role = "assistant", content = null)
        assertEquals("", msg.textContent)
        assertFalse(msg.hasText)
    }

    @Test
    fun `hasText is false for blank content`() {
        assertFalse(ChatMessage(role = "user", content = JsonPrimitive("   ")).hasText)
    }

    @Test
    fun `visionUserMessage builds the OpenAI two-part content structure`() {
        val msg = visionUserMessage("what is this", "QUJD", imageFormat = "jpeg")

        assertEquals("user", msg.role)
        val parts = (msg.content as JsonArray)
        assertEquals(2, parts.size)

        val textPart = parts[0].jsonObject
        assertEquals("text", textPart["type"]!!.jsonPrimitive.content)
        assertEquals("what is this", textPart["text"]!!.jsonPrimitive.content)

        val imagePart = parts[1].jsonObject
        assertEquals("image_url", imagePart["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,QUJD",
            imagePart["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `visionUserMessage falls back to a default prompt for blank text`() {
        val msg = visionUserMessage("  ", "QUJD")
        assertEquals("What is in this image?", msg.textContent)
    }

    @Test
    fun `hasImage is true for vision messages and imageUrl returns the data uri`() {
        val msg = visionUserMessage("look", "QUJD", imageFormat = "jpeg")
        assertTrue(msg.hasImage)
        assertEquals("data:image/jpeg;base64,QUJD", msg.imageUrl())
    }

    @Test
    fun `hasImage is false and imageUrl null for text-only messages`() {
        assertFalse(ChatMessage(role = "user", content = JsonPrimitive("hi")).hasImage)
        assertNull(ChatMessage(role = "user", content = JsonPrimitive("hi")).imageUrl())
        assertNull(ChatMessage(role = "assistant", content = null).imageUrl())
    }

    @Test
    fun `imageUrl returns null when the content array has no image part`() {
        val msg = ChatMessage(
            role = "user",
            content = buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "no image here")
                    }
                )
            }
        )
        assertFalse(msg.hasImage)
        assertNull(msg.imageUrl())
    }

    @Test
    fun `tool call type is always encoded even with encodeDefaults off`() {
        val call = ToolCall(id = "call_1", function = FunctionCall(name = "dial_number", arguments = "{}"))
        val encoded = json.encodeToString(ToolCall.serializer(), call)
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertEquals("function", obj["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool definition type is always encoded even with encodeDefaults off`() {
        val def = ToolDefinition(
            function = FunctionDefinition(
                name = "x",
                description = "y",
                parameters = json.parseToJsonElement("""{"type":"object"}""").jsonObject
            )
        )
        val encoded = json.encodeToString(ToolDefinition.serializer(), def)
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertEquals("function", obj["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `chat request omits null tools and temperature`() {
        val request = ChatRequest(
            model = "gpt-4o",
            messages = listOf(ChatMessage(role = "user", content = JsonPrimitive("hi")))
        )
        val obj = json.parseToJsonElement(json.encodeToString(ChatRequest.serializer(), request)).jsonObject
        assertNull(obj["tools"])
        assertNull(obj["temperature"])
    }

    @Test
    fun `chat response decoding ignores unknown keys`() {
        val response = json.decodeFromString(
            ChatResponse.serializer(),
            """
            {
              "id": "chatcmpl-123",
              "object": "chat.completion",
              "created": 1700000000,
              "choices": [
                {
                  "index": 0,
                  "message": {"role": "assistant", "content": "hi", "refusal": null},
                  "finish_reason": "stop",
                  "logprobs": null
                }
              ],
              "usage": {"prompt_tokens": 5, "completion_tokens": 2, "total_tokens": 7}
            }
            """.trimIndent()
        )
        assertEquals("hi", response.choices.single().message.textContent)
        assertEquals("stop", response.choices.single().finishReason)
        assertEquals(7, response.usage!!.totalTokens)
    }

    @Test
    fun `chat message with tool calls round-trips through serialization`() {
        val original = ChatMessage(
            role = "assistant",
            toolCalls = listOf(
                ToolCall(id = "call_1", function = FunctionCall("read_file", """{"path":"files/a.txt"}"""))
            )
        )
        val decoded = json.decodeFromString(
            ChatMessage.serializer(),
            json.encodeToString(ChatMessage.serializer(), original)
        )
        assertEquals(original, decoded)
    }

    @Test
    fun `tool result message uses tool_call_id serial name`() {
        val msg = ChatMessage(role = "tool", content = JsonPrimitive("ok"), toolCallId = "call_9")
        val obj = json.parseToJsonElement(json.encodeToString(ChatMessage.serializer(), msg)).jsonObject
        assertEquals("call_9", obj["tool_call_id"]!!.jsonPrimitive.content)
        assertNull(obj["toolCallId"])
    }

    @Test
    fun `vision message content survives a serialization round-trip`() {
        val original = visionUserMessage("look", "QUJD")
        val decoded = json.decodeFromString(
            ChatMessage.serializer(),
            json.encodeToString(ChatMessage.serializer(), original)
        )
        assertEquals(original, decoded)
        assertEquals(2, decoded.content!!.jsonArray.size)
    }

    @Test
    fun `ModelInfo parses provider_type and task hints`() {
        val decoded = json.decodeFromString(
            ModelListResponse.serializer(),
            """
            {
              "data": [
                {"id": "gpt-4o", "provider_type": "llm"},
                {"id": "kokoro-82m", "task": "text-to-speech"},
                {"id": "plain-model"}
              ]
            }
            """.trimIndent()
        )
        assertEquals(3, decoded.data.size)
        assertEquals("gpt-4o", decoded.data[0].id)
        assertEquals("llm", decoded.data[0].providerType)
        assertEquals("text-to-speech", decoded.data[1].task)
        assertNull(decoded.data[2].providerType)
        assertNull(decoded.data[2].task)
    }

    // ---- malformed tool-call arguments (issue #13) ----

    @Test
    fun `isParsableJsonObject accepts objects and rejects everything else`() {
        assertTrue(isParsableJsonObject("{}"))
        assertTrue(isParsableJsonObject("""{"summary":"hi"}"""))
        assertFalse(isParsableJsonObject(""))
        assertFalse(isParsableJsonObject("not json"))
        assertFalse(isParsableJsonObject("[1,2]"))
        assertFalse(isParsableJsonObject("\"string\""))
        assertFalse(isParsableJsonObject("""{"summary": "unterminated"""))
    }

    @Test
    fun `malformed tool call arguments are replaced with an empty object`() {
        val msg = ChatMessage(
            role = "assistant",
            toolCalls = listOf(
                ToolCall(
                    id = "call_bad",
                    function = FunctionCall("finish_task", """{"summary": "unterminated""")
                ),
                ToolCall(
                    id = "call_ok",
                    function = FunctionCall("websearch", """{"query":"hi"}""")
                )
            )
        )

        val sanitized = msg.withValidToolCallArguments()

        assertEquals("{}", sanitized.toolCalls!![0].function.arguments)
        assertEquals("""{"query":"hi"}""", sanitized.toolCalls!![1].function.arguments)
        assertEquals("call_bad", sanitized.toolCalls!![0].id)
        // The original is untouched.
        assertEquals("""{"summary": "unterminated""", msg.toolCalls!![0].function.arguments)
    }

    @Test
    fun `messages without tool calls pass through unchanged`() {
        val plain = ChatMessage(role = "user", content = JsonPrimitive("hello"))
        val noCalls = ChatMessage(role = "assistant", content = JsonPrimitive("ok"))
        assertEquals(plain, plain.withValidToolCallArguments())
        assertEquals(noCalls, noCalls.withValidToolCallArguments())
    }
}
