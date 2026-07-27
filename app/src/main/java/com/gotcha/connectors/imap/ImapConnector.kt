package com.gotcha.connectors.imap

import com.gotcha.connectors.Connector
import com.gotcha.connectors.CredentialStore
import com.gotcha.connectors.mail.EmailFull
import com.gotcha.connectors.mail.EmailSummary
import com.gotcha.connectors.mail.MailBackend
import com.gotcha.connectors.mail.MailBodyExtractor
import com.gotcha.connectors.mail.OutgoingEmail
import com.gotcha.tools.MimeMessageBuilder
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.Transport
import javax.mail.UIDFolder
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.search.BodyTerm
import javax.mail.search.FlagTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.SearchTerm
import javax.mail.search.SubjectTerm

@Serializable
data class ImapCredentials(
    val email: String,
    val appPassword: String,
    val imapHost: String,
    val imapPort: Int = 993,
    val smtpHost: String,
    val smtpPort: Int = 465
) {
    companion object {
        /** Gmail preset (requires a Google app password). */
        fun gmail(email: String, appPassword: String) = ImapCredentials(
            email = email,
            appPassword = appPassword,
            imapHost = "imap.gmail.com",
            imapPort = 993,
            smtpHost = "smtp.gmail.com",
            smtpPort = 465
        )
    }
}

/** Thrown when the server rejects the stored app password. */
class ImapAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Provider-agnostic IMAP/SMTP connector using app-password auth. Works with
 * any provider that offers IMAP (Gmail preset in Settings). Caches the IMAP
 * [Store] and reconnects when stale. All I/O runs on the caller's dispatcher
 * (ToolExecutor already dispatches on IO).
 */
class ImapConnector(
    private val store: CredentialStore
) : Connector, MailBackend {

    override val id = "imap"
    override val displayName = "Email (IMAP)"
    override val description =
        "Read and send email on any provider via IMAP/SMTP with an app password."
    override val toolNames =
        setOf("list_emails", "read_email", "send_email", "mark_email_read")
    override val idPrefix = "imap"
    override val spec = com.gotcha.connectors.ConnectorCatalog.IMAP

    private val json = Json { ignoreUnknownKeys = true }
    private var cachedStore: Store? = null

    @Volatile
    private var credentials: ImapCredentials? = store.loadRaw(id)?.let { blob ->
        runCatching { json.decodeFromString<ImapCredentials>(blob) }.getOrNull()
    }

    fun credentials(): ImapCredentials? = credentials

    fun connect(newCredentials: ImapCredentials) {
        disconnect()
        store.saveRaw(id, json.encodeToString(newCredentials))
        credentials = newCredentials
    }

    override fun isConnected(): Boolean = credentials != null

    override fun statusLine(): String =
        credentials?.let { "Connected as ${it.email} (${it.imapHost})" } ?: "Not connected"

    @Synchronized
    override fun disconnect() {
        runCatching { cachedStore?.close() }
        cachedStore = null
        store.clear(id)
        credentials = null
    }

    /** Opens (or reuses) the IMAP store. Throws [ImapAuthException] on bad credentials. */
    @Synchronized
    private fun imapStore(): Store {
        val creds = checkNotNull(credentials) { "IMAP is not connected." }
        cachedStore?.let { if (it.isConnected) return it }
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", creds.imapHost)
            put("mail.imaps.port", creds.imapPort.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "20000")
            put("mail.imaps.timeout", "30000")
        }
        val session = Session.getInstance(props)
        val newStore = session.getStore("imaps")
        try {
            newStore.connect(creds.imapHost, creds.email, creds.appPassword)
        } catch (e: AuthenticationFailedException) {
            throw ImapAuthException(
                "The mail server rejected the app password — open Settings and reconnect the IMAP connector.",
                e
            )
        }
        cachedStore = newStore
        return newStore
    }

    private fun smtpSession(creds: ImapCredentials): Session {
        val props = Properties().apply {
            put("mail.transport.protocol", "smtp")
            put("mail.smtp.host", creds.smtpHost)
            put("mail.smtp.port", creds.smtpPort.toString())
            put("mail.smtp.auth", "true")
            if (creds.smtpPort == 587) {
                put("mail.smtp.starttls.enable", "true")
            } else {
                put("mail.smtp.ssl.enable", "true")
            }
            put("mail.smtp.connectiontimeout", "20000")
            put("mail.smtp.timeout", "30000")
        }
        return Session.getInstance(props)
    }

    // ---- MailBackend ----

    override suspend fun list(query: String?, unreadOnly: Boolean, max: Int): List<EmailSummary> {
        val inbox = imapStore().getFolder("INBOX")
        inbox.open(Folder.READ_ONLY)
        try {
            val terms = mutableListOf<SearchTerm>()
            if (unreadOnly) terms += FlagTerm(Flags(Flags.Flag.SEEN), false)
            if (!query.isNullOrBlank()) {
                terms += OrTerm(arrayOf(SubjectTerm(query), FromStringTerm(query), BodyTerm(query)))
            }
            val messages: Array<Message> = when (terms.size) {
                0 -> inbox.messages
                1 -> inbox.search(terms[0])
                else -> inbox.search(terms.reduce { a, b -> javax.mail.search.AndTerm(a, b) })
            }
            val uidFolder = inbox as UIDFolder
            // Newest first; message number order follows arrival order.
            return messages.sortedByDescending { it.messageNumber }.take(max).map { msg ->
                val body = runCatching { extractBody(msg) }.getOrDefault("")
                EmailSummary(
                    id = "imap:INBOX:${uidFolder.getUID(msg)}",
                    from = msg.from?.joinToString { addr -> addr.toString() } ?: "",
                    subject = msg.subject ?: "(no subject)",
                    date = msg.sentDate?.toInstant()?.toString()
                        ?: msg.receivedDate?.toInstant()?.toString().orEmpty(),
                    unread = !msg.isSet(Flags.Flag.SEEN),
                    snippet = MailBodyExtractor.snippet(body)
                )
            }
        } finally {
            runCatching { inbox.close(false) }
        }
    }

    override suspend fun read(id: String): EmailFull {
        val (folderName, uid) = parseId(id)
        val folder = imapStore().getFolder(folderName)
        folder.open(Folder.READ_ONLY)
        try {
            val msg = (folder as UIDFolder).getMessageByUID(uid)
                ?: throw IllegalArgumentException("No message with id $id — it may have been moved or deleted.")
            return EmailFull(
                id = id,
                from = msg.from?.joinToString { it.toString() } ?: "",
                to = msg.getRecipients(Message.RecipientType.TO)?.joinToString { it.toString() } ?: "",
                cc = msg.getRecipients(Message.RecipientType.CC)?.joinToString { it.toString() } ?: "",
                subject = msg.subject ?: "(no subject)",
                date = msg.sentDate?.toInstant()?.toString() ?: Instant.EPOCH.toString(),
                body = extractBody(msg)
            )
        } finally {
            runCatching { folder.close(false) }
        }
    }

    override suspend fun send(message: OutgoingEmail): String {
        val creds = checkNotNull(credentials) { "IMAP is not connected." }
        val raw = MimeMessageBuilder.build(
            from = creds.email,
            to = message.to,
            cc = message.cc,
            bcc = message.bcc,
            subject = message.subject,
            body = message.body
        )
        val session = smtpSession(creds)
        val mime = MimeMessage(session, ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))
        try {
            Transport.send(mime, creds.email, creds.appPassword)
        } catch (e: AuthenticationFailedException) {
            throw ImapAuthException(
                "The SMTP server rejected the app password — open Settings and reconnect the IMAP connector.",
                e
            )
        }
        return "Email sent to ${message.to.joinToString()} via ${creds.smtpHost}."
    }

    override suspend fun markRead(id: String, read: Boolean) {
        val (folderName, uid) = parseId(id)
        val folder = imapStore().getFolder(folderName)
        folder.open(Folder.READ_WRITE)
        try {
            val msg = (folder as UIDFolder).getMessageByUID(uid)
                ?: throw IllegalArgumentException("No message with id $id — it may have been moved or deleted.")
            msg.setFlag(Flags.Flag.SEEN, read)
        } finally {
            runCatching { folder.close(false) }
        }
    }

    private fun parseId(id: String): Pair<String, Long> {
        val parts = id.split(":", limit = 3)
        require(parts.size == 3 && parts[0] == "imap") { "Not an IMAP message id: $id" }
        return parts[1] to (
            parts[2].toLongOrNull()
                ?: throw IllegalArgumentException("Bad IMAP uid in id: $id")
            )
    }

    /** Prefer text/plain; fall back to stripped HTML; recurse into multiparts. */
    private fun extractBody(part: Part): String = when {
        part.isMimeType("text/plain") -> part.content?.toString().orEmpty()
        part.isMimeType("text/html") ->
            MailBodyExtractor.htmlToText(part.content?.toString().orEmpty())
        part.isMimeType("multipart/*") -> {
            val multipart = part.content as MimeMultipart
            val parts = (0 until multipart.count).map { multipart.getBodyPart(it) }
            parts.firstOrNull { it.isMimeType("text/plain") }?.let { extractBody(it) }
                ?.takeIf { it.isNotBlank() }
                ?: parts.asSequence().map { extractBody(it) }.firstOrNull { it.isNotBlank() }
                ?: ""
        }
        else -> ""
    }
}
