package com.gotcha.telemetry

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.LlmProvider
import com.gotcha.llm.ChatMessage
import com.gotcha.service.SmartActionDetector
import com.gotcha.ui.tour.TourController
import com.gotcha.ui.tour.defaultTourSteps
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FeatureEventsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prefs: SharedPreferences
    private lateinit var queue: TelemetryQueue

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("feature-events-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        queue = TelemetryQueue(File(tmp.root, "queue.ndjson"))
    }

    @After
    fun tearDown() {
        Telemetry.installForTest(null)
    }

    /** A collecting client whose queue the test reads; nothing is ever sent. */
    private fun collecting(): TelemetryClient {
        val config = TelemetryConfig("https://ingest.invalid/v1/telemetry", "key")
        return TelemetryClient(
            config = config,
            prefs = prefs,
            queue = queue,
            sender = TelemetrySender(config.url, config.key),
            appInfo = { AppInfo("1.5.0", 9, "release", 34, "en", "phone") },
            network = { NetworkState(connected = false, metered = false) },
            executor = { it.run() }
        ).also {
            prefs.edit().putString(TelemetryClient.KEY_INSTALL_ID, "server-install")
                .putString(TelemetryClient.KEY_TOKEN, "install-token")
                .putLong(TelemetryClient.KEY_CONSENT_AT, System.currentTimeMillis()).commit()
            Telemetry.installForTest(it)
        }
    }

    private fun queuedProps(): List<Map<String, String>> =
        queue.peek(100).map { e -> e.props.mapValues { it.value.content } }

    @Test
    fun `permission watch records changes only, never the starting point`() {
        var granted = setOf(PermissionName.MIC, PermissionName.OVERLAY)
        val watch = PermissionWatch(prefs) { granted }

        assertTrue("first look is the baseline", watch.changes().isEmpty())
        assertTrue(watch.changes().isEmpty())

        granted = setOf(PermissionName.OVERLAY, PermissionName.ACCESSIBILITY)
        val events = watch.changes()
        assertEquals(
            setOf("mic" to "false", "accessibility" to "true"),
            events.map { it.props["name"]!!.content to it.props["granted"]!!.content }.toSet()
        )
        events.forEach { assertTrue(TelemetrySchema.accepts(it)) }

        watch.reset()
        granted = emptySet()
        assertTrue("after a reset the next look is a new baseline", watch.changes().isEmpty())
    }

    @Test
    fun `a suggestion is named by its kind, never its payload`() {
        val dial = SmartActionDetector.encode(SmartActionDetector.TYPE_DIAL, "+91 98765 43210")
        assertEquals(LensAction.DIAL, LensAction.ofSuggestion(dial))
        val fetch = SmartActionDetector.encode(SmartActionDetector.TYPE_FETCH, "https://example.com/private")
        assertEquals(LensAction.SUMMARIZE_LINK, LensAction.ofSuggestion(fetch))
        assertEquals(LensAction.PROMPT, LensAction.ofSuggestion("Translate this message from my landlord"))
        val event = FeatureEvents.lens(LensKind.COMPANION, LensAction.ofSuggestion(dial))
        assertEquals(mapOf("kind" to "companion", "action" to "dial"), event.props.mapValues { it.value.content })
    }

    @Test
    fun `an imported skill is sent as custom`() {
        val event = FeatureEvents.skillUsed("my_banking_routine", SkillVia.FETCHED)
        assertEquals("custom", event.props["skill"]!!.content)
        assertTrue(TelemetrySchema.accepts(event))
        val forged = event.copy(props = event.props + ("skill" to JsonPrimitive("my_banking_routine")))
        assertFalse(TelemetrySchema.accepts(forged))
    }

    @Test
    fun `an offered skill is counted once however many rounds repeat it`() {
        collecting()
        val summary = ChatMessage(
            role = "user",
            content = JsonPrimitive(
                "<available-skills>\n• alpha_skill: does a\n• beta_skill (community): does b\n</available-skills>"
            )
        )
        val seen = mutableSetOf<String>()
        repeat(3) { SkillTelemetry.trackOffered(summary, seen) }
        assertEquals(setOf("alpha_skill", "beta_skill"), seen)
        assertEquals(2, queue.size())
        assertTrue(queuedProps().all { it == mapOf("skill" to "custom", "via" to "offered") })
    }

    @Test
    fun `the tour reports reached, done, skipped and abandoned`() {
        collecting()
        val controller = TourController(defaultTourSteps())
        controller.start()
        controller.acknowledge()
        controller.start(fromStepId = "samosa_sign_in")
        controller.skipMissingAnchor()
        controller.cancel()
        assertEquals(
            listOf(
                "open_settings" to "reached",
                "open_settings" to "done",
                "open_ai" to "reached",
                "samosa_sign_in" to "reached",
                "samosa_sign_in" to "skipped",
                "save_ai_config" to "reached",
                "save_ai_config" to "abandoned"
            ),
            queuedProps().map { it["step"] to it["action"] }
        )
    }

    @Test
    fun `setup names the kind of server and whether a key is present, nothing more`() {
        val event = FeatureEvents.setup(
            SetupAction.TESTED,
            LlmProvider.OPENAI_COMPATIBLE,
            "http://192.168.1.20:8080/v1/",
            hasKey = true,
            testOk = false
        )
        assertEquals(
            mapOf(
                "action" to "tested",
                "provider" to "byo",
                "endpoint" to "local",
                "has_key" to "true",
                "test_ok" to "false"
            ),
            event.props.mapValues { it.value.content }
        )
        assertTrue(TelemetrySchema.accepts(event))
    }

    @Test
    fun `connector ids come from the catalogue`() {
        assertTrue(TelemetrySchema.accepts(FeatureEvents.connector("notion", ConnectorAction.CONNECT)))
        assertTrue(TelemetrySchema.accepts(FeatureEvents.connector("mcp", ConnectorAction.REFRESH, mcpServers = 3)))
        assertFalse(TelemetrySchema.accepts(FeatureEvents.connector("my-home-server", ConnectorAction.CONNECT)))
    }
}
