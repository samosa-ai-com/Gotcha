package com.gotcha.connectors.microsoft

import com.gotcha.connectors.mail.EmailFull
import com.gotcha.connectors.mail.EmailSummary
import com.gotcha.connectors.mail.MailBodyExtractor
import com.gotcha.connectors.mail.OutgoingEmail
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Maps Microsoft Graph message JSON to the shared mail model and back. Pure
 * Kotlin so it is unit-testable without a device — the Graph counterpart of
 * [com.gotcha.connectors.google.GmailMessageParser].
 */
object GraphMailParser {

    /** Graph nests addresses as `{"emailAddress":{"name":..,"address":..}}`. */
    fun address(node: JsonObject?): String {
        val emailAddress = node?.get("emailAddress")?.jsonObject ?: return ""
        val name = emailAddress.str("name")
        val addr = emailAddress.str("address").orEmpty()
        return if (name.isNullOrBlank() || name == addr) addr else "$name <$addr>"
    }

    fun addresses(nodes: JsonArray?): String =
        nodes.orEmpty().joinToString(", ") { address(it.jsonObject) }.trim()

    fun summary(message: JsonObject): EmailSummary = EmailSummary(
        id = "ms:${message.str("id").orEmpty()}",
        from = address(message["from"]?.jsonObject),
        subject = message.str("subject")?.takeIf { it.isNotBlank() } ?: "(no subject)",
        date = message.str("receivedDateTime").orEmpty(),
        unread = message["isRead"]?.jsonPrimitive?.booleanOrNull?.not() ?: false,
        snippet = MailBodyExtractor.snippet(message.str("bodyPreview").orEmpty())
    )

    fun full(id: String, message: JsonObject): EmailFull = EmailFull(
        id = id,
        from = address(message["from"]?.jsonObject),
        to = addresses(message["toRecipients"]?.jsonArray),
        cc = addresses(message["ccRecipients"]?.jsonArray),
        subject = message.str("subject")?.takeIf { it.isNotBlank() } ?: "(no subject)",
        date = message.str("receivedDateTime").orEmpty(),
        body = body(message["body"]?.jsonObject)
    )

    /** Graph bodies carry their own contentType; HTML is stripped to readable text. */
    fun body(bodyNode: JsonObject?): String {
        val content = bodyNode?.str("content").orEmpty()
        return if (bodyNode?.str("contentType")?.equals("html", ignoreCase = true) == true) {
            MailBodyExtractor.htmlToText(content)
        } else {
            content.trim()
        }
    }

    /** Builds the `message` payload for `POST /me/sendMail`. */
    fun outgoing(message: OutgoingEmail): JsonObject = buildJsonObject {
        put("subject", JsonPrimitive(message.subject))
        put(
            "body",
            buildJsonObject {
                put("contentType", JsonPrimitive("Text"))
                put("content", JsonPrimitive(message.body))
            }
        )
        put("toRecipients", recipients(message.to))
        if (message.cc.isNotEmpty()) put("ccRecipients", recipients(message.cc))
        if (message.bcc.isNotEmpty()) put("bccRecipients", recipients(message.bcc))
    }

    private fun recipients(addresses: List<String>): JsonArray = buildJsonArray {
        addresses.forEach { addr ->
            add(
                buildJsonObject {
                    put(
                        "emailAddress",
                        buildJsonObject { put("address", JsonPrimitive(addr.trim())) }
                    )
                }
            )
        }
    }

    /** Strips the uniform `ms:` prefix from a message id. */
    fun stripPrefix(id: String): String = id.removePrefix("ms:")

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
