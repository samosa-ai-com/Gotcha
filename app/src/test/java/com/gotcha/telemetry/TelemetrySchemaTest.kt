package com.gotcha.telemetry

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetrySchemaTest {

    /** One of every event the factories can build. */
    private val samples = listOf(
        TelemetryEvents.appOpen(cold = true, startMs = 940),
        TelemetryEvents.appOpen(cold = false, startMs = null),
        TelemetryEvents.sessionEnd(lengthMs = 95_000),
        TelemetryEvents.optIn(OptInSurface.SETTINGS),
        TelemetryEvents.crash("java.lang.IllegalStateException", "ChatRunner.start", CrashThread.MAIN, true),
        TelemetryEvents.crash("kotlin.KotlinNullPointerException", null, CrashThread.OTHER, false),
        TelemetryEvents.agentRun(
            RunSurface.CHAT, AgentModeWire.OPERATOR, ProviderKind.SAMOSA, EndpointClass.SAMOSA,
            ok = true, durationMs = 12_000, rounds = 3, toolCalls = 4, toolFailures = 1,
            delegated = false, end = RunEnd.REPLY
        ),
        TelemetryEvents.aiRequest(
            LlmFeature.AGENT, ProviderKind.SAMOSA, EndpointClass.SAMOSA, "deepseek-v4-flash",
            ok = true, latencyMs = 3_100, promptTokens = 20_000, completionTokens = 300,
            cacheHit = false, error = null
        ),
        TelemetryEvents.aiRequest(
            LlmFeature.TITLE, ProviderKind.BYO, EndpointClass.LOCAL, "my-private-finetune",
            ok = false, latencyMs = 15_000, promptTokens = null, completionTokens = null,
            cacheHit = false, error = AiError.TIMEOUT
        ),
        TelemetryEvents.toolCall("open_app", ok = true, durationMs = 400)
    )

    @Test
    fun `every factory builds an event the schema accepts`() {
        samples.forEach { assertTrue("${it.name} ${it.props}", TelemetrySchema.accepts(it)) }
    }

    @Test
    fun `every event in the schema has a factory sample and a description`() {
        assertEquals(TelemetrySchema.eventNames.toSet(), samples.map { it.name }.toSet())
        TelemetrySchema.eventNames.forEach {
            assertNotNull("no Privacy-page description for $it", TelemetryEvents.descriptionOf(it))
        }
    }

    @Test
    fun `an unknown event is refused`() {
        assertFalse(TelemetrySchema.accepts(TelemetryEvent("prompt_sent", emptyMap())))
    }

    @Test
    fun `a property the schema does not list is refused`() {
        val event = TelemetryEvents.optIn(OptInSurface.SETTINGS)
        val extra = event.copy(props = event.props + ("email" to JsonPrimitive("someone@example.com")))
        assertFalse(TelemetrySchema.accepts(extra))
    }

    @Test
    fun `a missing required property is refused`() {
        assertFalse(TelemetrySchema.accepts(TelemetryEvent(TelemetryEvents.SESSION_END, emptyMap())))
    }

    @Test
    fun `free text in a word property is refused`() {
        val event = TelemetryEvent(
            TelemetryEvents.TELEMETRY_OPT_IN,
            mapOf("surface" to JsonPrimitive("turned it on because my boss asked"))
        )
        assertFalse(TelemetrySchema.accepts(event))
    }

    @Test
    fun `a boolean property refuses the string true`() {
        val event = TelemetryEvent(TelemetryEvents.APP_OPEN, mapOf("cold" to JsonPrimitive("true")))
        assertFalse(TelemetrySchema.accepts(event))
    }

    @Test
    fun `a crash cannot carry a message or a path`() {
        val withMessage = TelemetryEvents.crash(
            "java.io.FileNotFoundException: /sdcard/Gotcha/notes.md (No such file)",
            null,
            CrashThread.MAIN,
            true
        )
        assertFalse(TelemetrySchema.accepts(withMessage))
    }

    @Test
    fun `crash frame prefers Gotcha's own code and names only class and method`() {
        val throwable = IllegalStateException("secret text the user typed").apply {
            stackTrace = arrayOf(
                StackTraceElement("java.util.ArrayList", "get", "ArrayList.java", 1),
                StackTraceElement("com.gotcha.agent.ChatRunner", "start", "ChatRunner.kt", 42)
            )
        }
        assertEquals("ChatRunner.start", TelemetryClient.crashFrame(throwable))
    }

    @Test
    fun `a model on the user's own server is never named`() {
        val byo = samples.first { it.name == TelemetryEvents.AI_REQUEST && it.props["provider"]!!.content == "byo" }
        assertFalse("model" in byo.props)
        val samosa = samples.first { it.name == TelemetryEvents.AI_REQUEST && it.props["provider"]!!.content == "samosa" }
        assertEquals("deepseek-v4-flash", samosa.props["model"]!!.content)
    }

    @Test
    fun `a model id with spaces or a path is refused`() {
        val event = TelemetryEvents.aiRequest(
            LlmFeature.AGENT, ProviderKind.SAMOSA, EndpointClass.SAMOSA, "my notes about mom",
            ok = true, latencyMs = 1, promptTokens = null, completionTokens = null, cacheHit = false, error = null
        )
        assertFalse(TelemetrySchema.accepts(event))
    }

    @Test
    fun `tool names outside Gotcha's catalogue are not sent`() {
        assertEquals("open_app", BoundedNames.tool("open_app"))
        assertEquals("mcp", BoundedNames.tool("mcp_myserver__search_my_diary"))
        assertEquals("other", BoundedNames.tool("something_the_model_made_up"))
        assertTrue(TelemetrySchema.accepts(TelemetryEvents.toolCall("mcp_myserver__x", true, 1)))
    }

    @Test
    fun `endpoint class names a kind of server, never the server`() {
        assertEquals(EndpointClass.OPENAI, EndpointClass.of("https://api.openai.com/v1/"))
        assertEquals(EndpointClass.OPENROUTER, EndpointClass.of("https://openrouter.ai/api/v1/"))
        assertEquals(EndpointClass.GROQ, EndpointClass.of("https://api.groq.com/openai/v1/"))
        assertEquals(EndpointClass.LOCAL, EndpointClass.of("http://192.168.1.5:8080/v1/"))
        assertEquals(EndpointClass.LOCAL, EndpointClass.of("http://10.0.2.2:1234/v1/"))
        assertEquals(EndpointClass.LOCAL, EndpointClass.of("http://172.20.0.3/v1/"))
        assertEquals(EndpointClass.LOCAL, EndpointClass.of("http://localhost:11434/v1/"))
        assertEquals(EndpointClass.LOCAL, EndpointClass.of("http://gpu-box.local/v1/"))
        assertEquals(EndpointClass.OTHER, EndpointClass.of("http://172.32.0.1/v1/"))
        assertEquals(EndpointClass.OTHER, EndpointClass.of("https://llm.my-name.example/v1/"))
        assertEquals(EndpointClass.OTHER, EndpointClass.of("not a url"))
    }

    @Test
    fun `model errors are classified by kind`() {
        fun http(code: Int) = retrofit2.HttpException(
            retrofit2.Response.error<Any>(code, okhttp3.ResponseBody.Companion.create(null, ""))
        )
        assertEquals(AiError.AUTH, ErrorClass.of(http(401)))
        assertEquals(AiError.RATE, ErrorClass.of(http(429)))
        assertEquals(AiError.HTTP_4XX, ErrorClass.of(http(400)))
        assertEquals(AiError.HTTP_5XX, ErrorClass.of(http(502)))
        assertEquals(AiError.TIMEOUT, ErrorClass.of(java.net.SocketTimeoutException()))
        assertEquals(AiError.OFFLINE, ErrorClass.of(java.net.UnknownHostException()))
        assertEquals(AiError.OTHER, ErrorClass.of(IllegalStateException()))
    }

    @Test
    fun `bucket edges`() {
        assertEquals("<100", Buckets.ms(99))
        assertEquals("<250", Buckets.ms(100))
        assertEquals(">=60000", Buckets.ms(60_000))
        assertEquals("0", Buckets.count(0))
        assertEquals("1", Buckets.count(1))
        assertEquals("2-4", Buckets.count(4))
        assertEquals("25+", Buckets.count(25))
        assertEquals("0-5", Buckets.seconds(5))
        assertEquals("6-30", Buckets.seconds(6))
        assertEquals(">1800", Buckets.seconds(1_801))
        assertEquals("<1k", Buckets.tokens(999))
        assertEquals(">=128k", Buckets.tokens(128_000))
        // Every value a bucket function returns is in its published set.
        (0L..70_000L step 50).forEach { assertTrue(Buckets.ms(it) in Buckets.MS) }
        (0..40).forEach { assertTrue(Buckets.count(it) in Buckets.COUNT) }
        (0L..4_000L).forEach { assertTrue(Buckets.seconds(it) in Buckets.SECONDS) }
        (0..200_000 step 500).forEach { assertTrue(Buckets.tokens(it) in Buckets.TOKENS) }
    }
}
