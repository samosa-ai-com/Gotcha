package com.gotcha.telemetry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** One queued event with the ids and time it is sent with. */
data class QueuedEvent(
    val id: String,
    val name: String,
    val ts: Long,
    val sessionId: String,
    val props: Map<String, JsonPrimitive>
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("ts", ts)
        put("session_id", sessionId)
        put("props", JsonObject(props))
    }

    companion object {
        /** Null for a line that is not a whole event, such as one cut short by a crash. */
        fun parse(line: String): QueuedEvent? = runCatching {
            val obj = Json.parseToJsonElement(line) as JsonObject
            QueuedEvent(
                id = (obj["id"] as JsonPrimitive).content,
                name = (obj["name"] as JsonPrimitive).content,
                ts = (obj["ts"] as JsonPrimitive).content.toLong(),
                sessionId = (obj["session_id"] as JsonPrimitive).content,
                props = (obj["props"] as JsonObject).mapValues { it.value as JsonPrimitive }
            )
        }.getOrNull()
    }
}

/**
 * Events waiting to be sent, one JSON object per line in a file in the app's
 * private storage. Appending is the only thing a call site's [Telemetry.track]
 * does, so it never waits on the network.
 *
 * Capped at [maxEvents]: past it the oldest fifth is dropped in one rewrite, so
 * a phone that stays offline for weeks keeps recent events in a bounded file.
 */
class TelemetryQueue(
    private val file: File,
    private val maxEvents: Int = DEFAULT_MAX_EVENTS
) {
    @Synchronized
    fun append(event: QueuedEvent) {
        file.parentFile?.mkdirs()
        file.appendText(event.toJson().toString() + "\n")
        if (lineCount() > maxEvents) {
            val all = readAll()
            val consent = all.filter { it.name == TelemetryEvents.CONSENT }.takeLast(maxEvents)
            val ordinary = all.filter { it.name != TelemetryEvents.CONSENT }
            val ordinaryLimit = (maxEvents * KEEP_PERCENT / PERCENT - consent.size).coerceAtLeast(0)
            val kept = consent + ordinary.takeLast(ordinaryLimit)
            writeAll(kept)
        }
    }

    /** Up to [max] of the oldest events, skipping any line that does not parse. */
    @Synchronized
    fun peek(max: Int): List<QueuedEvent> = readAll().take(max)

    /** Drops the [ids] that were sent, keeping anything appended since. */
    @Synchronized
    fun remove(ids: Set<String>) {
        if (ids.isEmpty()) return
        writeAll(readAll().filterNot { it.id in ids })
    }

    @Synchronized
    fun size(): Int = readAll().size

    @Synchronized
    fun retainConsent() {
        writeAll(readAll().filter { it.name == TelemetryEvents.CONSENT })
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun lineCount(): Int =
        if (file.isFile) file.useLines { lines -> lines.count { it.isNotBlank() } } else 0

    private fun readAll(): List<QueuedEvent> =
        if (file.isFile) file.readLines().mapNotNull { QueuedEvent.parse(it) } else emptyList()

    private fun writeAll(events: List<QueuedEvent>) {
        if (events.isEmpty()) {
            file.delete()
            return
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(events.joinToString("") { it.toJson().toString() + "\n" })
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        const val DEFAULT_MAX_EVENTS = 1_000
        private const val KEEP_PERCENT = 80
        private const val PERCENT = 100
    }
}
