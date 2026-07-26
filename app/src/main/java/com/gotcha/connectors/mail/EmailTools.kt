package com.gotcha.connectors.mail

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.gotcha.connectors.ToolRouter
import com.gotcha.connectors.google.GoogleConnector
import com.gotcha.connectors.imap.ImapConnector
import com.gotcha.connectors.microsoft.MicrosoftConnector
import com.gotcha.tools.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Router owning the email tools. Backend precedence for new operations: Gmail API,
 * else Microsoft Graph, else IMAP/SMTP, else an error steering the model to Settings
 * or `compose_email`. Message ids are uniform (`gmail:...` / `ms:...` / `imap:...`)
 * and read/mark route by prefix, so ids stay valid even after the user connects or
 * disconnects another backend.
 */
class EmailTools(
    private val gmailBackend: () -> MailBackend?,
    private val microsoftBackend: () -> MailBackend?,
    private val imapBackend: () -> MailBackend?,
    private val composeLauncher: (to: String?, subject: String?, body: String?) -> ToolResult
) : ToolRouter {

    constructor(
        context: Context,
        imap: ImapConnector,
        google: GoogleConnector,
        microsoft: MicrosoftConnector
    ) : this(
        gmailBackend = { google.takeIf { it.isConnected() } },
        microsoftBackend = { microsoft.takeIf { it.isConnected() } },
        imapBackend = { imap.takeIf { it.isConnected() } },
        composeLauncher = { to, subject, body -> launchCompose(context, to, subject, body) }
    )

    companion object {
        const val CONFIRM_SEND_PREFIX = "CONFIRM_SEND_EMAIL:"
        private const val DEFAULT_LIST_MAX = 10
        private const val MAX_LIST_MAX = 50

        private fun launchCompose(
            context: Context,
            to: String?,
            subject: String?,
            body: String?
        ): ToolResult {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:" + Uri.encode(to.orEmpty()))
                subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                body?.let { putExtra(Intent.EXTRA_TEXT, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolResult.error("No email app is installed that can handle compose.")
            }
            context.startActivity(intent)
            return ToolResult.ok(
                "Opened the email composer" + (to?.let { " addressed to $it" } ?: "") +
                    ". The user reviews and sends it themselves."
            )
        }
    }

    override val toolNames: Set<String> =
        setOf("list_emails", "read_email", "send_email", "mark_email_read", "compose_email")

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(name: String, args: JsonObject): ToolResult = try {
        when (name) {
            "list_emails" -> listEmails(args)
            "read_email" -> readEmail(args)
            "send_email" -> requestSendConfirmation(args)
            "mark_email_read" -> markEmailRead(args)
            "compose_email" -> composeLauncher(
                args.optString("to"),
                args.optString("subject"),
                args.optString("body")
            )
            else -> ToolResult.error("Unknown email tool '$name'.")
        }
    } catch (e: Exception) {
        ToolResult.error("$name failed: ${e.message}")
    }

    /** Preferred backend for new operations (listing, sending). */
    private fun primaryBackend(): MailBackend? =
        gmailBackend() ?: microsoftBackend() ?: imapBackend()

    /** Backend that owns an existing message id, by prefix. */
    private fun backendForId(id: String): MailBackend? = when {
        id.startsWith("gmail:") -> gmailBackend()
        id.startsWith("ms:") -> microsoftBackend()
        id.startsWith("imap:") -> imapBackend()
        else -> null
    }

    private fun notConnectedError(): ToolResult = ToolResult.error(
        "No email account is connected. Ask the user to connect Gmail, Microsoft or IMAP in " +
            "Settings → Connectors, or use compose_email to open their email app with a " +
            "pre-filled draft instead."
    )

    private suspend fun listEmails(args: JsonObject): ToolResult {
        val backend = primaryBackend() ?: return notConnectedError()
        val max = (args.optInt("max") ?: DEFAULT_LIST_MAX).coerceIn(1, MAX_LIST_MAX)
        val emails = backend.list(
            query = args.optString("query"),
            unreadOnly = args.optBoolean("unread_only") ?: false,
            max = max
        )
        if (emails.isEmpty()) return ToolResult.ok("No matching emails found.")
        val rows = emails.joinToString("\n") { e ->
            val flag = if (e.unread) "UNREAD" else "read"
            "[${e.id}] $flag | ${e.date} | from: ${e.from} | ${e.subject}\n    ${e.snippet}"
        }
        return ToolResult.ok("${emails.size} email(s):\n$rows")
    }

    private suspend fun readEmail(args: JsonObject): ToolResult {
        val id = args.optString("id") ?: return ToolResult.error("Missing required arg 'id'.")
        val backend = backendForId(id)
            ?: return ToolResult.error(
                "Cannot read '$id' — its backend is not connected (ids look like gmail:... or imap:...)."
            )
        val email = backend.read(id)
        return ToolResult.ok(
            "From: ${email.from}\nTo: ${email.to}\n" +
                (if (email.cc.isNotBlank()) "Cc: ${email.cc}\n" else "") +
                "Date: ${email.date}\nSubject: ${email.subject}\n\n${email.body}"
        )
    }

    private suspend fun markEmailRead(args: JsonObject): ToolResult {
        val id = args.optString("id") ?: return ToolResult.error("Missing required arg 'id'.")
        val read = args.optBoolean("read") ?: true
        val backend = backendForId(id)
            ?: return ToolResult.error("Cannot modify '$id' — its backend is not connected.")
        backend.markRead(id, read)
        return ToolResult.ok("Marked $id as ${if (read) "read" else "unread"}.")
    }

    /**
     * send_email never sends directly: it validates, then returns a
     * CONFIRM_SEND_EMAIL payload that AgentEngine turns into a user
     * confirmation dialog; on approval ToolExecutor calls [executeSendConfirmed].
     */
    private fun requestSendConfirmation(args: JsonObject): ToolResult {
        if (primaryBackend() == null) {
            return ToolResult.error(
                "No email account is connected, so send_email is unavailable. " +
                    "Use compose_email to open the user's email app with a pre-filled draft, " +
                    "or ask them to connect an account in Settings → Connectors."
            )
        }
        parseOutgoing(args)
            ?: return ToolResult.error("send_email needs 'to' (list or string), 'subject' and 'body'.")
        val payload = java.util.Base64.getEncoder()
            .encodeToString(args.toString().toByteArray(Charsets.UTF_8))
        return ToolResult.ok(CONFIRM_SEND_PREFIX + payload)
    }

    /** Executes a send the user already approved. [argsBase64] is the CONFIRM payload body. */
    suspend fun executeSendConfirmed(argsBase64: String): ToolResult {
        return try {
            val args = json.parseToJsonElement(
                String(java.util.Base64.getDecoder().decode(argsBase64), Charsets.UTF_8)
            ) as JsonObject
            val message = parseOutgoing(args)
                ?: return ToolResult.error("Send confirmation payload was malformed.")
            val backend = primaryBackend()
                ?: return ToolResult.error("No email account is connected anymore.")
            ToolResult.ok(backend.send(message))
        } catch (e: Exception) {
            ToolResult.error("send_email failed: ${e.message}")
        }
    }

    /** Human-readable summary of a pending send, for the confirmation dialog. */
    fun describeSend(argsBase64: String): String = runCatching {
        val args = json.parseToJsonElement(
            String(java.util.Base64.getDecoder().decode(argsBase64), Charsets.UTF_8)
        ) as JsonObject
        val message = parseOutgoing(args)
            ?: return@runCatching "Send an email (details unavailable)."
        val bodyPreview = message.body.replace(Regex("\\s+"), " ").trim().take(200)
        buildString {
            append("Send email to ${message.to.joinToString()}")
            if (message.cc.isNotEmpty()) append(" (cc: ${message.cc.joinToString()})")
            append("\nSubject: ${message.subject}")
            append("\n\n$bodyPreview")
            if (message.body.length > 200) append("…")
        }
    }.getOrDefault("Send an email (details unavailable).")

    private fun parseOutgoing(args: JsonObject): OutgoingEmail? {
        val to = args.optStringList("to")
        val subject = args.optString("subject")
        val body = args.optString("body")
        if (to.isEmpty() || subject == null || body == null) return null
        return OutgoingEmail(
            to = to,
            cc = args.optStringList("cc"),
            bcc = args.optStringList("bcc"),
            subject = subject,
            body = body
        )
    }

    private fun JsonObject.optString(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.optInt(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.optBoolean(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull

    /** Accepts either a JSON array of strings or a single/comma-separated string. */
    private fun JsonObject.optStringList(key: String): List<String> {
        val element = this[key] ?: return emptyList()
        return runCatching {
            element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrElse {
            element.jsonPrimitive.contentOrNull
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
        }
    }
}
