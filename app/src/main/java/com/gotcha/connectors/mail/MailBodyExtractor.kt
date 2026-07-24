package com.gotcha.connectors.mail

import org.jsoup.Jsoup

/**
 * Extracts a readable plain-text body from raw message content: prefers
 * text/plain parts; strips markup from HTML parts with jsoup.
 */
object MailBodyExtractor {

    /** Convert an HTML body to readable plain text. */
    fun htmlToText(html: String): String {
        val doc = Jsoup.parse(html)
        // Preserve rough line structure: jsoup's text() collapses everything to one line.
        doc.select("br").append("\\n")
        doc.select("p, div, li, tr, h1, h2, h3, h4, h5, h6").append("\\n")
        return doc.text()
            .replace("\\n", "\n")
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /** Collapse whitespace and truncate to a listing snippet. */
    fun snippet(text: String, maxChars: Int = 150): String {
        val collapsed = text.replace(Regex("\\s+"), " ").trim()
        return if (collapsed.length <= maxChars) collapsed else collapsed.take(maxChars) + "…"
    }
}
