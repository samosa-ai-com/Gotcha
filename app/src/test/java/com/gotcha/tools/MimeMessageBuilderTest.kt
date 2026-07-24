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
