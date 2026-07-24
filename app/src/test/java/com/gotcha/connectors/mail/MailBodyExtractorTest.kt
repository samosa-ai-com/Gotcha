package com.gotcha.connectors.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MailBodyExtractorTest {

    @Test
    fun `strips html tags to readable text`() {
        val html = "<html><body><p>Hello <b>world</b></p><p>Second paragraph</p></body></html>"
        val text = MailBodyExtractor.htmlToText(html)
        assertTrue(text.contains("Hello world"))
        assertTrue(text.contains("Second paragraph"))
        assertTrue(!text.contains("<"))
    }

    @Test
    fun `snippet truncates long text with ellipsis`() {
        val long = "a".repeat(200)
        val snippet = MailBodyExtractor.snippet(long, maxChars = 150)
        assertEquals(151, snippet.length) // 150 chars + ellipsis
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun `snippet collapses whitespace`() {
        val text = "line one\n\n  line two\t\tline three"
        val snippet = MailBodyExtractor.snippet(text)
        assertEquals("line one line two line three", snippet)
    }

    @Test
    fun `snippet passes short text through unchanged`() {
        assertEquals("short text", MailBodyExtractor.snippet("short text"))
    }
}
