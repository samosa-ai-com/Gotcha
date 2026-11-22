package com.gotcha.telemetry

import kotlinx.serialization.json.JsonPrimitive

/** A snapshot of bounded counters derived from existing telemetry events. */
data class SessionSnapshot(
    val runs: Int = 0,
    val runFailures: Int = 0,
    val toolCalls: Int = 0,
    val toolFailures: Int = 0,
    val calls: Int = 0,
    val lensActions: Int = 0,
    val setupActions: Int = 0,
    val scheduledTasks: Int = 0,
    val retries: Int = 0,
    val wakeWordsNotLeadingToCall: Int = 0,
    val permissionDenials: Int = 0,
    val featuresUsed: Int = 0,
    val firstRunLatencyMs: Long = 0,
    val jankyFrames: Int = 0,
    val totalFrames: Int = 0,
    val anrLastSession: Boolean = false
)

/** Only event names and closed-vocabulary properties enter this accumulator. */
class SessionCounters {
    private var state = SessionSnapshot()
    private val features = mutableSetOf<String>()
    private var lastRunFailed = false
    private var startedAt = 0L

    @Synchronized
    fun reset(now: Long = 0L) {
        state = SessionSnapshot()
        features.clear()
        lastRunFailed = false
        startedAt = now
    }

    @Synchronized
    fun snapshot(): SessionSnapshot = state.copy(featuresUsed = features.size)

    @Synchronized
    fun addFrames(janky: Int, total: Int) {
        state = state.copy(jankyFrames = state.jankyFrames + janky, totalFrames = state.totalFrames + total)
    }

    @Synchronized
    fun setAnr(value: Boolean) {
        state = state.copy(anrLastSession = value)
    }

    @Synchronized
    fun record(event: TelemetryEvent, now: Long) {
        if (event.name !in setOf(
                TelemetryEvents.APP_OPEN,
                TelemetryEvents.SESSION_END,
                TelemetryEvents.SESSION_PROFILE,
                TelemetryEvents.CONSENT
            )
        ) {
            features.add(event.name)
        }
        fun prop(name: String) = event.props[name]?.content
        when (event.name) {
            TelemetryEvents.AGENT_RUN -> {
                val failed = prop("ok") == "false"
                state = state.copy(
                    runs = state.runs + 1,
                    runFailures = state.runFailures + if (failed) 1 else 0,
                    retries = state.retries + if (lastRunFailed) 1 else 0,
                    firstRunLatencyMs = if (state.runs == 0) (now - startedAt).coerceAtLeast(0) else state.firstRunLatencyMs
                )
                lastRunFailed = prop("end") != RunEnd.REPLY.wire
            }
            TelemetryEvents.TOOL_CALL -> state = state.copy(
                toolCalls = state.toolCalls + 1,
                toolFailures = state.toolFailures + if (prop("ok") == "false") 1 else 0
            )
            TelemetryEvents.VOICE_CALL -> state = state.copy(calls = state.calls + 1)
            FeatureEvents.LENS -> state = state.copy(lensActions = state.lensActions + 1)
            FeatureEvents.SETUP -> state = state.copy(setupActions = state.setupActions + 1)
            TelemetryEvents.SCHEDULED_TASK -> state = state.copy(scheduledTasks = state.scheduledTasks + 1)
            TelemetryEvents.WAKE_WORD -> {
                if (prop("led_to_call") == "false") {
                    state = state.copy(wakeWordsNotLeadingToCall = state.wakeWordsNotLeadingToCall + 1)
                }
            }
            FeatureEvents.PERMISSION -> {
                if (prop("granted") == "false") {
                    state = state.copy(permissionDenials = state.permissionDenials + 1)
                }
            }
        }
    }
}

/** Versioned, deterministic on-device classifier; no model runtime. */
object SessionRules {
    const val RULES_VERSION = 1
    val FAIL_RATIOS = setOf("0", "<25", "<50", "<75", "100")
    val JANK_RATIOS = setOf("0", "<5", "<10", "<25", ">=25")

    fun archetype(s: SessionSnapshot, lengthMs: Long): String = when {
        s.runs == 0 && s.calls == 0 && s.lensActions == 0 && lengthMs < 10_000 -> "idle"
        s.setupActions > 0 -> "setup"
        s.calls > 0 && s.calls >= s.runs -> "voice"
        s.lensActions >= 2 && s.lensActions > s.runs -> "lens"
        s.scheduledTasks > 0 && s.runs == 0 -> "automation"
        s.runs >= 3 -> "chat"
        s.runs >= 1 -> "glance"
        else -> "idle"
    }

    fun struggle(s: SessionSnapshot): String = when {
        s.retries >= 3 || ratioPercent(s.runFailures, s.runs) >= 50 || s.permissionDenials >= 2 -> "high"
        s.retries >= 1 || ratioPercent(s.toolFailures, s.toolCalls) >= 25 ||
            s.wakeWordsNotLeadingToCall >= 2 -> "mild"
        else -> "none"
    }

    private fun ratioPercent(failed: Int, total: Int) = if (total <= 0) 0 else failed * 100 / total

    private fun failRatio(failed: Int, total: Int): String {
        val value = ratioPercent(failed, total)
        return when {
            value == 0 -> "0"
            value < 25 -> "<25"
            value < 50 -> "<50"
            value < 75 -> "<75"
            else -> "100"
        }
    }

    private fun jankRatio(janky: Int, total: Int): String {
        val value = ratioPercent(janky, total)
        return when {
            value == 0 -> "0"
            value < 5 -> "<5"
            value < 10 -> "<10"
            value < 25 -> "<25"
            else -> ">=25"
        }
    }

    fun profileOf(s: SessionSnapshot, lengthMs: Long): TelemetryEvent = TelemetryEvent(
        TelemetryEvents.SESSION_PROFILE,
        mapOf(
            "archetype" to JsonPrimitive(archetype(s, lengthMs)),
            "struggle" to JsonPrimitive(struggle(s)),
            "length_s" to JsonPrimitive(Buckets.seconds(lengthMs / 1000)),
            "runs" to JsonPrimitive(Buckets.count(s.runs)),
            "run_fail_ratio" to JsonPrimitive(failRatio(s.runFailures, s.runs)),
            "tool_fail_ratio" to JsonPrimitive(failRatio(s.toolFailures, s.toolCalls)),
            "retries" to JsonPrimitive(Buckets.count(s.retries)),
            "features_used" to JsonPrimitive(Buckets.count(s.featuresUsed)),
            "first_run_ms" to JsonPrimitive(Buckets.ms(s.firstRunLatencyMs)),
            "jank_ratio" to JsonPrimitive(jankRatio(s.jankyFrames, s.totalFrames)),
            "anr_last_session" to JsonPrimitive(s.anrLastSession),
            "permission_denied" to JsonPrimitive(s.permissionDenials > 0),
            "rules_version" to JsonPrimitive(RULES_VERSION)
        )
    )
}
