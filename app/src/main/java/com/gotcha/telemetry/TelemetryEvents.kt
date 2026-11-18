package com.gotcha.telemetry

import androidx.annotation.StringRes
import com.gotcha.R
import kotlinx.serialization.json.JsonPrimitive

/**
 * One usage event as queued: a name from [TelemetrySchema] and its properties.
 * Built only through the factories in [TelemetryEvents], which take typed
 * arguments, so a call site cannot hand over free text.
 */
data class TelemetryEvent(
    val name: String,
    val props: Map<String, JsonPrimitive>
)

/** A word from a fixed list, as it appears on the wire. */
interface WireWord {
    val wire: String
}

private inline fun <reified E> words(): Vocab.Words where E : Enum<E>, E : WireWord =
    Vocab.Words(enumValues<E>().map { it.wire }.toSet())

private fun word(value: WireWord) = JsonPrimitive(value.wire)

/** How the user turned usage data on. */
enum class OptInSurface(override val wire: String) : WireWord {
    SETTINGS("settings")
}

/** Which thread a crash happened on. */
enum class CrashThread(override val wire: String) : WireWord {
    MAIN("main"),
    OTHER("other")
}

/**
 * The events and their schema, side by side so the two cannot drift: each
 * factory builds exactly the properties its [TelemetrySchema.define] lists.
 */
object TelemetryEvents {

    const val APP_OPEN = "app_open"
    const val SESSION_END = "session_end"
    const val TELEMETRY_OPT_IN = "telemetry_opt_in"
    const val CRASH = "crash"

    /** Longest exception class or frame name sent; longer ones are cut. */
    const val CODE_NAME_MAX = 160

    internal fun defineAll(schema: TelemetrySchema) {
        schema.define(
            APP_OPEN,
            "cold" to Prop(Vocab.Bool),
            "start_ms" to Prop(Vocab.Words(Buckets.MS), optional = true)
        )
        schema.define(
            SESSION_END,
            "length_s" to Prop(Vocab.Words(Buckets.SECONDS))
        )
        schema.define(
            TELEMETRY_OPT_IN,
            "surface" to Prop(words<OptInSurface>())
        )
        schema.define(
            CRASH,
            "exception" to Prop(Vocab.CodeName(CODE_NAME_MAX)),
            "frame" to Prop(Vocab.CodeName(CODE_NAME_MAX), optional = true),
            "thread" to Prop(words<CrashThread>()),
            "foreground" to Prop(Vocab.Bool)
        )
    }

    /** Gotcha came to the foreground. [startMs] is set for a cold start only. */
    fun appOpen(cold: Boolean, startMs: Long?) = TelemetryEvent(
        APP_OPEN,
        buildMap {
            put("cold", JsonPrimitive(cold))
            if (startMs != null) put("start_ms", JsonPrimitive(Buckets.ms(startMs)))
        }
    )

    /** Gotcha left the foreground and stayed away; [lengthMs] is how long it was in front. */
    fun sessionEnd(lengthMs: Long) = TelemetryEvent(
        SESSION_END,
        mapOf("length_s" to JsonPrimitive(Buckets.seconds(lengthMs / MS_PER_SECOND)))
    )

    fun optIn(surface: OptInSurface) = TelemetryEvent(
        TELEMETRY_OPT_IN,
        mapOf("surface" to word(surface))
    )

    /**
     * An uncaught exception: its class and the method it was thrown from, never
     * its message, which can quote anything the app was handling.
     */
    fun crash(exception: String, frame: String?, thread: CrashThread, foreground: Boolean) = TelemetryEvent(
        CRASH,
        buildMap {
            put("exception", JsonPrimitive(exception.take(CODE_NAME_MAX)))
            if (!frame.isNullOrBlank()) put("frame", JsonPrimitive(frame.take(CODE_NAME_MAX)))
            put("thread", word(thread))
            put("foreground", JsonPrimitive(foreground))
        }
    )

    /**
     * What the Privacy page says each event is. A test checks that every event in
     * the schema has a line here, so the list the user reads is the list we send.
     */
    @StringRes
    fun descriptionOf(name: String): Int? = when (name) {
        APP_OPEN -> R.string.telemetry_event_app_open
        SESSION_END -> R.string.telemetry_event_session_end
        TELEMETRY_OPT_IN -> R.string.telemetry_event_opt_in
        CRASH -> R.string.telemetry_event_crash
        else -> null
    }

    private const val MS_PER_SECOND = 1_000L
}
