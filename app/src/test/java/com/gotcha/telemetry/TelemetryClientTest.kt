package com.gotcha.telemetry

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

@RunWith(RobolectricTestRunner::class)
class TelemetryClientTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var prefs: SharedPreferences
    private lateinit var queue: TelemetryQueue
    private var now = 1_700_000_000_000L
    private var network = NetworkState(connected = true, metered = false)

    private val app = AppInfo("1.5.0", 9, "release", 34, "en-IN", "phone")

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("telemetry-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        queue = TelemetryQueue(File(tmp.root, "queue.ndjson"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(
        url: String = server.url("/").toString(),
        key: String = "enrollment-key"
    ): TelemetryClient {
        val config = TelemetryConfig(url, key)
        return TelemetryClient(
            config = config,
            prefs = prefs,
            queue = queue,
            sender = if (config.configured) TelemetrySender(config.url, config.key) else null,
            appInfo = { app },
            network = { network },
            clock = { now },
            random = { 0.5 },
            executor = { it.run() }
        )
    }

    private fun ready(c: TelemetryClient, usageData: Boolean = true) {
        prefs.edit().putString(TelemetryClient.KEY_INSTALL_ID, "server-install")
            .putString(TelemetryClient.KEY_TOKEN, "install-token").commit()
        c.recordConsent(usageData, ConsentSurface.FIRST_RUN)
    }

    private fun accepted(body: String = """{"ok":true,"enabled":true,"sample":1.0}""") =
        MockResponse().setResponseCode(202).setBody(body)

    @Test
    fun `failed first registration recovers on a later flush`() {
        val c = client()
        c.recordConsent(true, ConsentSurface.FIRST_RUN)
        server.enqueue(MockResponse().setResponseCode(503))
        assertFalse(runBlocking { c.ensureRegistered() })
        now += 60_000
        server.enqueue(registration())
        server.enqueue(accepted())
        assertEquals(FlushOutcome.SENT, runBlocking { c.flush(force = true) })
        assertEquals(
            listOf("/v1/install", "/v1/install", "/v1/events"),
            (1..3).map { server.takeRequest(1, TimeUnit.SECONDS)!!.path }
        )
        assertEquals(0, queue.size())
    }

    @Test
    fun `accepted response with rejected events is distinguishable and dropped`() {
        val c = client()
        ready(c)
        server.enqueue(accepted("""{"accepted":0,"rejected":1,"enabled":true}"""))
        assertEquals(FlushOutcome.REJECTED, runBlocking { c.flush(force = true) })
        assertEquals(0, queue.size())
    }

    @Test
    fun `transient registration failures back off without pausing this version`() {
        val c = client()
        c.recordConsent(true, ConsentSurface.FIRST_RUN)
        repeat(4) {
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(FlushOutcome.FAILED, runBlocking { c.flush(force = true) })
            assertFalse(c.status().paused)
            now += 60L * 60L * 1000L
        }
        server.enqueue(registration())
        server.enqueue(accepted())
        assertEquals(FlushOutcome.SENT, runBlocking { c.flush(force = true) })
    }

    private fun RecordedRequest.json(): JsonObject {
        val bytes = GZIPInputStream(body.inputStream()).readBytes()
        return Json.parseToJsonElement(String(bytes)).jsonObject
    }

    @Test
    fun `default on before consent does not queue`() {
        val c = client()
        // The switch reads on, but nothing is recorded until the first screen
        // has been answered: the default is a choice in force, not consent.
        assertTrue(c.status().enabled)
        assertFalse(c.hasConsent)
        c.track(TelemetryEvents.appOpen(cold = true, startMs = 500))
        assertEquals(0, queue.size())
    }

    @Test
    fun `a build without an endpoint stays off even when switched on`() {
        val c = client(url = "", key = "")
        c.setEnabled(true)
        c.track(TelemetryEvents.appOpen(cold = true, startMs = 500))
        assertEquals(0, queue.size())
        assertFalse(c.status().configured)
        assertEquals(FlushOutcome.NOT_CONFIGURED, runBlocking { c.flush(force = true) })
    }

    @Test
    fun `registration runs with usage data off and reuses installed time`() {
        val c = client()
        assertEquals(null, c.regionFlow.value)
        c.recordConsent(false, ConsentSurface.FIRST_RUN)
        server.enqueue(MockResponse().setResponseCode(503))
        assertFalse(runBlocking { c.ensureRegistered() })
        val installedAt = prefs.getLong(TelemetryClient.KEY_INSTALLED_AT, 0)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"install_id":"server-install","token":"install-token","region":"other",
                    "consent_required":false,"config":{"enabled":true,"sample":1.0}}"""
            )
        )
        assertTrue(runBlocking { c.ensureRegistered() })
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("/v1/install", first.path)
        assertEquals("/v1/install", second.path)
        assertEquals("Bearer enrollment-key", second.getHeader("Authorization"))
        assertEquals(
            installedAt,
            Json.parseToJsonElement(second.body.readUtf8()).jsonObject["installed_at"]!!.jsonPrimitive.content.toLong()
        )
        assertEquals("other", c.region)
        assertEquals("other", c.regionFlow.value)
        assertFalse(c.status().enabled)
        assertEquals(2, server.requestCount)
        assertTrue(runBlocking { c.ensureRegistered() })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `every region defaults on`() {
        val c = client()
        assertTrue(c.defaultUsageData)
        assertTrue(c.status().enabled)
    }

    @Test
    fun `declined consent is queued`() {
        val c = client()
        c.recordConsent(false, ConsentSurface.FIRST_RUN)
        assertEquals(listOf(TelemetryEvents.CONSENT), queue.peek(10).map { it.name })
        assertFalse(c.isActive)
    }

    @Test
    fun `an event outside the schema is dropped`() {
        val c = client()
        ready(c)
        c.track(TelemetryEvent("chat_text", mapOf("text" to JsonPrimitive("hello"))))
        assertEquals(1, queue.size())
    }

    @Test
    fun `flush sends a gzip batch with the product, the id and no account`() {
        val c = client()
        ready(c)
        c.track(TelemetryEvents.appOpen(cold = true, startMs = 940))
        server.enqueue(accepted())

        assertEquals(FlushOutcome.SENT, runBlocking { c.flush(force = true) })

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("Bearer install-token", request.getHeader("Authorization"))
        assertEquals("gzip", request.getHeader("Content-Encoding"))
        val body = request.json()
        assertEquals("gotcha", body["product"]!!.jsonPrimitive.content)
        assertEquals(prefs.getString(TelemetryClient.KEY_INSTALL_ID, ""), body["install_id"]!!.jsonPrimitive.content)
        assertEquals(setOf("schema", "product", "install_id", "sent_at", "app", "events"), body.keys)
        val names = body["events"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("consent", "app_open"), names)
        assertEquals(0, queue.size())
        assertEquals(now, c.status().lastSentAt)
    }

    @Test
    fun `nothing is sent on mobile data unless allowed`() {
        val c = client()
        ready(c)
        network = NetworkState(connected = true, metered = true)
        assertEquals(FlushOutcome.METERED, runBlocking { c.flush(force = true) })
        assertEquals(0, server.requestCount)

        c.setMobileData(true)
        server.enqueue(accepted())
        assertEquals(FlushOutcome.SENT, runBlocking { c.flush(force = true) })
    }

    @Test
    fun `offline keeps the queue`() {
        val c = client()
        ready(c)
        network = NetworkState(connected = false, metered = false)
        assertEquals(FlushOutcome.OFFLINE, runBlocking { c.flush(force = true) })
        assertEquals(1, queue.size())
    }

    @Test
    fun `flushes are spaced fifteen minutes apart unless forced`() {
        val c = client()
        ready(c)
        server.enqueue(accepted())
        assertEquals(FlushOutcome.SENT, runBlocking { c.flush() })
        c.track(TelemetryEvents.sessionEnd(10_000))
        now += 60_000
        assertEquals(FlushOutcome.NOT_DUE, runBlocking { c.flush() })
        now += TelemetryClient.MIN_FLUSH_INTERVAL_MS
        server.enqueue(accepted())
        assertEquals(FlushOutcome.SENT, runBlocking { c.flush() })
    }

    @Test
    fun `a server error keeps the batch for next time`() {
        val c = client()
        ready(c)
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(FlushOutcome.FAILED, runBlocking { c.flush(force = true) })
        assertEquals(1, queue.size())
    }

    @Test
    fun `rejected consent batch does not block the next batch`() {
        val c = client()
        ready(c)
        server.enqueue(MockResponse().setResponseCode(400))
        assertEquals(FlushOutcome.REJECTED, runBlocking { c.flush(force = true) })
        assertEquals(0, queue.size())
        c.track(TelemetryEvents.sessionEnd(10_000))
        server.enqueue(accepted())
        assertEquals(FlushOutcome.SENT, runBlocking { c.flush(force = true) })
        assertEquals("/v1/events", server.takeRequest().path)
        val nextName = server.takeRequest().json()["events"]!!.jsonArray.last()
            .jsonObject["name"]!!.jsonPrimitive.content
        assertEquals("session_end", nextName)
    }

    @Test
    fun `revoked install token re-registers and sends on next flush`() {
        val c = client()
        ready(c)
        prefs.edit().putLong(TelemetryClient.KEY_INSTALLED_AT, now / 86_400_000L * 86_400_000L).commit()
        val oldInstalledAt = prefs.getLong(TelemetryClient.KEY_INSTALLED_AT, 0)
        now += 2L * 86_400_000L
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(registration())
        assertEquals(FlushOutcome.FAILED, runBlocking { c.flush(force = true) })
        assertEquals(1, queue.size())
        assertFalse(c.status().paused)
        assertEquals("/v1/events", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
        val registrationRequest = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/install", registrationRequest.path)
        val newInstalledAt = Json.parseToJsonElement(registrationRequest.body.readUtf8())
            .jsonObject["installed_at"]!!.jsonPrimitive.content.toLong()
        assertTrue(newInstalledAt != oldInstalledAt)
        assertEquals(0L, newInstalledAt % 86_400_000L)
        server.enqueue(accepted())
        assertEquals(FlushOutcome.SENT, runBlocking { c.flush(force = true) })
        assertEquals("Bearer replacement-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `same-day token revocation changes the registration day`() {
        val c = client()
        ready(c)
        val today = now / 86_400_000L * 86_400_000L
        prefs.edit().putLong(TelemetryClient.KEY_INSTALLED_AT, today).commit()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(registration())
        assertEquals(FlushOutcome.FAILED, runBlocking { c.flush(force = true) })
        server.takeRequest(1, TimeUnit.SECONDS)
        val fresh = Json.parseToJsonElement(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
            .jsonObject["installed_at"]!!.jsonPrimitive.content.toLong()
        assertTrue(fresh != today)
        assertEquals(0L, fresh % 86_400_000L)
    }

    @Test
    fun `refused enrollment key pauses after revoked token`() {
        val c = client()
        ready(c)
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(FlushOutcome.PAUSED, runBlocking { c.flush(force = true) })
        assertTrue(c.status().paused)
        assertEquals(1, queue.size())
        assertEquals("/v1/events", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
        assertEquals("/v1/install", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun `repeated re-registration failures back off without pausing this version`() {
        val c = client()
        ready(c)
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(FlushOutcome.FAILED, runBlocking { c.flush(force = true) })
        assertFalse(c.status().paused)
        server.enqueue(MockResponse().setResponseCode(503))
        now += 60L * 60L * 1000L
        assertEquals(FlushOutcome.FAILED, runBlocking { c.flush(force = true) })
        assertFalse(c.status().paused)
        assertEquals(1, queue.size())
        val paths = (1..3).map { server.takeRequest(1, TimeUnit.SECONDS)!!.path }
        assertEquals(listOf("/v1/events", "/v1/install", "/v1/install"), paths)
    }

    private fun registration() = MockResponse().setResponseCode(200).setBody(
        """{"install_id":"replacement-install","token":"replacement-token","region":"other",
            "consent_required":false,"config":{"enabled":true,"sample":1.0}}"""
    )

    @Test
    fun `rate limiting waits for retry_after_ms`() {
        val c = client()
        ready(c)
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"retry_after_ms":60000}"""))
        assertEquals(FlushOutcome.FAILED, runBlocking { c.flush(force = true) })
        now += 30_000
        assertEquals(FlushOutcome.NOT_DUE, runBlocking { c.flush(force = true) })
        now += 31_000
        server.enqueue(accepted())
        assertEquals(FlushOutcome.SENT, runBlocking { c.flush(force = true) })
    }

    @Test
    fun `the server's kill switch pauses sending for a day`() {
        val c = client()
        ready(c)
        server.enqueue(accepted("""{"ok":true,"enabled":false}"""))
        assertEquals(FlushOutcome.PAUSED, runBlocking { c.flush(force = true) })
        assertTrue(c.status().paused)
        c.track(TelemetryEvents.sessionEnd(10_000))
        assertEquals(0, queue.size())
        now += TelemetryClient.SERVER_PAUSE_MS + 1
        assertFalse(c.status().paused)
    }

    @Test
    fun `server sampling thins events`() {
        val c = client()
        ready(c)
        server.enqueue(accepted("""{"ok":true,"enabled":true,"sample":0.25}"""))
        runBlocking { c.flush(force = true) }
        // random() is 0.5, above the 0.25 sample, so this one is not kept.
        c.track(TelemetryEvents.sessionEnd(10_000))
        assertEquals(0, queue.size())
    }

    @Test
    fun `a long queue goes out in batches of at most fifty`() {
        val c = client()
        ready(c)
        repeat(119) { c.track(TelemetryEvents.sessionEnd(10_000)) }
        repeat(3) { server.enqueue(accepted()) }
        assertEquals(FlushOutcome.SENT, runBlocking { c.flush(force = true) })
        val sizes = (1..3).map { (server.takeRequest().json()["events"] as JsonArray).size }
        assertEquals(listOf(50, 50, 20), sizes)
        assertEquals(0, queue.size())
    }

    @Test
    fun `opting out deletes the queue at once`() {
        val c = client()
        ready(c)
        c.track(TelemetryEvents.sessionEnd(10_000))
        c.setEnabled(false)
        assertEquals(2, queue.size())
        c.track(TelemetryEvents.sessionEnd(10_000))
        assertEquals(2, queue.size())
        server.enqueue(accepted())
        assertEquals(FlushOutcome.SENT, runBlocking { c.flush(force = true) })
        assertEquals(0, queue.size())
    }

    @Test
    fun `sender uses POST for registration and events`() {
        val c = client()
        server.enqueue(registration())
        assertTrue(runBlocking { c.ensureRegistered() })
        c.recordConsent(true, ConsentSurface.FIRST_RUN)
        server.enqueue(accepted())
        assertEquals(FlushOutcome.SENT, runBlocking { c.flush(force = true) })
        assertEquals(listOf("POST", "POST"), listOf(server.takeRequest().method, server.takeRequest().method))
    }

    @Test
    fun `a crash is recorded with class and method only`() {
        val c = client()
        ready(c)
        val throwable = IllegalStateException("user typed this").apply {
            stackTrace = arrayOf(StackTraceElement("com.gotcha.agent.ChatRunner", "start", "ChatRunner.kt", 1))
        }
        c.recordCrash(Thread("worker"), throwable, foreground = true)
        val crash = queue.peek(10).last()
        assertEquals("crash", crash.name)
        assertEquals("java.lang.IllegalStateException", crash.props["exception"]!!.content)
        assertEquals("ChatRunner.start", crash.props["frame"]!!.content)
        assertEquals("other", crash.props["thread"]!!.content)
        assertFalse(crash.toJson().toString().contains("user typed this"))
    }

    @Test
    fun `the regional default follows the registered region`() {
        val c = client()

        fun region(value: String) {
            prefs.edit()
                .putString(TelemetryClient.KEY_REGION, value)
                .commit()
        }

        // Usage data is on by default in every region; the region only decides
        // whether the row is shown on the first screen.
        region("eea")
        assertTrue(c.defaultUsageData)
        assertTrue(c.status().enabled)

        region("uk")
        assertTrue(c.defaultUsageData)
        assertTrue(c.status().enabled)

        region("other")
        assertTrue(c.defaultUsageData)
        assertTrue(c.status().enabled)
    }

    @Test
    fun `a choice from settings is recorded with the settings surface`() {
        val c = client()
        c.recordConsent(true, ConsentSurface.SETTINGS)
        val event = queue.peek(10).last()
        assertEquals(TelemetryEvents.CONSENT, event.name)
        assertEquals("settings", event.props["surface"]!!.content)
        assertEquals("accepted", event.props["usage_data"]!!.content)
        assertEquals("accepted", event.props["terms"]!!.content)
        assertTrue(c.hasConsent)
        assertTrue(c.status().enabled)
    }

    @Test
    fun `a later choice flips the reported state`() {
        val c = client()
        ready(c)
        c.setEnabled(false)
        assertFalse(c.status().enabled)
        c.setEnabled(true)
        assertTrue(c.status().enabled)
    }

    @Test
    fun `toggle before consent records a settings choice`() {
        val c = client()
        c.setEnabled(false)
        assertTrue(c.hasConsent)
        assertFalse(c.status().enabled)
        assertEquals("settings", queue.peek(1).single().props["surface"]!!.content)
    }

    @Test
    fun `enabling before consent overrides an off regional default`() {
        val c = client()
        prefs.edit().putString(TelemetryClient.KEY_REGION, "eea").apply()
        c.setEnabled(true)
        assertTrue(c.hasConsent)
        assertTrue(c.status().enabled)
        assertEquals("accepted", queue.peek(1).single().props["usage_data"]!!.content)
    }
}
