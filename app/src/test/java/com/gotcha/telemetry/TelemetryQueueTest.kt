package com.gotcha.telemetry

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TelemetryQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun event(n: Int) = QueuedEvent("id-$n", "app_open", n.toLong(), "s", mapOf("cold" to JsonPrimitive(true)))

    private fun queue(max: Int = 1_000) = TelemetryQueue(File(tmp.root, "telemetry/queue.ndjson"), max)

    @Test
    fun `events come back oldest first`() {
        val q = queue()
        (1..3).forEach { q.append(event(it)) }
        assertEquals(listOf("id-1", "id-2"), q.peek(2).map { it.id })
        assertEquals(3, q.size())
    }

    @Test
    fun `past the cap the oldest fifth is dropped`() {
        val q = queue(max = 10)
        (1..11).forEach { q.append(event(it)) }
        val ids = q.peek(100).map { it.id }
        assertEquals(8, ids.size)
        assertEquals("id-4", ids.first())
        assertEquals("id-11", ids.last())
    }

    @Test
    fun `remove drops only what was sent`() {
        val q = queue()
        (1..4).forEach { q.append(event(it)) }
        q.remove(setOf("id-1", "id-2"))
        assertEquals(listOf("id-3", "id-4"), q.peek(10).map { it.id })
    }

    @Test
    fun `a line cut short by a crash is skipped, not fatal`() {
        val file = File(tmp.root, "telemetry/queue.ndjson")
        val q = TelemetryQueue(file)
        q.append(event(1))
        file.appendText("{\"id\":\"id-2\",\"name\":\"app_o")
        assertEquals(listOf("id-1"), q.peek(10).map { it.id })
    }

    @Test
    fun `clear deletes the file`() {
        val file = File(tmp.root, "telemetry/queue.ndjson")
        val q = TelemetryQueue(file)
        q.append(event(1))
        q.clear()
        assertEquals(false, file.exists())
        assertEquals(0, q.size())
    }

    @Test
    fun `size counts lines without parsing queued JSON`() {
        val file = File(tmp.root, "queue.ndjson")
        file.writeText("not json\n\n{broken}\n")
        assertEquals(2, TelemetryQueue(file).size())
    }
}
