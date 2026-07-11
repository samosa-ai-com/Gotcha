package com.gotcha.tools

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Web content fetching tool — fetches a URL and returns the content as text/markdown.
 * Uses OkHttp (already a project dependency).
 */
class WebFetchTool {

    companion object {
        private const val MAX_RESPONSE_BYTES = 5 * 1024 * 1024
        private const val TIMEOUT_SECONDS = 30L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun fetch(url: String, format: String?): ToolResult {
        val trimmed = url.trim()

        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return ToolResult.error("Only http:// and https:// URLs are supported.")
        }

        return try {
            val request = Request.Builder()
                .url(trimmed)
                .header("User-Agent", "Gotcha/0.1.0")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body ?: return ToolResult.error("Empty response (HTTP ${response.code}).")

            if (!response.isSuccessful) {
                return ToolResult.error("HTTP ${response.code} ${response.message}")
            }

            val contentType = body.contentType()
            val mime = contentType?.type?.lowercase() + "/" + contentType?.subtype?.lowercase()

            // Reject binary content
            if (!mime.startsWith("text/") && mime != "application/json" && mime != "application/xml") {
                return ToolResult.error("Unsupported content type: $mime. Only text responses are supported.")
            }

            val source = body.source()
            val buffer = okio.Buffer()
            source.read(buffer, MAX_RESPONSE_BYTES.toLong())
            source.close()

            val raw = buffer.readString(Charsets.UTF_8)
            val truncated = raw.length > MAX_RESPONSE_BYTES

            val output = if (truncated) {
                raw.take(MAX_RESPONSE_BYTES) + "\n…(truncated at ${MAX_RESPONSE_BYTES / 1024} KB)"
            } else raw

            ToolResult.ok(
                "Fetched ${trimmed} (${raw.length / 1024} KB, HTTP ${response.code}):\n\n$output"
            )
        } catch (e: java.net.UnknownHostException) {
            ToolResult.error("Could not resolve host: ${e.message}")
        } catch (e: java.net.SocketTimeoutException) {
            ToolResult.error("Request timed out after ${TIMEOUT_SECONDS}s.")
        } catch (e: Exception) {
            ToolResult.error("Failed to fetch URL: ${e.message}")
        }
    }
}
