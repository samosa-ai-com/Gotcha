package com.gotcha.connectors.google

import com.gotcha.connectors.mail.MailBodyExtractor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Pure helpers for pulling readable fields out of a Gmail API message JSON payload. */
internal object GmailMessageParser {

    fun stripPrefix(id: String): String = id.removePrefix("gmail:")

    fun headerMap(message: JsonObject): Map<String, String> =
        message["payload"]?.jsonObject?.get("headers")?.jsonArray
            ?.mapNotNull { h ->
                val obj = h.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val value = obj["value"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                name.lowercase() to value
            }?.toMap() ?: emptyMap()

    fun labelIds(message: JsonObject): List<String> =
        message["labelIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    /** Prefer text/plain parts; fall back to stripped text/html; recurse into multiparts. */
    fun extractBody(payload: JsonObject?): String {
        payload ?: return ""
        val mimeType = payload["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val data = payload["body"]?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
        if (data != null && (mimeType.startsWith("text/") || mimeType.isEmpty())) {
            val decoded = decodeBase64Url(data)
            return if (mimeType == "text/html") MailBodyExtractor.htmlToText(decoded) else decoded
        }
        val parts = payload["parts"]?.jsonArray?.map { it.jsonObject } ?: return ""
        parts.firstOrNull { it["mimeType"]?.jsonPrimitive?.contentOrNull == "text/plain" }
            ?.let { extractBody(it).takeIf { body -> body.isNotBlank() }?.let { body -> return body } }
        return parts.asSequence().map { extractBody(it) }.firstOrNull { it.isNotBlank() } ?: ""
    }

    private fun decodeBase64Url(data: String): String =
        runCatching {
            String(java.util.Base64.getUrlDecoder().decode(data), Charsets.UTF_8)
        }.getOrDefault("")
}
