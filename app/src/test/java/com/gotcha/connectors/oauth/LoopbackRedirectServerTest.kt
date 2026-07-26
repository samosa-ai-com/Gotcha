package com.gotcha.connectors.oauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class LoopbackRedirectServerTest {

    private fun hit(url: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val code = conn.responseCode
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return code to body
    }

    @Test
    fun `round trip returns code and success page`() = runBlocking {
        val server = LoopbackRedirectServer()
        val result = async { server.awaitCode("expected-state") }
        val (status, body) = withContext(Dispatchers.IO) {
            hit("${server.redirectUri}/?code=auth-c0de&state=expected-state")
        }
        assertEquals(200, status)
        assertTrue(body.contains("Signed in"))
        assertEquals(LoopbackRedirectServer.Result.Code("auth-c0de"), result.await())
    }

    @Test
    fun `state mismatch rejected`() = runBlocking {
        val server = LoopbackRedirectServer()
        val result = async { server.awaitCode("expected-state") }
        withContext(Dispatchers.IO) {
            hit("${server.redirectUri}/?code=auth-c0de&state=evil")
        }
        assertTrue(result.await() is LoopbackRedirectServer.Result.Error)
    }

    @Test
    fun `provider error propagated`() = runBlocking {
        val server = LoopbackRedirectServer()
        val result = async { server.awaitCode("s") }
        withContext(Dispatchers.IO) {
            hit("${server.redirectUri}/?error=access_denied&state=s")
        }
        val error = result.await() as LoopbackRedirectServer.Result.Error
        assertTrue(error.message.contains("access_denied"))
    }

    @Test
    fun `timeout produces error`() = runBlocking {
        val server = LoopbackRedirectServer(timeoutMillis = 200)
        val result = server.awaitCode("s")
        assertTrue(result is LoopbackRedirectServer.Result.Error)
        assertTrue((result as LoopbackRedirectServer.Result.Error).message.contains("Timed out"))
    }
}
