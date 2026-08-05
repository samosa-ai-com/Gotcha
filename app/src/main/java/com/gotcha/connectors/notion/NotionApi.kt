package com.gotcha.connectors.notion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Non-2xx Notion API response, with a user-actionable message. */
class NotionApiException(val code: Int, message: String) : IOException(message)

/** A paginated list response: `results` plus the cursor that continues it. */
data class ListResult(
    val results: JsonArray,
    val hasMore: Boolean,
    val nextCursor: String?
)

/**
 * Thin OkHttp wrapper over the Notion API. Authenticated with an internal
 * integration token (no OAuth, no refresh), so every call simply carries the
 * secret as a Bearer token. JVM-testable via a MockWebServer [baseUrl].
 */
@Suppress("TooManyFunctions") // one function per Notion endpoint; splitting by service would not help
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

    /**
     * `GET /databases/{id}` — database metadata. Todo lists and other table-style
     * content are stored as databases, which live under `/databases/{id}` rather
     * than `/pages/{id}`.
     */
    suspend fun database(token: String, databaseId: String): JsonObject =
        get("$baseUrl/databases/${databaseId.trim()}", token)

    /** `POST /databases/{id}/query` — a database's rows (each row is a page). */
    suspend fun databaseQuery(
        token: String,
        databaseId: String,
        pageSize: Int,
        startCursor: String? = null
    ): ListResult {
        val body = buildJsonObject {
            put("page_size", JsonPrimitive(pageSize))
            startCursor?.let { put("start_cursor", JsonPrimitive(it)) }
        }
        return post("$baseUrl/databases/${databaseId.trim()}/query", token, body).toListResult()
    }

    /** `GET /blocks/{id}/children` — a page's (or block's) content blocks. */
    suspend fun blockChildren(
        token: String,
        blockId: String,
        pageSize: Int,
        startCursor: String? = null
    ): ListResult {
        val url = buildString {
            append("$baseUrl/blocks/${blockId.trim()}/children?page_size=$pageSize")
            startCursor?.let {
                append("&start_cursor=").append(URLEncoder.encode(it, "UTF-8"))
            }
        }
        return get(url, token).toListResult()
    }

    /** `POST /pages` — creates a page. Returns the new page object. */
    suspend fun createPage(token: String, payload: JsonObject): JsonObject =
        post("$baseUrl/pages", token, payload)

    /** `PATCH /pages/{id}` — updates a page/row's properties, or trashes it (`in_trash`). */
    suspend fun updatePage(token: String, pageId: String, payload: JsonObject): JsonObject =
        patch("$baseUrl/pages/${pageId.trim()}", token, payload)

    /** `PATCH /blocks/{id}/children` — appends blocks to an existing page. */
    suspend fun appendBlocks(token: String, blockId: String, children: JsonArray): JsonObject {
        val body = buildJsonObject { put("children", children) }
        return patch("$baseUrl/blocks/${blockId.trim()}/children", token, body)
    }

    /** `PATCH /blocks/{id}` — updates a block (e.g. a to-do's checked state). */
    suspend fun updateBlock(token: String, blockId: String, payload: JsonObject): JsonObject =
        patch("$baseUrl/blocks/${blockId.trim()}", token, payload)

    /** `DELETE /blocks/{id}` — permanently deletes a block and its children. */
    suspend fun deleteBlock(token: String, blockId: String): JsonObject =
        delete("$baseUrl/blocks/${blockId.trim()}", token)

    private fun JsonObject.results(): JsonArray =
        this["results"] as? JsonArray ?: JsonArray(emptyList())

    /** Single place that unpacks a paginated list response. */
    private fun JsonObject.toListResult(): ListResult = ListResult(
        results = results(),
        hasMore = this["has_more"]?.jsonPrimitive?.booleanOrNull ?: false,
        nextCursor = this["next_cursor"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    )

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

    private suspend fun delete(url: String, token: String): JsonObject =
        execute(
            Request.Builder()
                .url(url)
                .notionHeaders(token)
                .delete()
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
                if (detail.contains("database", ignoreCase = true)) {
                    "Notion could not find that database (404). A database id (e.g. a todo " +
                        "list) can only be read through notion_read_page, which tries databases " +
                        "as well as pages — re-run notion_search and pass the id it returns. " +
                        "If it still fails, the database has not been shared with the " +
                        "integration. $detail"
                } else {
                    "Notion could not find that page (404) — most often it simply has not been " +
                        "shared with the integration. Open the page in Notion, use ⋯ → Connections, " +
                        "and add the integration. $detail"
                }
            429 -> "Notion is rate limiting requests (429) — try again shortly. $detail"
            400 -> "Notion rejected the request (400): $detail"
            else -> "Notion API error (HTTP $code): $detail"
        }
        return NotionApiException(code, message)
    }
}
