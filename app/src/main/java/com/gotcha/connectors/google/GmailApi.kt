package com.gotcha.connectors.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Non-2xx Gmail API response, with a user-actionable message. */
class GmailApiException(val code: Int, message: String) : IOException(message)

/**
 * Thin OkHttp wrapper over the Gmail REST API. Stateless: every call takes the
 * Bearer token so [GoogleConnector] can own refresh/retry. JVM-testable via a
 * MockWebServer [baseUrl].
 */
class GmailApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = "https://gmail.googleapis.com/gmail/v1/users/me"
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** messages.list — returns message ids (possibly empty). */
    suspend fun listMessageIds(
        accessToken: String,
        query: String?,
        unreadOnly: Boolean,
        max: Int
    ): List<String> {
        val effectiveQuery = listOfNotNull(
            query?.takeIf { it.isNotBlank() },
            "is:unread".takeIf { unreadOnly }
        ).joinToString(" ")
        val url = "$baseUrl/messages".toHttpUrl().newBuilder()
            .addQueryParameter("maxResults", max.toString())
            .apply { if (effectiveQuery.isNotBlank()) addQueryParameter("q", effectiveQuery) }
            .build()
        val obj = get(url.toString(), accessToken)
        return obj["messages"]?.jsonArray?.mapNotNull {
            it.jsonObject["id"]?.jsonPrimitive?.contentOrNull
        } ?: emptyList()
    }

    /** messages.get?format=metadata — headers + snippet + labels. */
    suspend fun getMessageMetadata(accessToken: String, id: String): JsonObject {
        val url = "$baseUrl/messages/$id".toHttpUrl().newBuilder()
            .addQueryParameter("format", "metadata")
            .addQueryParameter("metadataHeaders", "From")
            .addQueryParameter("metadataHeaders", "To")
            .addQueryParameter("metadataHeaders", "Subject")
            .addQueryParameter("metadataHeaders", "Date")
            .build()
        return get(url.toString(), accessToken)
    }

    /** messages.get?format=full — complete payload for body extraction. */
    suspend fun getMessageFull(accessToken: String, id: String): JsonObject =
        get("$baseUrl/messages/$id?format=full", accessToken)

    /** messages.send with a base64url raw RFC 2822 message; returns the new message id. */
    suspend fun sendRaw(accessToken: String, rawBase64Url: String): String {
        val body = buildJsonObject { put("raw", rawBase64Url) }
        val obj = post("$baseUrl/messages/send", accessToken, body)
        return obj["id"]?.jsonPrimitive?.contentOrNull ?: "(unknown id)"
    }

    /** messages.modify — add/remove the UNREAD label. */
    suspend fun setUnread(accessToken: String, id: String, unread: Boolean) {
        val body = buildJsonObject {
            putJsonArray(if (unread) "addLabelIds" else "removeLabelIds") { add("UNREAD") }
        }
        post("$baseUrl/messages/$id/modify", accessToken, body)
    }

    /** users.getProfile — the connected account's email address. */
    suspend fun profileEmail(accessToken: String): String {
        val obj = get("$baseUrl/profile", accessToken)
        return obj["emailAddress"]?.jsonPrimitive?.contentOrNull ?: "(unknown)"
    }

    private suspend fun get(url: String, accessToken: String): JsonObject =
        execute(Request.Builder().url(url).header("Authorization", "Bearer $accessToken").build())

    private suspend fun post(url: String, accessToken: String, body: JsonObject): JsonObject =
        execute(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()
        )

    private suspend fun execute(request: Request): JsonObject = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw mapError(response.code, body)
            if (body.isBlank()) buildJsonObject {} else json.parseToJsonElement(body).jsonObject
        }
    }

    private fun mapError(code: Int, body: String): GmailApiException {
        val detail = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]
                ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: body.take(200)
        val message = when (code) {
            401 -> "Gmail auth expired (401): $detail"
            403, 429 -> "Gmail API rate/quota limit (HTTP $code) — try again later. $detail"
            400 -> "Gmail API rejected the request (400): $detail"
            else -> "Gmail API error (HTTP $code): $detail"
        }
        return GmailApiException(code, message)
    }
}
