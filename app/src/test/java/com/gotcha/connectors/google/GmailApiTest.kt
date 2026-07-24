package com.gotcha.connectors.google

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GmailApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: GmailApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = GmailApi(baseUrl = server.url("/gmail/v1/users/me").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `listMessageIds sends bearer token and query`() = runTest {
        server.enqueue(MockResponse().setBody("""{"messages":[{"id":"m1"},{"id":"m2"}]}"""))
        val ids = api.listMessageIds("tok-1", "is:unread", unreadOnly = true, max = 10)
        assertEquals(listOf("m1", "m2"), ids)

        val request = server.takeRequest()
        assertEquals("Bearer tok-1", request.getHeader("Authorization"))
        assertTrue(request.path?.contains("q=is%3Aunread") == true || request.path?.contains("is:unread") == true)
    }

    @Test
    fun `listMessageIds handles empty result`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        val ids = api.listMessageIds("tok", null, unreadOnly = false, max = 10)
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `retries once on 401 is caller responsibility - api surfaces GmailApiException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid token"}}"""))
        try {
            api.profileEmail("bad-token")
            throw AssertionError("expected GmailApiException")
        } catch (e: GmailApiException) {
            assertEquals(401, e.code)
        }
    }

    @Test
    fun `403 rate limit mapped to actionable message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"quota exceeded"}}"""))
        try {
            api.profileEmail("tok")
            throw AssertionError("expected GmailApiException")
        } catch (e: GmailApiException) {
            assertEquals(403, e.code)
            assertTrue(e.message!!.contains("try again later", ignoreCase = true))
        }
    }

    @Test
    fun `sendRaw posts raw payload and returns id`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"sent-1"}"""))
        val id = api.sendRaw("tok", "base64url-content")
        assertEquals("sent-1", id)
        val request = server.takeRequest()
        assertTrue(request.body.readUtf8().contains("base64url-content"))
    }

    @Test
    fun `profileEmail parses emailAddress`() = runTest {
        server.enqueue(MockResponse().setBody("""{"emailAddress":"user@gmail.com"}"""))
        assertEquals("user@gmail.com", api.profileEmail("tok"))
    }
}
