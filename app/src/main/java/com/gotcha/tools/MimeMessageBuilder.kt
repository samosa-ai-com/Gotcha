package com.gotcha.tools

/**
 * Builds a minimal RFC 2822 plain-text message. Pure string manipulation —
 * no JavaMail dependency — so the exact same bytes can be handed to SMTP
 * (via MimeMessage parsing) and to the Gmail API (`{"raw": base64url}`).
 */
object MimeMessageBuilder {

    /**
     * Build the RFC 2822 message text (CRLF line endings). Non-ASCII subjects
     * are RFC 2047 UTF-8 base64 encoded; the body is sent as UTF-8 8bit.
     */
    fun build(
        from: String,
        to: List<String>,
        cc: List<String> = emptyList(),
        bcc: List<String> = emptyList(),
        subject: String,
        body: String
    ): String {
        require(to.isNotEmpty()) { "At least one recipient required" }
        val headers = buildList {
            add("From: ${address(from)}")
            add("To: ${addresses(to)}")
            if (cc.isNotEmpty()) add("Cc: ${addresses(cc)}")
            if (bcc.isNotEmpty()) add("Bcc: ${addresses(bcc)}")
            add("Subject: ${encodeHeader(subject)}")
            add("MIME-Version: 1.0")
            add("Content-Type: text/plain; charset=\"UTF-8\"")
            add("Content-Transfer-Encoding: 8bit")
        }
        val normalizedBody = body.replace("\r\n", "\n").replace("\n", "\r\n")
        return headers.joinToString("\r\n") + "\r\n\r\n" + normalizedBody
    }

    /** Gmail API `messages.send` payload value: base64url (no padding) of the raw message. */
    fun toBase64Url(rawMessage: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(rawMessage.toByteArray(Charsets.UTF_8))

    private fun addresses(values: List<String>): String =
        values.joinToString(", ") { address(it) }

    /**
     * A single address header value. Addresses reach here from model-generated
     * tool arguments, which in turn derive from content the agent read (emails,
     * web pages), so a CR/LF inside one would let that content inject its own
     * headers — `"a@b.com\r\nBcc: attacker@evil.com"` becoming a real Bcc.
     * No legitimate address contains a control character, so strip them all.
     */
    private fun address(value: String): String =
        value.filterNot { it.code < 32 || it.code == 127 }.trim()

    /**
     * RFC 2047 encoded-word for non-ASCII header values; plain values pass
     * through. Control characters fail the printable-ASCII test and so take the
     * base64 branch, which neutralises any CR/LF a subject might carry.
     */
    private fun encodeHeader(value: String): String {
        if (value.all { it.code in 32..126 }) return value
        val encoded = java.util.Base64.getEncoder()
            .encodeToString(value.toByteArray(Charsets.UTF_8))
        return "=?UTF-8?B?$encoded?="
    }
}
