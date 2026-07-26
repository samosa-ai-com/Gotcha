package com.gotcha.connectors.google

import com.gotcha.connectors.CredentialStore
import com.gotcha.connectors.oauth.OAuth2Helper
import kotlinx.coroutines.test.runTest
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

class GoogleConnectorTest {

    private lateinit var tokenServer: MockWebServer
    private lateinit var gmailServer: MockWebServer
    private lateinit var store: InMemoryCredentialStore
    private lateinit var connector: GoogleConnector

    @Before
    fun setUp() {
        tokenServer = MockWebServer()
        tokenServer.start()
        gmailServer = MockWebServer()
        gmailServer.start()
        store = InMemoryCredentialStore()
        connector = GoogleConnector(
            store = store,
            api = GmailApi(baseUrl = gmailServer.url("/gmail/v1/users/me").toString()),
            oauth = OAuth2Helper(),
            tokenUrl = tokenServer.url("/token").toString()
        )
    }

    @After
    fun tearDown() {
        tokenServer.shutdown()
        gmailServer.shutdown()
    }

    @Test
    fun `completeConnect identifies account and persists credentials`() = runTest {
        gmailServer.enqueue(MockResponse().setBody("""{"emailAddress":"user@gmail.com"}"""))
        connector.completeConnect(
            "client-id",
            "client-secret",
            com.gotcha.connectors.oauth.TokenSet("at-1", "rt-1", System.currentTimeMillis() + 3600_000)
        )
        assertTrue(connector.isConnected())
        assertEquals("user@gmail.com", connector.credentials()?.accountEmail)
        assertTrue(store.loadRaw("google")?.contains("user@gmail.com") == true)
    }

    @Test
    fun `completeConnect without refresh token throws`() = runTest {
        try {
            connector.completeConnect(
                "client-id",
                "client-secret",
                com.gotcha.connectors.oauth.TokenSet("at-1", null, System.currentTimeMillis() + 3600_000)
            )
            throw AssertionError("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("refresh token"))
        }
    }

    @Test
    fun `expired access token triggers proactive refresh before gmail call`() = runTest {
        gmailServer.enqueue(MockResponse().setBody("""{"emailAddress":"user@gmail.com"}"""))
        connector.completeConnect(
            "client-id",
            "client-secret",
            com.gotcha.connectors.oauth.TokenSet("stale-token", "rt-1", System.currentTimeMillis() - 1000)
        )
        tokenServer.enqueue(MockResponse().setBody("""{"access_token":"fresh-token","expires_in":3600}"""))
        gmailServer.enqueue(MockResponse().setBody("""{"messages":[]}"""))

        val emails = connector.list(null, false, 10)

        assertTrue(emails.isEmpty())
        gmailServer.takeRequest() // profile call from completeConnect
        val listRequest = gmailServer.takeRequest()
        assertEquals("Bearer fresh-token", listRequest.getHeader("Authorization"))
    }

    @Test
    fun `invalid_grant on refresh flips needsReconnect`() = runTest {
        gmailServer.enqueue(MockResponse().setBody("""{"emailAddress":"user@gmail.com"}"""))
        connector.completeConnect(
            "client-id",
            "client-secret",
            com.gotcha.connectors.oauth.TokenSet("stale-token", "rt-1", System.currentTimeMillis() - 1000)
        )
        tokenServer.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))

        try {
            connector.list(null, false, 10)
            throw AssertionError("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("reconnect"))
        }
        assertTrue(connector.needsReconnect())
        assertTrue(!connector.isConnected())
    }

    @Test
    fun `disconnect clears credentials`() = runTest {
        gmailServer.enqueue(MockResponse().setBody("""{"emailAddress":"user@gmail.com"}"""))
        connector.completeConnect(
            "client-id",
            "client-secret",
            com.gotcha.connectors.oauth.TokenSet("at-1", "rt-1", System.currentTimeMillis() + 3600_000)
        )
        connector.disconnect()
        assertTrue(!connector.isConnected())
        assertEquals(null, connector.credentials())
        assertEquals(null, store.loadRaw("google"))
    }
}
