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
        url: String = server.url("/v1/telemetry").toString(),
        key: String = "ingest-key"
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

    private fun accepted(body: String = """{"ok":true,"enabled":true,"sample":1.0}""") =
        MockResponse().setResponseCode(202).setBody(body)

    private fun RecordedRequest.json(): JsonObject {
        val bytes = GZIPInputStream(body.inputStream()).readBytes()
        return Json.parseToJsonElement(String(bytes)).jsonObject
    }

    @Test
    fun `off by default, nothing is queued`() {
        val c = client()
        assertFalse(c.status().enabled)
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
    fun `opting in creates a random id and queues the opt-in event`() {
        val c = client()
        c.setEnabled(true)
        val id = prefs.getString(TelemetryClient.KEY_INSTALL_ID, "")!!
        assertEquals(36, id.length)
        assertEquals(listOf(TelemetryEvents.TELEMETRY_OPT_IN), queue.peek(10).map { it.name })
    }

    @Test
    fun `an event outside the schema is dropped`() {
        val c = client()
        c.setEnabled(true)
        c.track(TelemetryEvent("chat_text", mapOf("text" to JsonPrimitive("hello"))))
        assertEquals(1, queue.size())
    }

    @Test
    fun `flush sends a gzip batch with the product, the id and no account`() {
        val c = client()
        c.setEnabled(true)
        c.track(TelemetryEvents.appOpen(cold = true, startMs = 940))
        server.enqueue(accepted())

        assertEquals(FlushOutcome.SENT, runBlocking { c.flush(force = true) })

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("Bearer ingest-key", request.getHeader("Authorization"))
        assertEquals("gzip", request.getHeader("Content-Encoding"))
        val body = request.json()
        assertEquals("gotcha", body["product"]!!.jsonPrimitive.content)
        assertEquals(prefs.getString(TelemetryClient.KEY_INSTALL_ID, ""), body["install_id"]!!.jsonPrimitive.content)
        assertEquals(setOf("schema", "product", "install_id", "sent_at", "app", "events"), body.keys)
        val names = body["events"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("telemetry_opt_in", "app_open"), names)
        assertEquals(0, queue.size())
        assertEquals(now, c.status().lastSentAt)
    }

    @Test
    fun `nothing is sent on mobile data unless allowed`() {
        val c = client()
        c.setEnabled(true)
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
        c.setEnabled(true)
        network = NetworkState(connected = false, metered = false)
        assertEquals(FlushOutcome.OFFLINE, runBlocking { c.flush(force = true) })
        assertEquals(1, queue.size())
    }

    @Test
    fun `flushes are spaced fifteen minutes apart unless forced`() {
        val c = client()
        c.setEnabled(true)
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
        c.setEnabled(true)
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(FlushOutcome.FAILED, runBlocking { c.flush(force = true) })
        assertEquals(1, queue.size())
    }

    @Test
    fun `a refused batch is dropped, not retried forever`() {
        val c = client()
        c.setEnabled(true)
        server.enqueue(MockResponse().setResponseCode(400))
        runBlocking { c.flush(force = true) }
        assertEquals(0, queue.size())
    }

    @Test
    fun `a refused key pauses this version and clears the queue`() {
        val c = client()
        c.setEnabled(true)
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(FlushOutcome.PAUSED, runBlocking { c.flush(force = true) })
        assertEquals(0, queue.size())
        assertTrue(c.status().paused)
        c.track(TelemetryEvents.sessionEnd(10_000))
        assertEquals(0, queue.size())
    }

    @Test
    fun `rate limiting waits for retry_after_ms`() {
        val c = client()
        c.setEnabled(true)
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
        c.setEnabled(true)
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
        c.setEnabled(true)
        server.enqueue(accepted("""{"ok":true,"enabled":true,"sample":0.25}"""))
        runBlocking { c.flush(force = true) }
        // random() is 0.5, above the 0.25 sample, so this one is not kept.
        c.track(TelemetryEvents.sessionEnd(10_000))
        assertEquals(0, queue.size())
    }

    @Test
    fun `a long queue goes out in batches of at most fifty`() {
        val c = client()
        c.setEnabled(true)
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
        c.setEnabled(true)
        c.track(TelemetryEvents.sessionEnd(10_000))
        c.setEnabled(false)
        assertEquals(0, queue.size())
        c.track(TelemetryEvents.sessionEnd(10_000))
        assertEquals(0, queue.size())
        assertEquals(FlushOutcome.OFF, runBlocking { c.flush(force = true) })
    }

    @Test
    fun `delete my data asks the server, then forgets the id`() {
        val c = client()
        c.setEnabled(true)
        val id = prefs.getString(TelemetryClient.KEY_INSTALL_ID, "")!!
        server.enqueue(MockResponse().setResponseCode(200))

        assertTrue(runBlocking { c.deleteMyData() })

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/telemetry/$id", request.path)
        assertFalse(c.status().enabled)
        assertFalse(c.status().hasInstallId)
        assertEquals(0, queue.size())
    }

    @Test
    fun `a failed delete keeps the id so it can be retried`() {
        val c = client()
        c.setEnabled(true)
        server.enqueue(MockResponse().setResponseCode(500))
        assertFalse(runBlocking { c.deleteMyData() })
        assertTrue(c.status().hasInstallId)
    }

    @Test
    fun `a crash is recorded with class and method only`() {
        val c = client()
        c.setEnabled(true)
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
}
