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
        TelemetryEvents.crash("kotlin.KotlinNullPointerException", null, CrashThread.OTHER, false)
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
