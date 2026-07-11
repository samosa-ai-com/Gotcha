package com.gotcha.llm

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
}
