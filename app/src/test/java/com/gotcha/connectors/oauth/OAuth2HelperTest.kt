package com.gotcha.connectors.oauth

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

class OAuth2HelperTest {

    private lateinit var server: MockWebServer
    private lateinit var helper: OAuth2Helper
    private lateinit var cfg: OAuth2Config

    private val fixedNow = 1_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        helper = OAuth2Helper(clock = { fixedNow })
        cfg = OAuth2Config(
            authUrl = server.url("/auth").toString(),
            tokenUrl = server.url("/token").toString(),
            clientId = "client-id",
            clientSecret = "client-secret",
            scopes = listOf("scope.a", "scope.b"),
            extraAuthParams = mapOf("access_type" to "offline", "prompt" to "consent")
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `authorization url carries all parameters`() {
        val url = helper.buildAuthorizationUrl(cfg, "http://127.0.0.1:8080", "st4te", "chall3nge")
        val decoded = URLDecoder.decode(url, "UTF-8")
        assertTrue(decoded.contains("response_type=code"))
        assertTrue(decoded.contains("client_id=client-id"))
        assertTrue(decoded.contains("redirect_uri=http://127.0.0.1:8080"))
        assertTrue(decoded.contains("scope=scope.a scope.b"))
        assertTrue(decoded.contains("state=st4te"))
        assertTrue(decoded.contains("code_challenge=chall3nge"))
        assertTrue(decoded.contains("code_challenge_method=S256"))
        assertTrue(decoded.contains("access_type=offline"))
        assertTrue(decoded.contains("prompt=consent"))
    }

    @Test
    fun `exchangeCode posts form and parses token set`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"at-1","refresh_token":"rt-1","expires_in":3599}"""
            )
        )
        val tokens = helper.exchangeCode(cfg, "the-code", "http://127.0.0.1:8080", "verif13r")

        assertEquals("at-1", tokens.accessToken)
        assertEquals("rt-1", tokens.refreshToken)
        assertEquals(fixedNow + 3599 * 1000, tokens.expiresAtMillis)

        val body = URLDecoder.decode(server.takeRequest().body.readUtf8(), "UTF-8")
        assertTrue(body.contains("grant_type=authorization_code"))
        assertTrue(body.contains("code=the-code"))
        assertTrue(body.contains("code_verifier=verif13r"))
        assertTrue(body.contains("client_secret=client-secret"))
    }

    @Test
    fun `refresh keeps old refresh token when provider omits it`() = runTest {
        server.enqueue(MockResponse().setBody("""{"access_token":"at-2","expires_in":3600}"""))
        val tokens = helper.refresh(cfg, "rt-old")

        assertEquals("at-2", tokens.accessToken)
        assertEquals("rt-old", tokens.refreshToken)
        val body = URLDecoder.decode(server.takeRequest().body.readUtf8(), "UTF-8")
        assertTrue(body.contains("grant_type=refresh_token"))
        assertTrue(body.contains("refresh_token=rt-old"))
    }

    @Test(expected = OAuthInvalidGrant::class)
    fun `invalid_grant throws typed exception`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        helper.refresh(cfg, "rt-revoked")
    }

    @Test
    fun `other token errors carry status code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        try {
            helper.refresh(cfg, "rt")
            throw AssertionError("expected OAuthTokenError")
        } catch (e: OAuthTokenError) {
            assertEquals(500, e.code)
        }
    }
}
