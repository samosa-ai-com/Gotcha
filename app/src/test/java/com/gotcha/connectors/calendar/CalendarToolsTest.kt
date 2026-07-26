package com.gotcha.connectors.calendar

import com.gotcha.connectors.CredentialStore
import com.gotcha.connectors.google.GmailApi
import com.gotcha.connectors.google.GoogleCalendarApi
import com.gotcha.connectors.google.GoogleConnector
import com.gotcha.connectors.microsoft.GraphApi
import com.gotcha.connectors.microsoft.MicrosoftConnector
import com.gotcha.connectors.oauth.TokenSet
import com.gotcha.tools.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class InMemoryCredentialStore : CredentialStore {
    private val map = mutableMapOf<String, String>()
    override fun loadRaw(connectorId: String): String? = map[connectorId]
    override fun saveRaw(connectorId: String, blob: String) { map[connectorId] = blob }
    override fun clear(connectorId: String) { map.remove(connectorId) }
}

private class FakeDeviceCalendar : DeviceCalendar {
    var listCalls = 0
    var createCalls = 0
    var lastSearch: String? = null
    var lastTitle: String? = null

    override fun listEvents(
        daysAhead: Int?,
        fromDate: String?,
        toDate: String?,
        search: String?
    ): ToolResult {
        listCalls++
        lastSearch = search
        return ToolResult.ok("device events")
    }

    @Suppress("LongParameterList")
    override fun createEvent(
        title: String,
        start: String,
        end: String?,
        location: String?,
        description: String?,
        allDay: Boolean?,
        reminderMinutes: Int?,
        calendarName: String?,
        attendees: List<String>?,
        recurrence: String?,
        recurrenceCount: Int?,
        recurrenceUntil: String?
    ): ToolResult {
        createCalls++
        lastTitle = title
        return ToolResult.ok("device event created")
    }
}

class CalendarToolsTest {

    private lateinit var googleServer: MockWebServer
    private lateinit var graphServer: MockWebServer
    private lateinit var device: FakeDeviceCalendar
    private lateinit var google: GoogleConnector
    private lateinit var microsoft: MicrosoftConnector

    /** Fixed "now" so window arithmetic in assertions is deterministic. */
    private val now = 1_767_225_600_000L

    @Before
    fun setUp() {
        googleServer = MockWebServer()
        googleServer.start()
        graphServer = MockWebServer()
        graphServer.start()
        device = FakeDeviceCalendar()
        google = GoogleConnector(
            store = InMemoryCredentialStore(),
            api = GmailApi(baseUrl = googleServer.url("/gmail").toString()),
            calendarApi = GoogleCalendarApi(
                baseUrl = googleServer.url("/calendar/v3").toString().trimEnd('/')
            ),
            tokenUrl = googleServer.url("/token").toString()
        )
        microsoft = MicrosoftConnector(
            store = InMemoryCredentialStore(),
            api = GraphApi(baseUrl = graphServer.url("/v1.0").toString().trimEnd('/'))
        )
    }

    @After
    fun tearDown() {
        googleServer.shutdown()
        graphServer.shutdown()
    }

    private fun tools(
        withGoogle: Boolean = false,
        withMicrosoft: Boolean = false
    ) = CalendarTools(
        device = device,
        google = { google.takeIf { withGoogle } },
        microsoft = { microsoft.takeIf { withMicrosoft } },
        clock = { now }
    )

    /**
     * Token expiry uses the real clock — the connectors validate it against
     * System.currentTimeMillis(), so [now] (which only fixes the router's date
     * window) must not be used here or every call would trigger a refresh.
     */
    private fun liveExpiry() = System.currentTimeMillis() + 3_600_000

    private suspend fun connectGoogle(scopes: List<String>) {
        googleServer.enqueue(MockResponse().setBody("""{"emailAddress":"u@gmail.com"}"""))
        google.completeConnect("cid", "secret", TokenSet("at", "rt", liveExpiry()), scopes)
        googleServer.takeRequest() // the profile call
    }

    private suspend fun connectMicrosoft() {
        graphServer.enqueue(MockResponse().setBody("""{"mail":"u@outlook.com"}"""))
        microsoft.completeConnect("cid", "common", TokenSet("at", "rt", liveExpiry()))
        graphServer.takeRequest() // the /me call
    }

    private fun args(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject(block)

    // ---- routing ----

    @Test
    fun `list_calendar_events defaults to the device calendar`() = runTest {
        val result = tools().execute("list_calendar_events", args())
        assertTrue(result.success)
        assertEquals(1, device.listCalls)
        assertEquals("device events", result.message)
    }

    @Test
    fun `explicit device source also routes to the device calendar`() = runTest {
        tools().execute("list_calendar_events", args { put("source", "device") })
        assertEquals(1, device.listCalls)
    }

    @Test
    fun `create_calendar_event defaults to the device calendar`() = runTest {
        val result = tools().execute(
            "create_calendar_event",
            args {
                put("title", "Dentist")
                put("start", "2026-07-17 15:30")
            }
        )
        assertTrue(result.success)
        assertEquals(1, device.createCalls)
        assertEquals("Dentist", device.lastTitle)
    }

    @Test
    fun `an unknown source is rejected without touching any backend`() = runTest {
        val result = tools().execute("list_calendar_events", args { put("source", "yahoo") })
        assertTrue(!result.success)
        assertTrue(result.message.contains("device"))
        assertEquals(0, device.listCalls)
    }

    // ---- google ----

    @Test
    fun `google source lists events with gcal-prefixed ids`() = runTest {
        connectGoogle(listOf(GoogleConnector.SCOPE_GMAIL_MODIFY, GoogleConnector.SCOPE_CALENDAR))
        googleServer.enqueue(
            MockResponse().setBody(
                """{"items":[{"id":"e1","summary":"Standup",
                   "start":{"dateTime":"2026-01-01T09:00:00Z"},
                   "end":{"dateTime":"2026-01-01T09:15:00Z"}}]}"""
            )
        )
        val result = tools(withGoogle = true)
            .execute("list_calendar_events", args { put("source", "google") })

        assertTrue(result.success)
        assertTrue(result.message.contains("[gcal:e1]"))
        assertTrue(result.message.contains("Standup"))
        assertEquals("the device backend must not be touched", 0, device.listCalls)
    }

    @Test
    fun `google source without the calendar scope steers to reconnect`() = runTest {
        connectGoogle(listOf(GoogleConnector.SCOPE_GMAIL_MODIFY))
        val result = tools(withGoogle = true)
            .execute("list_calendar_events", args { put("source", "google") })

        assertTrue(!result.success)
        assertTrue(result.message.contains("Gmail only"))
        assertTrue(result.message.contains("Calendar"))
    }

    @Test
    fun `google source when not connected steers to Settings`() = runTest {
        val result = tools(withGoogle = true)
            .execute("list_calendar_events", args { put("source", "google") })
        assertTrue(!result.success)
        assertTrue(result.message.contains("not connected"))
    }

    @Test
    fun `creating a google event posts summary and times and returns a gcal id`() = runTest {
        connectGoogle(listOf(GoogleConnector.SCOPE_CALENDAR))
        googleServer.enqueue(MockResponse().setBody("""{"id":"new-1"}"""))
        val result = tools(withGoogle = true).execute(
            "create_calendar_event",
            args {
                put("source", "google")
                put("title", "Lunch")
                put("start", "2026-07-17 12:00")
            }
        )

        assertTrue(result.success)
        assertTrue(result.message.contains("gcal:new-1"))
        val body = googleServer.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"summary\":\"Lunch\""))
        assertTrue(body.contains("\"timeZone\":\"UTC\""))
        assertEquals(0, device.createCalls)
    }

    // ---- microsoft ----

    @Test
    fun `microsoft source lists events with ms-prefixed ids`() = runTest {
        connectMicrosoft()
        graphServer.enqueue(
            MockResponse().setBody(
                """{"value":[{"id":"m1","subject":"Review",
                   "start":{"dateTime":"2026-01-01T09:00:00.0000000"},
                   "end":{"dateTime":"2026-01-01T10:00:00.0000000"}}]}"""
            )
        )
        val result = tools(withMicrosoft = true)
            .execute("list_calendar_events", args { put("source", "microsoft") })

        assertTrue(result.success)
        assertTrue(result.message.contains("[ms:m1]"))
        assertTrue(result.message.contains("Review"))
    }

    @Test
    fun `microsoft source filters by search client-side`() = runTest {
        connectMicrosoft()
        graphServer.enqueue(
            MockResponse().setBody(
                """{"value":[{"id":"m1","subject":"Review"},{"id":"m2","subject":"Lunch"}]}"""
            )
        )
        val result = tools(withMicrosoft = true).execute(
            "list_calendar_events",
            args {
                put("source", "microsoft")
                put("search", "lunch")
            }
        )

        assertTrue(result.message.contains("[ms:m2]"))
        assertTrue(!result.message.contains("[ms:m1]"))
    }

    // ---- check_availability ----

    @Test
    fun `check_availability without any connector explains what is needed`() = runTest {
        val result = tools().execute("check_availability", args())
        assertTrue(!result.success)
        assertTrue(result.message.contains("connected calendar"))
        assertTrue(result.message.contains("list_calendar_events"))
    }

    @Test
    fun `check_availability reports google busy blocks and the gaps between them`() = runTest {
        connectGoogle(listOf(GoogleConnector.SCOPE_CALENDAR))
        googleServer.enqueue(
            MockResponse().setBody(
                """{"calendars":{"primary":{"busy":[
                   {"start":"2026-01-01T09:00:00Z","end":"2026-01-01T10:00:00Z"},
                   {"start":"2026-01-01T09:30:00Z","end":"2026-01-01T11:00:00Z"}]}}}"""
            )
        )
        val result = tools(withGoogle = true).execute(
            "check_availability",
            args {
                put("days_ahead", 1)
                put("duration_minutes", 60)
            }
        )

        assertTrue(result.success)
        assertTrue(result.message.contains("Google Calendar"))
        // The two overlapping blocks must be merged into one.
        assertTrue(result.message.contains("Busy (1)"))
        assertTrue(result.message.contains("Free slots"))
    }

    @Test
    fun `check_availability ignores a google connector without the calendar scope`() = runTest {
        connectGoogle(listOf(GoogleConnector.SCOPE_GMAIL_MODIFY))
        val result = tools(withGoogle = true).execute("check_availability", args())
        assertTrue(!result.success)
        assertTrue(result.message.contains("connected calendar"))
    }
}
