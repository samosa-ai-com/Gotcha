package com.gotcha.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRulesTest {

    @Test fun idleLeaf() = assertEquals("idle", SessionRules.archetype(SessionSnapshot(), 1_000))

    @Test fun setupLeaf() = assertEquals("setup", SessionRules.archetype(SessionSnapshot(setupActions = 1), 11_000))

    @Test fun voiceLeaf() = assertEquals("voice", SessionRules.archetype(SessionSnapshot(calls = 1), 11_000))

    @Test fun lensLeaf() = assertEquals("lens", SessionRules.archetype(SessionSnapshot(lensActions = 2), 11_000))

    @Test fun automationLeaf() = assertEquals(
        "automation",
        SessionRules.archetype(SessionSnapshot(scheduledTasks = 1), 11_000)
    )

    @Test fun chatLeaf() = assertEquals("chat", SessionRules.archetype(SessionSnapshot(runs = 3), 11_000))

    @Test fun glanceLeaf() = assertEquals("glance", SessionRules.archetype(SessionSnapshot(runs = 1), 11_000))

    @Test fun highStruggleLeaf() = assertEquals("high", SessionRules.struggle(SessionSnapshot(retries = 3)))

    @Test fun mildStruggleLeaf() = assertEquals("mild", SessionRules.struggle(SessionSnapshot(retries = 1)))

    @Test fun noStruggleLeaf() = assertEquals("none", SessionRules.struggle(SessionSnapshot()))

    @Test fun archetypeLeavesAndBoundaries() {
        val cases = listOf(
            SessionSnapshot() to (9_999L to "idle"),
            SessionSnapshot(setupActions = 1) to (10_000L to "setup"),
            SessionSnapshot(runs = 1, calls = 1) to (10_000L to "voice"),
            SessionSnapshot(runs = 1, lensActions = 2) to (10_000L to "lens"),
            SessionSnapshot(scheduledTasks = 1) to (10_000L to "automation"),
            SessionSnapshot(runs = 3) to (10_000L to "chat"),
            SessionSnapshot(runs = 1) to (10_000L to "glance"),
            SessionSnapshot() to (10_000L to "idle"),
            SessionSnapshot(runs = 2) to (10_000L to "glance"),
            SessionSnapshot(runs = 2, lensActions = 2) to (10_000L to "glance"),
            SessionSnapshot(runs = 3, lensActions = 2) to (10_000L to "chat")
        )
        cases.forEach { (snapshot, expected) ->
            assertEquals(expected.second, SessionRules.archetype(snapshot, expected.first))
        }
    }

    @Test fun struggleLeavesAndThresholds() {
        val cases = listOf(
            SessionSnapshot() to "none",
            SessionSnapshot(retries = 1) to "mild",
            SessionSnapshot(retries = 2) to "mild",
            SessionSnapshot(retries = 3) to "high",
            SessionSnapshot(runs = 100, runFailures = 49) to "none",
            SessionSnapshot(runs = 100, runFailures = 50) to "high",
            SessionSnapshot(permissionDenials = 1) to "none",
            SessionSnapshot(permissionDenials = 2) to "high",
            SessionSnapshot(toolCalls = 100, toolFailures = 24) to "none",
            SessionSnapshot(toolCalls = 100, toolFailures = 25) to "mild",
            SessionSnapshot(wakeWordsNotLeadingToCall = 1) to "none",
            SessionSnapshot(wakeWordsNotLeadingToCall = 2) to "mild"
        )
        cases.forEach { (snapshot, expected) -> assertEquals(expected, SessionRules.struggle(snapshot)) }
    }

    @Test fun ratioBucketBoundaries() {
        val failCases = mapOf(
            0 to "0",
            1 to "<25",
            24 to "<25",
            25 to "<50",
            49 to "<50",
            50 to "<75",
            74 to "<75",
            75 to "100",
            100 to "100"
        )
        failCases.forEach { (failures, bucket) ->
            val props = SessionRules.profileOf(SessionSnapshot(runs = 100, runFailures = failures), 10_000).props
            assertEquals(bucket, props["run_fail_ratio"]!!.content)
        }
        val jankCases = mapOf(
            0 to "0",
            1 to "<5",
            4 to "<5",
            5 to "<10",
            9 to "<10",
            10 to "<25",
            24 to "<25",
            25 to ">=25"
        )
        jankCases.forEach { (janky, bucket) ->
            val props = SessionRules.profileOf(SessionSnapshot(jankyFrames = janky, totalFrames = 100), 10_000).props
            assertEquals(bucket, props["jank_ratio"]!!.content)
        }
    }

    @Test
    fun countersUseExistingEvents() {
        val counters = SessionCounters()
        counters.reset(1_000)
        counters.record(
            TelemetryEvents.agentRun(
                RunSurface.CHAT, AgentModeWire.OPERATOR, ProviderKind.SAMOSA, EndpointClass.SAMOSA,
                ok = false, durationMs = 300, rounds = 1, toolCalls = 1, toolFailures = 1,
                delegated = false, end = RunEnd.MODEL_ERROR
            ),
            2_000
        )
        counters.record(TelemetryEvents.toolCall("open_app", false, 100), 2_100)
        counters.record(FeatureEvents.permission(PermissionName.MIC, false), 2_200)
        val snapshot = counters.snapshot()
        assertEquals(1, snapshot.runs)
        assertEquals(1, snapshot.runFailures)
        assertEquals(1, snapshot.toolFailures)
        assertEquals(1, snapshot.permissionDenials)
        assertEquals(1_000L, snapshot.firstRunLatencyMs)
        assertEquals(3, snapshot.featuresUsed)
    }

    @Test fun profileIsAcceptedByClosedSchema() {
        val profile = SessionRules.profileOf(SessionSnapshot(runs = 1, featuresUsed = 2), 11_000)
        assertTrue(TelemetrySchema.accepts(profile))
        assertEquals(SessionRules.RULES_VERSION.toString(), profile.props["rules_version"]!!.content)
    }
}
