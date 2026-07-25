package com.gotcha.connectors.oauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * Transient loopback HTTP listener for the OAuth desktop-app redirect
 * (`http://127.0.0.1:{port}/...`). Binds an ephemeral port, accepts a single
 * connection, parses the query string, replies with a small success/error page,
 * and closes. Pure java.net — JVM-testable.
 */
class LoopbackRedirectServer(
    timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS
) : Closeable {

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5 * 60 * 1000
        private const val MAX_REQUEST_LINE_LENGTH = 8192
    }

    sealed class Result {
        data class Code(val code: String) : Result()
        data class Error(val message: String) : Result()
    }

    private val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).apply {
        soTimeout = timeoutMillis
    }

    val port: Int get() = serverSocket.localPort

    val redirectUri: String get() = "http://127.0.0.1:$port"

    /**
     * Blocks until one request arrives (or the timeout elapses) and returns the
     * outcome. [expectedState] is checked against the `state` query parameter to
     * reject forged redirects.
     */
    suspend fun awaitCode(expectedState: String): Result = withContext(Dispatchers.IO) {
        try {
            serverSocket.accept().use { socket ->
                val requestLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
                    .take(MAX_REQUEST_LINE_LENGTH)
                val params = parseQuery(requestLine)
                val result = when {
                    params["error"] != null ->
                        Result.Error("Authorization failed: ${params["error"]}")
                    params["state"] != expectedState ->
                        Result.Error("State mismatch — possible forged redirect. Try connecting again.")
                    params["code"].isNullOrBlank() ->
                        Result.Error("Redirect did not contain an authorization code.")
                    else -> Result.Code(params.getValue("code"))
                }
                val page = if (result is Result.Code) successHtml else errorHtml(result)
                socket.getOutputStream().apply {
                    write(httpResponse(page).toByteArray(Charsets.UTF_8))
                    flush()
                }
                result
            }
        } catch (ignored: SocketTimeoutException) {
            Result.Error("Timed out waiting for the sign-in redirect (5 minutes). Try connecting again.")
        } finally {
            close()
        }
    }

    override fun close() {
        runCatching { serverSocket.close() }
    }

    /** Extracts query parameters from an HTTP request line like `GET /?code=x&state=y HTTP/1.1`. */
    private fun parseQuery(requestLine: String): Map<String, String> {
        val path = requestLine.split(" ").getOrNull(1) ?: return emptyMap()
        val query = path.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
            val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            key to value
        }.toMap()
    }

    private fun httpResponse(html: String): String {
        val body = html.toByteArray(Charsets.UTF_8)
        return "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            html
    }

    private fun errorHtml(result: Result): String {
        val message = (result as? Result.Error)?.message ?: "Unknown error"
        return "<html><body style=\"font-family:sans-serif;text-align:center;padding-top:4em\">" +
            "<h2>Sign-in failed</h2><p>${escapeHtml(message)}</p>" +
            "<p>Return to the app and try again.</p></body></html>"
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private val successHtml =
        "<html><body style=\"font-family:sans-serif;text-align:center;padding-top:4em\">" +
            "<h2>Signed in</h2><p>You can close this tab and return to the app.</p></body></html>"
}
