package com.gotcha.connectors.mail

/** One row in a mailbox listing. */
data class EmailSummary(
    /** Uniform id: `gmail:<messageId>` or `imap:<folder>:<uid>`. */
    val id: String,
    val from: String,
    val subject: String,
    /** ISO-8601 date string, empty when the message has no date header. */
    val date: String,
    val unread: Boolean,
    /** Plain-text snippet, roughly 150 chars. */
    val snippet: String
)

/** Full content of a single message. */
data class EmailFull(
    val id: String,
    val from: String,
    val to: String,
    val cc: String,
    val subject: String,
    val date: String,
    val body: String
)

/** An outgoing message (already confirmed by the user). */
data class OutgoingEmail(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String,
    val body: String
)

/**
 * Common mail operations implemented by both the IMAP/SMTP and the Gmail REST
 * backends. All functions may throw; EmailTools converts failures to ToolResults.
 */
interface MailBackend {
    /** Uniform id prefix this backend owns ("gmail" or "imap"). */
    val idPrefix: String

    suspend fun list(query: String?, unreadOnly: Boolean, max: Int): List<EmailSummary>

    suspend fun read(id: String): EmailFull

    /** Sends the message; returns a short human-readable confirmation. */
    suspend fun send(message: OutgoingEmail): String

    suspend fun markRead(id: String, read: Boolean)
}
