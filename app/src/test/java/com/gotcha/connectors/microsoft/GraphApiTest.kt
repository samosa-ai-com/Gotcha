package com.gotcha.connectors.microsoft

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

class GraphApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: GraphApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = GraphApi(baseUrl = server.url("/v1.0").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Takes the next request, asserts it carried the Bearer token, returns its decoded path. */
    private fun decodedPath(): String {
        val request = server.takeRequest()
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        return URLDecoder.decode(request.path.orEmpty(), "UTF-8")
    }

    @Test
    fun `me prefers mail over userPrincipalName`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"mail":"a@b.com","userPrincipalName":"a_b.com#EXT#@x"}""")
        )
        assertEquals("a@b.com", api.me("tok"))
        assertTrue(decodedPath().contains("\$select=mail,userPrincipalName,displayName"))
    }

    @Test
    fun `me falls back to userPrincipalName`() = runTest {
        server.enqueue(MockResponse().setBody("""{"userPrincipalName":"work@corp.com"}"""))
        assertEquals("work@corp.com", api.me("tok"))
    }

    @Test
    fun `listMessages without query orders by date and can filter unread`() = runTest {
        server.enqueue(MockResponse().setBody("""{"value":[{"id":"1"}]}"""))
        val rows = api.listMessages("tok", query = null, unreadOnly = true, max = 5)

        assertEquals(1, rows.size)
        val path = decodedPath()
        assertTrue(path.contains("\$orderby=receivedDateTime desc"))
        assertTrue(path.contains("\$filter=isRead eq false"))
        assertTrue(path.contains("\$top=5"))
        assertTrue("search must not be combined with filter", !path.contains("\$search"))
    }

    @Test
    fun `listMessages with query switches to search and drops orderby and filter`() = runTest {
        server.enqueue(MockResponse().setBody("""{"value":[]}"""))
        api.listMessages("tok", query = "invoice", unreadOnly = true, max = 5)

        val path = decodedPath()
        assertTrue(path.contains("\$search=\"invoice\""))
        assertTrue("Graph rejects \$search with \$orderby", !path.contains("\$orderby"))
        assertTrue("Graph rejects \$search with \$filter", !path.contains("\$filter"))
    }

    @Test
    fun `sendMail wraps the message and saves to sent items`() = runTest {
        server.enqueue(MockResponse().setResponseCode(202))
        api.sendMail("tok", buildJsonObject { put("subject", JsonPrimitive("Hi")) })

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("Hi", body["message"]!!.jsonObject["subject"]!!.jsonPrimitive.content)
        assertEquals("true", body["saveToSentItems"]!!.jsonPrimitive.content)
    }

    @Test
    fun `setRead patches the isRead flag`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        api.setRead("tok", "msg-1", read = true)

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertTrue(request.path!!.endsWith("/me/messages/msg-1"))
        assertTrue(request.body.readUtf8().contains("\"isRead\":true"))
    }

    @Test
    fun `calendarView requests UTC and orders by start`() = runTest {
        server.enqueue(MockResponse().setBody("""{"value":[]}"""))
        api.calendarView("tok", "2026-01-01T00:00:00Z", "2026-01-08T00:00:00Z", 20)

        val request = server.takeRequest()
        assertEquals("outlook.timezone=\"UTC\"", request.getHeader("Prefer"))
        val path = URLDecoder.decode(request.path.orEmpty(), "UTF-8")
        assertTrue(path.contains("startDateTime=2026-01-01T00:00:00Z"))
        assertTrue(path.contains("\$orderby=start/dateTime"))
    }

    @Test
    fun `getSchedule posts the account and window`() = runTest {
        server.enqueue(MockResponse().setBody("""{"value":[{"scheduleId":"a@b.com"}]}"""))
        val rows = api.getSchedule(
            "tok",
            listOf("a@b.com"),
            "2026-01-01T09:00:00Z",
            "2026-01-01T17:00:00Z",
            30
        )

        assertEquals(1, rows.size)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"a@b.com\""))
        assertTrue("Z suffix must be dropped when timeZone is explicit", body.contains("2026-01-01T09:00:00\""))
        assertTrue(body.contains("\"availabilityViewInterval\":30"))
    }

    @Test
    fun `todoTasks hides completed items by default`() = runTest {
        server.enqueue(MockResponse().setBody("""{"value":[]}"""))
        api.todoTasks("tok", "list-1", includeCompleted = false, max = 25)

        val path = decodedPath()
        assertTrue(path.contains("/me/todo/lists/list-1/tasks"))
        assertTrue(path.contains("\$filter=status ne 'completed'"))
    }

    @Test
    fun `todoTasks includes completed items when asked`() = runTest {
        server.enqueue(MockResponse().setBody("""{"value":[]}"""))
        api.todoTasks("tok", "list-1", includeCompleted = true, max = 25)
        assertTrue(!decodedPath().contains("\$filter"))
    }

    @Test
    fun `401 maps to an auth-expired exception so the connector can retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"expired"}}"""))
        try {
            api.me("tok")
            throw AssertionError("expected GraphApiException")
        } catch (e: GraphApiException) {
            assertEquals(401, e.code)
            assertTrue(e.message!!.contains("401"))
        }
    }

    @Test
    fun `429 maps to a throttling message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"slow down"}}"""))
        try {
            api.me("tok")
            throw AssertionError("expected GraphApiException")
        } catch (e: GraphApiException) {
            assertEquals(429, e.code)
            assertTrue(e.message!!.contains("throttling"))
        }
    }

    @Test
    fun `403 steers the user to reconnect`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"no scope"}}"""))
        try {
            api.me("tok")
            throw AssertionError("expected GraphApiException")
        } catch (e: GraphApiException) {
            assertTrue(e.message!!.contains("Reconnect"))
        }
    }
}
