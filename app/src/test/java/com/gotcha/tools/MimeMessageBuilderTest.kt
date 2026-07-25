package com.gotcha.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeMessageBuilderTest {

    @Test
    fun `builds RFC 2822 message with CRLF line endings`() {
        val raw = MimeMessageBuilder.build(
            from = "me@example.com",
            to = listOf("you@example.com"),
            subject = "Hello",
            body = "Line one\nLine two"
        )
        assertTrue(raw.contains("From: me@example.com\r\n"))
        assertTrue(raw.contains("To: you@example.com\r\n"))
        assertTrue(raw.contains("Subject: Hello\r\n"))
        assertTrue(raw.contains("\r\n\r\nLine one\r\nLine two"))
    }

    @Test
    fun `includes cc and bcc when present`() {
        val raw = MimeMessageBuilder.build(
            from = "me@example.com",
            to = listOf("a@example.com", "b@example.com"),
            cc = listOf("c@example.com"),
            bcc = listOf("d@example.com"),
            subject = "Subj",
            body = "Body"
        )
        assertTrue(raw.contains("To: a@example.com, b@example.com\r\n"))
        assertTrue(raw.contains("Cc: c@example.com\r\n"))
        assertTrue(raw.contains("Bcc: d@example.com\r\n"))
    }

    @Test
    fun `non-ascii subject is RFC 2047 encoded`() {
        val raw = MimeMessageBuilder.build(
            from = "me@example.com",
            to = listOf("you@example.com"),
            subject = "Café",
            body = "Body"
        )
        assertTrue(raw.contains("Subject: =?UTF-8?B?"))
    }

    @Test
    fun `ascii subject passes through unencoded`() {
        val raw = MimeMessageBuilder.build(
            from = "me@example.com",
            to = listOf("you@example.com"),
            subject = "Plain subject",
            body = "Body"
        )
        assertTrue(raw.contains("Subject: Plain subject\r\n"))
    }

    @Test
    fun `CRLF in a recipient cannot inject a header`() {
        val raw = MimeMessageBuilder.build(
            from = "me@example.com",
            to = listOf("you@example.com\r\nBcc: attacker@evil.com"),
            subject = "Subj",
            body = "Body"
        )
        // The text survives as inert content of the To header, not as a header
        // of its own — which is what a leading CRLF would have made it.
        assertEquals(false, raw.contains("\r\nBcc:"))
        assertTrue(raw.contains("To: you@example.comBcc: attacker@evil.com\r\n"))
        // Exactly one blank line, i.e. one header block: nothing escaped into it.
        assertEquals(1, Regex("\r\n\r\n").findAll(raw).count())
    }

    @Test
    fun `CRLF in from and cc is stripped too`() {
        val raw = MimeMessageBuilder.build(
            from = "me@example.com\nX-Spoof: yes",
            to = listOf("you@example.com"),
            cc = listOf("c@example.com\r\nX-Also: no"),
            subject = "Subj",
            body = "Body"
        )
        assertEquals(false, raw.contains("\r\nX-Spoof:"))
        assertEquals(false, raw.contains("\r\nX-Also:"))
    }

    @Test
    fun `CRLF in the subject is neutralised by encoding`() {
        val raw = MimeMessageBuilder.build(
            from = "me@example.com",
            to = listOf("you@example.com"),
            subject = "Hi\r\nBcc: attacker@evil.com",
            body = "Body"
        )
        assertTrue(raw.contains("Subject: =?UTF-8?B?"))
        assertEquals(false, raw.contains("Bcc:"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `requires at least one recipient`() {
        MimeMessageBuilder.build(from = "me@example.com", to = emptyList(), subject = "s", body = "b")
    }

    @Test
    fun `base64url encoding has no padding`() {
        val raw = "test message"
        val encoded = MimeMessageBuilder.toBase64Url(raw)
        assertEquals(false, encoded.contains("="))
        assertEquals(false, encoded.contains("+"))
        assertEquals(false, encoded.contains("/"))
    }
}
