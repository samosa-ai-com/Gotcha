package com.gotcha.connectors.microsoft

import com.gotcha.connectors.CredentialStore
import com.gotcha.connectors.mail.OutgoingEmail
import com.gotcha.connectors.oauth.OAuth2Helper
import com.gotcha.connectors.oauth.TokenSet
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

private class InMemoryCredentialStore : CredentialStore {
    private val map = mutableMapOf<String, String>()
    override fun loadRaw(connectorId: String): String? = map[connectorId]
    override fun saveRaw(connectorId: String, blob: String) { map[connectorId] = blob }
    override fun clear(connectorId: String) { map.remove(connectorId) }
}

class MicrosoftConnectorTest {

    private lateinit var authServer: MockWebServer
    private lateinit var graphServer: MockWebServer
    private lateinit var store: InMemoryCredentialStore
    private lateinit var connector: MicrosoftConnector

    @Before
    fun setUp() {
        authServer = MockWebServer()
        authServer.start()
        graphServer = MockWebServer()
        graphServer.start()
        store = InMemoryCredentialStore()
        connector = MicrosoftConnector(
            store = store,
            api = GraphApi(baseUrl = graphServer.url("/v1.0").toString().trimEnd('/')),
            oauth = OAuth2Helper(),
            authorityOverride = authServer.url("/oauth2/v2.0").toString().trimEnd('/')
        )
    }

    @After
    fun tearDown() {
        authServer.shutdown()
        graphServer.shutdown()
    }

    private suspend fun connect(accessToken: String = "at-1", expiresAt: Long = future()) {
        graphServer.enqueue(MockResponse().setBody("""{"mail":"user@outlook.com"}"""))
        connector.completeConnect(
            "client-id",
            MicrosoftConnector.DEFAULT_TENANT,
            TokenSet(accessToken, "rt-1", expiresAt)
        )
    }

    private fun future() = System.currentTimeMillis() + 3_600_000

    private fun past() = System.currentTimeMillis() - 1_000

    @Test
    fun `oauth config is a public client with no secret`() {
        val cfg = connector.oauthConfig("client-id")
        assertEquals(null, cfg.clientSecret)
        assertTrue(cfg.scopes.contains("offline_access"))
        assertTrue(cfg.scopes.contains(MicrosoftConnector.SCOPE_MAIL))
        assertTrue(cfg.scopes.contains(MicrosoftConnector.SCOPE_CALENDAR))
        assertTrue(cfg.scopes.contains(MicrosoftConnector.SCOPE_TASKS))
    }

    @Test
    fun `completeConnect identifies account and persists credentials`() = runTest {
        connect()
        assertTrue(connector.isConnected())
        assertEquals("user@outlook.com", connector.credentials()?.accountEmail)
        assertTrue(store.loadRaw("microsoft")?.contains("user@outlook.com") == true)
    }

    @Test
    fun `completeConnect without refresh token throws a steering error`() = runTest {
        try {
            connector.completeConnect("client-id", "common", TokenSet("at-1", null, future()))
            throw AssertionError("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("offline_access"))
        }
    }

    @Test
    fun `expired access token triggers proactive refresh before the graph call`() = runTest {
        connect(accessToken = "stale", expiresAt = past())
        authServer.enqueue(MockResponse().setBody("""{"access_token":"fresh","expires_in":3600}"""))
        graphServer.enqueue(MockResponse().setBody("""{"value":[]}"""))

        assertTrue(connector.list(null, false, 10).isEmpty())

        graphServer.takeRequest() // the /me call from completeConnect
        assertEquals("Bearer fresh", graphServer.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `refresh posts no client_secret for the public client`() = runTest {
        connect(accessToken = "stale", expiresAt = past())
        authServer.enqueue(MockResponse().setBody("""{"access_token":"fresh","expires_in":3600}"""))
        graphServer.enqueue(MockResponse().setBody("""{"value":[]}"""))
        connector.list(null, false, 10)

        val body = URLDecoder.decode(authServer.takeRequest().body.readUtf8(), "UTF-8")
        assertTrue(body.contains("grant_type=refresh_token"))
        assertTrue("public clients must not send a secret", !body.contains("client_secret"))
    }

    @Test
    fun `invalid_grant on refresh flips needsReconnect`() = runTest {
        connect(accessToken = "stale", expiresAt = past())
        authServer.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
        )

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
    fun `list maps graph messages to the shared summary model`() = runTest {
        connect()
        graphServer.enqueue(
            MockResponse().setBody(
                """{"value":[{"id":"AAA","subject":"Hello","isRead":false,
                   "receivedDateTime":"2026-02-01T10:00:00Z",
                   "from":{"emailAddress":{"name":"Ann","address":"ann@x.com"}},
                   "bodyPreview":"first line"}]}"""
            )
        )
        val rows = connector.list(null, false, 10)

        assertEquals(1, rows.size)
        assertEquals("ms:AAA", rows[0].id)
        assertEquals("Ann <ann@x.com>", rows[0].from)
        assertTrue(rows[0].unread)
    }

    @Test
    fun `unread filtering happens client-side when a text query is used`() = runTest {
        connect()
        graphServer.enqueue(
            MockResponse().setBody(
                """{"value":[
                   {"id":"A","subject":"a","isRead":true,"bodyPreview":""},
                   {"id":"B","subject":"b","isRead":false,"bodyPreview":""}]}"""
            )
        )
        val rows = connector.list("report", unreadOnly = true, max = 10)

        assertEquals(1, rows.size)
        assertEquals("ms:B", rows[0].id)
    }

    @Test
    fun `send builds a graph message payload`() = runTest {
        connect()
        graphServer.enqueue(MockResponse().setResponseCode(202))
        val result = connector.send(
            OutgoingEmail(to = listOf("bob@x.com"), subject = "Subj", body = "Body")
        )

        assertTrue(result.contains("bob@x.com"))
        graphServer.takeRequest() // /me
        val body = graphServer.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"address\":\"bob@x.com\""))
        assertTrue(body.contains("\"subject\":\"Subj\""))
    }

    @Test
    fun `disconnect clears credentials`() = runTest {
        connect()
        connector.disconnect()
        assertTrue(!connector.isConnected())
        assertEquals(null, connector.credentials())
        assertEquals(null, store.loadRaw("microsoft"))
    }
}
