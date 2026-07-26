package com.gotcha.connectors.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Thin OkHttp wrapper over the Google Calendar v3 REST API. Stateless: every
 * call takes the Bearer token so [GoogleConnector] owns refresh/retry. Reuses
 * [GmailApiException] so the connector's shared 401-retry path applies to both
 * Google services. JVM-testable via a MockWebServer [baseUrl].
 */
class GoogleCalendarApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = "https://www.googleapis.com/calendar/v3"
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * `events.list` with `singleEvents=true` so recurring events are expanded into
     * individual instances within the window. [timeMin]/[timeMax] are RFC 3339.
     */
    suspend fun listEvents(
        accessToken: String,
        calendarId: String,
        timeMin: String,
        timeMax: String,
        query: String?,
        max: Int
    ): JsonArray {
        val url = "$baseUrl/calendars/$calendarId/events".toHttpUrl().newBuilder()
            .addQueryParameter("timeMin", timeMin)
            .addQueryParameter("timeMax", timeMax)
            .addQueryParameter("singleEvents", "true")
            .addQueryParameter("orderBy", "startTime")
            .addQueryParameter("maxResults", max.toString())
            .apply { if (!query.isNullOrBlank()) addQueryParameter("q", query) }
            .build()
        return get(url.toString(), accessToken).values()
    }

    /** `events.insert` — returns the new event id. */
    suspend fun insertEvent(accessToken: String, calendarId: String, event: JsonObject): String =
        post("$baseUrl/calendars/$calendarId/events", accessToken, event)["id"]
            ?.jsonPrimitive?.contentOrNull ?: "(unknown id)"

    /**
     * `freeBusy.query` — busy blocks per calendar. Returns the `calendars` object,
     * whose values each hold a `busy` array of `{start,end}` RFC 3339 pairs.
     */
    suspend fun freeBusy(
        accessToken: String,
        calendarIds: List<String>,
        timeMin: String,
        timeMax: String
    ): JsonObject {
        val body = buildJsonObject {
            put("timeMin", JsonPrimitive(timeMin))
            put("timeMax", JsonPrimitive(timeMax))
            put(
                "items",
                buildJsonArray {
                    calendarIds.forEach { id ->
                        add(buildJsonObject { put("id", JsonPrimitive(id)) })
                    }
                }
            )
        }
        return post("$baseUrl/freeBusy", accessToken, body)["calendars"]?.jsonObject
            ?: buildJsonObject {}
    }

    /** `calendarList.list` — every calendar the account can see, including unsynced ones. */
    suspend fun calendarList(accessToken: String): JsonArray =
        get("$baseUrl/users/me/calendarList", accessToken).values()

    private fun JsonObject.values(): JsonArray = this["items"]?.jsonArray ?: JsonArray(emptyList())

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
            401 -> "Google auth expired (401): $detail"
            403 ->
                "Google Calendar denied this request (403) — the Calendar scope was probably " +
                    "not granted. Reconnect Google in Settings → Connectors with Calendar " +
                    "ticked. $detail"
            429 -> "Google Calendar rate limit (429) — try again later. $detail"
            400 -> "Google Calendar rejected the request (400): $detail"
            else -> "Google Calendar error (HTTP $code): $detail"
        }
        return GmailApiException(code, message)
    }
}
