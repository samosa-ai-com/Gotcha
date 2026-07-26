package com.gotcha.connectors.notion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Non-2xx Notion API response, with a user-actionable message. */
class NotionApiException(val code: Int, message: String) : IOException(message)

/**
 * Thin OkHttp wrapper over the Notion API. Authenticated with an internal
 * integration token (no OAuth, no refresh), so every call simply carries the
 * secret as a Bearer token. JVM-testable via a MockWebServer [baseUrl].
 */
class NotionApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = "https://api.notion.com/v1"
) {

    companion object {
        /** Pinned API version — Notion requires this header on every request. */
        const val NOTION_VERSION = "2022-06-28"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** `GET /users/me` — validates the token and names the integration/workspace. */
    suspend fun me(token: String): JsonObject = get("$baseUrl/users/me", token)

    /**
     * `POST /search` — only ever returns pages explicitly shared with the
     * integration, which is the single most common source of "why is it empty?".
     */
    suspend fun search(token: String, query: String?, pageSize: Int): JsonArray {
        val body = buildJsonObject {
            if (!query.isNullOrBlank()) put("query", JsonPrimitive(query))
            put("page_size", JsonPrimitive(pageSize))
        }
        return post("$baseUrl/search", token, body).results()
    }

    /** `GET /pages/{id}` — page metadata (properties, title, url). */
    suspend fun page(token: String, pageId: String): JsonObject =
        get("$baseUrl/pages/${pageId.trim()}", token)

    /** `GET /blocks/{id}/children` — the page's content blocks. */
    suspend fun blockChildren(token: String, blockId: String, pageSize: Int): JsonArray {
        val url = "$baseUrl/blocks/${blockId.trim()}/children?page_size=$pageSize"
        return get(url, token).results()
    }

    /** `POST /pages` — creates a page. Returns the new page object. */
    suspend fun createPage(token: String, payload: JsonObject): JsonObject =
        post("$baseUrl/pages", token, payload)

    /** `PATCH /blocks/{id}/children` — appends blocks to an existing page. */
    suspend fun appendBlocks(token: String, blockId: String, children: JsonArray): JsonObject {
        val body = buildJsonObject { put("children", children) }
        return patch("$baseUrl/blocks/${blockId.trim()}/children", token, body)
    }

    private fun JsonObject.results(): JsonArray =
        this["results"]?.jsonArray ?: JsonArray(emptyList())

    private fun Request.Builder.notionHeaders(token: String) = this
        .header("Authorization", "Bearer $token")
        .header("Notion-Version", NOTION_VERSION)

    private suspend fun get(url: String, token: String): JsonObject =
        execute(Request.Builder().url(url).notionHeaders(token).build())

    private suspend fun post(url: String, token: String, body: JsonObject): JsonObject =
        execute(
            Request.Builder()
                .url(url)
                .notionHeaders(token)
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()
        )

    private suspend fun patch(url: String, token: String, body: JsonObject): JsonObject =
        execute(
            Request.Builder()
                .url(url)
                .notionHeaders(token)
                .patch(body.toString().toRequestBody(jsonMediaType))
                .build()
        )

    private suspend fun execute(request: Request): JsonObject = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw mapError(response.code, body)
            if (body.isBlank()) buildJsonObject {} else json.parseToJsonElement(body).jsonObject
        }
    }

    private fun mapError(code: Int, body: String): NotionApiException {
        val detail = runCatching {
            json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: body.take(200)
        val message = when (code) {
            401 ->
                "Notion rejected the integration token (401). Re-copy it from " +
                    "notion.so/my-integrations and reconnect in Settings → Connectors. $detail"
            404 ->
                "Notion could not find that page (404) — most often it simply has not been " +
                    "shared with the integration. Open the page in Notion, use ⋯ → Connections, " +
                    "and add the integration. $detail"
            429 -> "Notion is rate limiting requests (429) — try again shortly. $detail"
            400 -> "Notion rejected the request (400): $detail"
            else -> "Notion API error (HTTP $code): $detail"
        }
        return NotionApiException(code, message)
    }
}
