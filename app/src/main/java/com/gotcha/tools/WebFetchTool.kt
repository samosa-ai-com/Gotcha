package com.gotcha.tools

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/**
 * Web content fetching tool — fetches a URL and returns the content as clean text or markdown.
 * Uses OkHttp for the request and Jsoup for HTML-to-text / HTML-to-markdown conversion.
 */
class WebFetchTool {

    companion object {
        private const val MAX_RESPONSE_BYTES = 5 * 1024 * 1024
        private const val TIMEOUT_SECONDS = 30L

        private val HTML_MIMES = setOf("text/html", "application/xhtml+xml")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun fetch(url: String, format: String?): ToolResult {
        val trimmed = url.trim()
        val outputFormat = (format?.trim()?.lowercase() ?: "text").let {
            if (it in setOf("text", "markdown")) it else "text"
        }

        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return ToolResult.error("Only http:// and https:// URLs are supported.")
        }

        return try {
            val request = Request.Builder()
                .url(trimmed)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.165 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body ?: return ToolResult.error("Empty response (HTTP ${response.code}).")

            if (!response.isSuccessful) {
                return ToolResult.error("HTTP ${response.code} ${response.message}")
            }

            val contentType = body.contentType()
            val mime = if (contentType != null) {
                "${contentType.type?.lowercase() ?: "text"}/${contentType.subtype?.lowercase() ?: "html"}"
            } else "text/html"

            val accepted = mime.startsWith("text/") ||
                mime in listOf("application/json", "application/xml", "application/xhtml+xml")
            if (!accepted) {
                return ToolResult.error("Unsupported content type: $mime. Only text responses are supported.")
            }

            val raw = body.string()
            val rawSizeKb = raw.length / 1024

            val output: String = if (mime in HTML_MIMES) {
                val doc = Jsoup.parse(raw, trimmed)
                doc.select("script, style, noscript, iframe, object, embed, svg, nav, footer, header").remove()

                when (outputFormat) {
                    "markdown" -> htmlToMarkdown(doc)
                    else -> doc.body()?.text()?.trim() ?: "(empty page)"
                }
            } else {
                when (outputFormat) {
                    "markdown" -> raw
                    else -> raw
                }
            }

            val truncated = output.length > MAX_RESPONSE_BYTES
            val finalOutput = if (truncated) {
                output.take(MAX_RESPONSE_BYTES) + "\n…(truncated at ${MAX_RESPONSE_BYTES / 1024} KB)"
            } else output

            ToolResult.ok(
                "Fetched ${trimmed} (${rawSizeKb} KB raw → ${finalOutput.length / 1024} KB ${outputFormat}, HTTP ${response.code}):\n\n$finalOutput"
            )
        } catch (e: java.net.UnknownHostException) {
            ToolResult.error("Could not resolve host: ${e.message}")
        } catch (e: java.net.SocketTimeoutException) {
            ToolResult.error("Request timed out after ${TIMEOUT_SECONDS}s.")
        } catch (e: Exception) {
            ToolResult.error("Failed to fetch URL: ${e.message}")
        }
    }

    private fun htmlToMarkdown(doc: Document): String {
        val sb = StringBuilder()
        val body = doc.body() ?: return doc.text()

        fun process(node: org.jsoup.nodes.Node, listIndent: String) {
            fun children() {
                for (child in node.childNodes()) process(child, listIndent)
            }

            when (node.nodeName()) {
                "#text" -> {
                    val text = (node as org.jsoup.nodes.TextNode).wholeText
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    if (text.isNotEmpty()) sb.append(text)
                }
                "br" -> sb.append("\n")
                "p" -> {
                    val prev = sb.toString().trimEnd()
                    if (prev.isNotEmpty() && !prev.endsWith("\n")) sb.append("\n")
                    children()
                    sb.append("\n\n")
                }
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    val level = node.nodeName().last().digitToInt()
                    sb.append("\n${"#".repeat(level)} ")
                    children()
                    sb.append("\n\n")
                }
                "a" -> {
                    val el = node as org.jsoup.nodes.Element
                    val href = el.absUrl("href")
                    val text = el.text().trim()
                    if (text.isNotEmpty() && href.isNotEmpty()) {
                        sb.append("[$text]($href)")
                    } else if (text.isNotEmpty()) {
                        sb.append(text)
                    }
                }
                "strong", "b" -> {
                    sb.append("**"); children(); sb.append("**")
                }
                "em", "i" -> {
                    sb.append("*"); children(); sb.append("*")
                }
                "code" -> {
                    sb.append("`"); children(); sb.append("`")
                }
                "pre" -> {
                    sb.append("\n```\n${(node as org.jsoup.nodes.Element).text()}\n```\n\n")
                }
                "ul" -> { sb.append("\n"); children(); sb.append("\n") }
                "ol" -> { sb.append("\n"); children(); sb.append("\n") }
                "li" -> {
                    val parent = node.parent()
                    val isOrdered = parent != null && parent.nodeName() == "ol"
                    val bullet = if (isOrdered) "1." else "-"
                    sb.append("$listIndent$bullet ")
                    // Recurse into children with deeper indent, not the li itself
                    val saved = listIndent
                    fun liChildren() { for (c in node.childNodes()) process(c, saved + "  ") }
                    liChildren()
                    sb.append("\n")
                }
                "blockquote" -> {
                    sb.append("\n> "); children(); sb.append("\n\n")
                }
                "hr" -> sb.append("\n---\n\n")
                "img" -> {
                    val el = node as org.jsoup.nodes.Element
                    val src = el.absUrl("src")
                    val alt = el.attr("alt").trim()
                    if (src.isNotEmpty()) sb.append("![${alt.ifEmpty { "image" }}]($src)")
                }
                "div", "span", "section", "article", "main", "thead", "tbody", "tr", "td", "th" -> {
                    children()
                }
                else -> children()
            }
        }

        for (child in body.childNodes()) process(child, "")
        return sb.toString()
            .replace(Regex(" {3,}"), "  ")
            .replace(Regex("\n{4,}"), "\n\n\n")
            .trim()
    }
}
