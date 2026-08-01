package com.gotcha.notifications

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotificationApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: NotificationApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = NotificationApi(
            baseUrl = server.url("/").toString().trimEnd('/'),
            bearerToken = ""
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetch returns parsed envelope with etag from header`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("ETag", "\"abc123\"")
                .setBody(
                    """
                    {
                      "messages": [
                        {"id": "m1", "title": "T", "body": "B"}
                      ],
                      "etag": "abc123"
                    }
                    """.trimIndent()
                )
        )

        val result = api.fetchBlocking(ifNoneMatch = null)
        assertTrue(result is NotificationsApiResult.Parsed)
        val parsed = (result as NotificationsApiResult.Parsed).envelope
        assertEquals(1, parsed.messages.size)
        assertEquals("m1", parsed.messages[0].id)
        assertEquals("abc123", parsed.etag)
    }

    @Test
    fun `fetch returns NotModified on 304`() {
        server.enqueue(MockResponse().setResponseCode(304))
        val result = api.fetchBlocking(ifNoneMatch = "abc123")
        assertEquals(NotificationsApiResult.NotModified, result)
    }

    @Test
    fun `fetch sends If-None-Match when supplied`() {
        server.enqueue(MockResponse().setResponseCode(304))
        api.fetchBlocking(ifNoneMatch = "abc123")
        val recorded = server.takeRequest()
        assertEquals("\"abc123\"", recorded.getHeader("If-None-Match"))
    }

    @Test
    fun `fetch fires onUnauthorized on 401`() {
        var fired = 0
        val authed = NotificationApi(
            baseUrl = server.url("/").toString().trimEnd('/'),
            bearerToken = "bearer",
            onUnauthorized = { fired++ }
        )
        server.enqueue(MockResponse().setResponseCode(401))
        val result = authed.fetchBlocking(ifNoneMatch = null)
        assertEquals(1, fired)
        assertEquals(NotificationsApiResult.Error(401), result)
    }

    @Test
    fun `fetch attaches Bearer header when token set`() {
        val authed = NotificationApi(
            baseUrl = server.url("/").toString().trimEnd('/'),
            bearerToken = "the-token"
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"messages":[],"etag":""}""")
        )
        authed.fetchBlocking(ifNoneMatch = null)
        val recorded = server.takeRequest()
        assertEquals("Bearer the-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `fetch returns NetworkError on connection failure`() {
        val dead = NotificationApi(
            baseUrl = "http://127.0.0.1:1/" // unreachable
        )
        val result = dead.fetchBlocking(ifNoneMatch = null)
        assertEquals(NotificationsApiResult.NetworkError, result)
    }

    @Test
    fun `malformed message entries are skipped without sinking the parse`() {
        // id is the only required field on NotificationMessage; the first
        // entry is missing it and the third is missing title — both should be
        // dropped, while the second good one survives.
        val body = """
            {
              "messages": [
                {"title": "no id"},
                {"id": "good", "title": "T", "body": "B"},
                {"id": "no-title", "body": "B"}
              ],
              "etag": "etag-1"
            }
        """.trimIndent()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("ETag", "\"etag-1\"")
                .setBody(body)
        )

        val result = api.fetchBlocking(ifNoneMatch = null)
        assertTrue(result is NotificationsApiResult.Parsed)
        val envelope = (result as NotificationsApiResult.Parsed).envelope
        assertEquals(1, envelope.messages.size)
        assertEquals("good", envelope.messages[0].id)
        assertEquals("etag-1", envelope.etag)
    }
}
