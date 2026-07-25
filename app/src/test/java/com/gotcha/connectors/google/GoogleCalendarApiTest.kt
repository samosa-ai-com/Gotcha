package com.gotcha.connectors.google

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

class GoogleCalendarApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: GoogleCalendarApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = GoogleCalendarApi(baseUrl = server.url("/calendar/v3").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `listEvents expands recurring events and orders by start`() = runTest {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"e1","summary":"Standup"}]}"""))
        val events = api.listEvents(
            "tok",
            "primary",
            "2026-01-01T00:00:00Z",
            "2026-01-08T00:00:00Z",
            query = null,
            max = 50
        )

        assertEquals(1, events.size)
        assertEquals("Standup", events[0].jsonObject["summary"]!!.jsonPrimitive.content)

        val request = server.takeRequest()
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        val path = URLDecoder.decode(request.path.orEmpty(), "UTF-8")
        assertTrue(path.contains("/calendars/primary/events"))
        assertTrue("recurrences must be expanded", path.contains("singleEvents=true"))
        assertTrue(path.contains("orderBy=startTime"))
        assertTrue(path.contains("timeMin=2026-01-01T00:00:00Z"))
        assertTrue(!path.contains("&q="))
    }

    @Test
    fun `listEvents forwards a text query`() = runTest {
        server.enqueue(MockResponse().setBody("""{"items":[]}"""))
        api.listEvents("tok", "primary", "a", "b", query = "dentist", max = 10)
        assertTrue(URLDecoder.decode(server.takeRequest().path.orEmpty(), "UTF-8").contains("q=dentist"))
    }

    @Test
    fun `missing items yields an empty list rather than throwing`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(api.listEvents("tok", "primary", "a", "b", null, 10).isEmpty())
    }

    @Test
    fun `insertEvent posts the payload and returns the new id`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"new-1"}"""))
        val id = api.insertEvent(
            "tok",
            "primary",
            buildJsonObject { put("summary", JsonPrimitive("Lunch")) }
        )

        assertEquals("new-1", id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.body.readUtf8().contains("\"summary\":\"Lunch\""))
    }

    @Test
    fun `freeBusy posts the window and returns the calendars map`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"calendars":{"primary":{"busy":[
                   {"start":"2026-01-01T09:00:00Z","end":"2026-01-01T10:00:00Z"}]}}}"""
            )
        )
        val calendars = api.freeBusy(
            "tok",
            listOf("primary"),
            "2026-01-01T00:00:00Z",
            "2026-01-02T00:00:00Z"
        )

        val busy = calendars["primary"]!!.jsonObject["busy"]!!.jsonArray
        assertEquals(1, busy.size)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"timeMin\":\"2026-01-01T00:00:00Z\""))
        assertTrue(body.contains("\"id\":\"primary\""))
    }

    @Test
    fun `403 steers the user to reconnect with the calendar scope`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody("""{"error":{"message":"insufficient scope"}}""")
        )
        try {
            api.calendarList("tok")
            throw AssertionError("expected GmailApiException")
        } catch (e: GmailApiException) {
            assertEquals(403, e.code)
            assertTrue(e.message!!.contains("Calendar"))
            assertTrue(e.message!!.contains("Reconnect"))
        }
    }

    @Test
    fun `401 is surfaced with its code so the connector can refresh and retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        try {
            api.calendarList("tok")
            throw AssertionError("expected GmailApiException")
        } catch (e: GmailApiException) {
            assertEquals(401, e.code)
        }
    }
}
