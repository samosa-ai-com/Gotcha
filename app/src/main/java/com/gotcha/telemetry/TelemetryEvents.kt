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

/** Which backend answered: Samosa AI, or the user's own OpenAI-compatible endpoint. */
enum class ProviderKind(override val wire: String) : WireWord {
    SAMOSA("samosa"),
    BYO("byo")
}

/** What a model request was for. */
enum class LlmFeature(override val wire: String) : WireWord {
    AGENT("agent"),
    TITLE("title"),
    COMPACTION("compaction"),
    SUB_AGENT("sub_agent"),
    NAVIGATOR("navigator"),
    BALL("ball"),
    SHARE_CARD("share_card"),
    CONNECTION_TEST("connection_test"),
    DICTATION("dictation")
}

/** How a model request failed. */
enum class AiError(override val wire: String) : WireWord {
    AUTH("auth"),
    RATE("rate"),
    TIMEOUT("timeout"),
    OFFLINE("offline"),
    HTTP_4XX("http_4xx"),
    HTTP_5XX("http_5xx"),
    PARSE("parse"),
    EMPTY("empty"),
    OTHER("other")
}

/** Where an agent run was started from. */
enum class RunSurface(override val wire: String) : WireWord {
    CHAT("chat"),
    CALL("call"),
    SCHEDULED("scheduled")
}

/** How an agent run ended; one value per terminal path in AgentEngine. */
enum class RunEnd(override val wire: String) : WireWord {
    REPLY("reply"),
    FINISH_TASK("finish_task"),
    MODEL_ERROR("model_error"),
    EMPTY("empty"),
    STUCK("stuck"),
    DELEGATION_LIMIT("delegation_limit"),
    EXHAUSTED("exhausted")
}

enum class AgentModeWire(override val wire: String) : WireWord {
    MONITOR("monitor"),
    OPERATOR("operator")
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
    const val AGENT_RUN = "agent_run"
    const val AI_REQUEST = "ai_request"
    const val TOOL_CALL = "tool_call"

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
        schema.define(
            AGENT_RUN,
            "surface" to Prop(words<RunSurface>()),
            "mode" to Prop(words<AgentModeWire>()),
            "provider" to Prop(words<ProviderKind>()),
            "endpoint" to Prop(words<EndpointClass>()),
            "ok" to Prop(Vocab.Bool),
            "ms" to Prop(Vocab.Words(Buckets.MS)),
            "rounds" to Prop(Vocab.Words(Buckets.COUNT)),
            "tool_calls" to Prop(Vocab.Words(Buckets.COUNT)),
            "tool_failures" to Prop(Vocab.Words(Buckets.COUNT)),
            "delegated" to Prop(Vocab.Bool),
            "end" to Prop(words<RunEnd>())
        )
        schema.define(
            AI_REQUEST,
            "feature" to Prop(words<LlmFeature>()),
            "provider" to Prop(words<ProviderKind>()),
            "endpoint" to Prop(words<EndpointClass>()),
            "model" to Prop(Vocab.ModelId, optional = true),
            "ok" to Prop(Vocab.Bool),
            "latency_ms" to Prop(Vocab.Words(Buckets.MS)),
            "prompt_tokens" to Prop(Vocab.Words(Buckets.TOKENS), optional = true),
            "completion_tokens" to Prop(Vocab.Words(Buckets.TOKENS), optional = true),
            "cache_hit" to Prop(Vocab.Bool),
            "error" to Prop(words<AiError>(), optional = true)
        )
        schema.define(
            TOOL_CALL,
            "tool" to Prop(Vocab.Words(BoundedNames.toolVocabulary)),
            "ok" to Prop(Vocab.Bool),
            "ms" to Prop(Vocab.Words(Buckets.MS))
        )
    }

    /**
     * One agent run, from its terminal path. [toolNames] and [failedTools] are
     * counts only; the tools themselves are their own [toolCall] events.
     */
    @Suppress("LongParameterList")
    fun agentRun(
        surface: RunSurface,
        mode: AgentModeWire,
        provider: ProviderKind,
        endpoint: EndpointClass,
        ok: Boolean,
        durationMs: Long,
        rounds: Int,
        toolCalls: Int,
        toolFailures: Int,
        delegated: Boolean,
        end: RunEnd
    ) = TelemetryEvent(
        AGENT_RUN,
        mapOf(
            "surface" to word(surface),
            "mode" to word(mode),
            "provider" to word(provider),
            "endpoint" to word(endpoint),
            "ok" to JsonPrimitive(ok),
            "ms" to JsonPrimitive(Buckets.ms(durationMs)),
            "rounds" to JsonPrimitive(Buckets.count(rounds)),
            "tool_calls" to JsonPrimitive(Buckets.count(toolCalls)),
            "tool_failures" to JsonPrimitive(Buckets.count(toolFailures)),
            "delegated" to JsonPrimitive(delegated),
            "end" to word(end)
        )
    )

    /**
     * One model request. [model] is sent for Samosa AI only, whose model ids come
     * from its own catalogue; the name of a model on the user's own server is
     * never sent.
     */
    @Suppress("LongParameterList")
    fun aiRequest(
        feature: LlmFeature,
        provider: ProviderKind,
        endpoint: EndpointClass,
        model: String?,
        ok: Boolean,
        latencyMs: Long,
        promptTokens: Int?,
        completionTokens: Int?,
        cacheHit: Boolean,
        error: AiError?
    ) = TelemetryEvent(
        AI_REQUEST,
        buildMap {
            put("feature", word(feature))
            put("provider", word(provider))
            put("endpoint", word(endpoint))
            if (provider == ProviderKind.SAMOSA && !model.isNullOrBlank()) put("model", JsonPrimitive(model))
            put("ok", JsonPrimitive(ok))
            put("latency_ms", JsonPrimitive(Buckets.ms(latencyMs)))
            promptTokens?.takeIf { it > 0 }?.let { put("prompt_tokens", JsonPrimitive(Buckets.tokens(it))) }
            completionTokens?.takeIf { it > 0 }?.let { put("completion_tokens", JsonPrimitive(Buckets.tokens(it))) }
            put("cache_hit", JsonPrimitive(cacheHit))
            error?.let { put("error", word(it)) }
        }
    )

    /** One tool execution: which built-in tool (see [BoundedNames.tool]), whether it worked, how long. */
    fun toolCall(name: String, ok: Boolean, durationMs: Long) = TelemetryEvent(
        TOOL_CALL,
        mapOf(
            "tool" to JsonPrimitive(BoundedNames.tool(name)),
            "ok" to JsonPrimitive(ok),
            "ms" to JsonPrimitive(Buckets.ms(durationMs))
        )
    )

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
        AGENT_RUN -> R.string.telemetry_event_agent_run
        AI_REQUEST -> R.string.telemetry_event_ai_request
        TOOL_CALL -> R.string.telemetry_event_tool_call
        else -> null
    }

    private const val MS_PER_SECOND = 1_000L
}
