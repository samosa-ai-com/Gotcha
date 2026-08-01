package com.gotcha.llm

import com.gotcha.i18n.Language
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LLMClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: LLMClient

    private val dummyTool = ToolDefinition(
        function = FunctionDefinition(
            name = "dial_number",
            description = "Dial a phone number",
            parameters = buildJsonObject {
                put("type", "object")
            }
        )
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = LLMClient(
            apiKey = "test-key",
            baseUrl = server.url("/").toString()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `chat parses tool_calls response`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "tool_calls": [
                          {
                            "id": "call_1",
                            "type": "function",
                            "function": {
                              "name": "dial_number",
                              "arguments": "{\"number\":\"555-1234\"}"
                            }
                          }
                        ]
                      },
                      "finish_reason": "tool_calls"
                    }
                  ]
                }
                """.trimIndent()
            ).setHeader("Content-Type", "application/json")
        )

        val response = client.chat(
            messages = listOf(ChatMessage(role = "user", content = JsonPrimitive("Call mom"))),
            tools = listOf(dummyTool)
        )

        val message = response.choices.single().message
        val toolCall = message.toolCalls?.single()
        assertEquals("dial_number", toolCall?.function?.name)
        assertEquals("{\"number\":\"555-1234\"}", toolCall?.function?.arguments)
    }

    @Test
    fun `chat parses direct content response`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"Hi there"}}]}"""
            ).setHeader("Content-Type", "application/json")
        )

        val response = client.chat(
            messages = listOf(ChatMessage(role = "user", content = JsonPrimitive("Hello")))
        )

        assertEquals("Hi there", response.choices.single().message.textContent)
    }

    @Test
    fun `identical deterministic request is served from cache`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"cached answer"}}]}"""
            ).setHeader("Content-Type", "application/json")
        )

        val messages = listOf(ChatMessage(role = "user", content = JsonPrimitive("What is 2+2?")))

        val first = client.chat(messages = messages)
        val second = client.chat(messages = messages)

        assertEquals("cached answer", first.choices.single().message.textContent)
        assertEquals(first.choices.single().message.textContent, second.choices.single().message.textContent)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `response containing tool_calls is never cached`() = runTest {
        val toolCallBody = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "tool_calls": [
                      {
                        "id": "call_1",
                        "type": "function",
                        "function": { "name": "dial_number", "arguments": "{}" }
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(toolCallBody).setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody(toolCallBody).setHeader("Content-Type", "application/json"))

        val messages = listOf(ChatMessage(role = "user", content = JsonPrimitive("Call mom")))

        client.chat(messages = messages, tools = listOf(dummyTool))
        client.chat(messages = messages, tools = listOf(dummyTool))

        assertTrue(server.requestCount == 2)
    }

    @Test
    fun `cleanText request body contains the language name and no-translate clause`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"नमस्ते, आप कैसे हैं?"}}]}"""
            ).setHeader("Content-Type", "application/json")
        )

        client.cleanText("namaste aap kaise hain", language = Language.HINDI)

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains(Language.HINDI.label))
        assertTrue(body.contains("Never translate"))
        assertTrue(body.contains("<dictation>"))
    }

    @Test
    fun `listModels filters out audio models by provider_type`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": [
                    {"id": "gpt-4o", "provider_type": "llm"},
                    {"id": "kokoro-82m", "provider_type": "tts"},
                    {"id": "whisper-1", "provider_type": "stt"},
                    {"id": "claude-opus-4", "provider_type": "llm"}
                  ]
                }
                """.trimIndent()
            ).setHeader("Content-Type", "application/json")
        )

        val result = client.listModels().getOrThrow()

        assertEquals(listOf("claude-opus-4", "gpt-4o"), result)
    }

    @Test
    fun `listModels filters out audio models by task field`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": [
                    {"id": "gpt-4o-mini"},
                    {"id": "tts-1", "task": "text-to-speech"},
                    {"id": "whisper-large-v3", "task": "automatic-speech-recognition"}
                  ]
                }
                """.trimIndent()
            ).setHeader("Content-Type", "application/json")
        )

        val result = client.listModels().getOrThrow()

        assertEquals(listOf("gpt-4o-mini"), result)
    }

    @Test
    fun `listModels falls back to name heuristics when no hint field is present`() = runTest {
        // OpenAI's /v1/models omits both `provider_type` and `task`; the name
        // heuristic is the last line of defence.
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": [
                    {"id": "gpt-4o"},
                    {"id": "tts-1"},
                    {"id": "whisper-1"},
                    {"id": "llama-3-8b-instruct"}
                  ]
                }
                """.trimIndent()
            ).setHeader("Content-Type", "application/json")
        )

        val result = client.listModels().getOrThrow()

        assertEquals(listOf("gpt-4o", "llama-3-8b-instruct"), result)
    }

    @Test
    fun `listModels keeps explicitly tagged LLM models alongside UNKNOWN ones`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": [
                    {"id": "samosa-llm-pro", "provider_type": "llm"},
                    {"id": "gpt-4o"},
                    {"id": "kokoro-82m", "provider_type": "tts"}
                  ]
                }
                """.trimIndent()
            ).setHeader("Content-Type", "application/json")
        )

        val result = client.listModels().getOrThrow()

        assertEquals(listOf("gpt-4o", "samosa-llm-pro"), result)
    }

    @Test
    fun `listModels keeps UNKNOWN-categorized models with no hint and no name match`() = runTest {
        // Custom / private model with neither provider_type nor task field, and
        // an id that triggers no audio name heuristic — must survive filtering.
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": [
                    {"id": "my-private-llm"},
                    {"id": "tts-1"},
                    {"id": "whisper-large-v3"}
                  ]
                }
                """.trimIndent()
            ).setHeader("Content-Type", "application/json")
        )

        val result = client.listModels().getOrThrow()

        assertEquals(listOf("my-private-llm"), result)
    }
}
