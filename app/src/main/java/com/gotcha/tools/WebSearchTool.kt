package com.gotcha.tools

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * Web search tool — performs a search via DuckDuckGo's HTML endpoint.
 * No API key required. Returns clean numbered results with title, URL, and snippet.
 */
class WebSearchTool {

    companion object {
        private const val TIMEOUT_SECONDS = 15L
        private const val DUCKDUCKGO_HTML = "https://html.duckduckgo.com/html/"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun search(query: String, numResults: Int): ToolResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return ToolResult.error(
                "Search query cannot be empty. Provide keywords or a question to search for."
            )
        }
        val count = numResults.coerceIn(1, 10)

        return try {
            val request = Request.Builder()
                .url("$DUCKDUCKGO_HTML?q=${java.net.URLEncoder.encode(trimmed, "UTF-8")}")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/125.0.6422.165 Mobile Safari/537.36"
                )
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body ?: return ToolResult.error(
                "Empty response (HTTP ${response.code}). The search engine may be " +
                    "temporarily unavailable."
            )

            if (!response.isSuccessful) {
                return ToolResult.error(
                    "Search failed (HTTP ${response.code}). The search engine may be rate-limiting or temporarily down."
                )
            }

            val html = body.string()
            val doc = Jsoup.parse(html)
            val resultRows = doc.select(".result")

            if (resultRows.isEmpty()) {
                // DDG sometimes puts ads-only on first page; check for "no results" indicator
                return ToolResult.ok(
                    "No search results found for '$trimmed'. You may try different keywords or rephrase the query."
                )
            }

            val results = mutableListOf<String>()
            var rank = 0

            for (row in resultRows) {
                if (results.size >= count) break

                val titleEl = row.selectFirst(".result__title a")
                val snippetEl = row.selectFirst(".result__snippet")

                val title = titleEl?.text()?.trim() ?: continue
                val href = titleEl.attr("href")
                // DDG wraps real URLs in redirect — extract from the 'uddg' parameter or use the href directly
                val url = extractUrl(href)
                val snippet = snippetEl?.text()?.trim() ?: ""

                if (url.isNotEmpty()) {
                    rank++
                    results.add("$rank. $title\n   $url")
                    if (snippet.isNotEmpty()) {
                        results.add("   $snippet")
                    }
                }
            }

            if (results.isEmpty()) {
                return ToolResult.ok(
                    "Found search results page but could not extract any results for '$trimmed'. You may try a different query " +
                        "or use webfetch on a known URL."
                )
            }

            ToolResult.ok("Search results for '$trimmed' (top ${results.size}):\n\n${results.joinToString("\n")}")
        } catch (e: java.net.UnknownHostException) {
            ToolResult.error(
                "Could not reach the search engine: ${e.message}. You may check your internet connection or try webfetch on a known URL."
            )
        } catch (_: java.net.SocketTimeoutException) {
            ToolResult.error(
                "Search timed out after ${TIMEOUT_SECONDS}s. The search engine may be slow — you may try a simpler query or " +
                    "use webfetch on a known URL."
            )
        } catch (e: Exception) {
            ToolResult.error("Search failed: ${e.message}")
        }
    }

    /** Extract the real URL from a DDG result href. DDG wraps links in redirects. */
    private fun extractUrl(href: String): String {
        // DDG redirect links look like: //duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2F...
        if (href.contains("uddg=")) {
            val uddg = href.substringAfter("uddg=").substringBefore("&")
            return java.net.URLDecoder.decode(uddg, "UTF-8")
        }
        // Some results have direct hrefs
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        if (href.startsWith("//")) return "https:$href"
        return ""
    }
}
