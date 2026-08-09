package com.gotcha.testutil

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/** Canned reply asserted by [com.gotcha.ChatRoundTripTest]; mirrors testing/scripts/mock_llm_server.py. */
const val MOCK_REPLY_OK = "MOCK_REPLY_OK"

/**
 * In-process MockWebServer standing in for an OpenAI-compatible backend during
 * androidTest runs. Serves the same two routes the Kotlin unit tests
 * (app/src/test/java/com/gotcha/llm/LLMClientTest.kt) and the standalone
 * testing/scripts/mock_llm_server.py (used by Maestro) exercise.
 */
class MockLlm {

    val server = MockWebServer()

    /** Base URL to seed into settings — trailing slash, matches LLMClient's expectations. */
    val baseUrl: String
        get() = server.url("/v1/").toString()

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.endsWith("/chat/completions") == true -> MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """{"choices":[{"message":{"role":"assistant","content":"$MOCK_REPLY_OK"}}]}"""
                        )
                    request.path?.endsWith("/models") == true -> MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"data":[{"id":"test-model","object":"model"}]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    fun shutdown() {
        server.shutdown()
    }
}
