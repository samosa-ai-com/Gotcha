package com.gotcha.connectors.microsoft

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Non-2xx Microsoft Graph response, with a user-actionable message. */
class GraphApiException(val code: Int, message: String) : IOException(message)

/**
 * Thin OkHttp wrapper over Microsoft Graph v1.0 (mail, calendar, To Do).
 * Stateless: every call takes the Bearer token so [MicrosoftConnector] owns
 * refresh/retry. JVM-testable via a MockWebServer [baseUrl].
 */
@Suppress("TooManyFunctions") // one function per Graph endpoint; splitting by service would not help
class GraphApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = "https://graph.microsoft.com/v1.0"
) {

    companion object {
        private const val MAIL_LIST_SELECT = "id,subject,from,receivedDateTime,isRead,bodyPreview"
        private const val MAIL_READ_SELECT =
            "id,subject,from,toRecipients,ccRecipients,receivedDateTime,body"
        private const val TODO_TASK_SELECT = "id,title,status,dueDateTime,body"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ---- Identity ----

    /** `GET /me` — the connected account's address. */
    suspend fun me(accessToken: String): String {
        val obj = get("$baseUrl/me?\$select=mail,userPrincipalName,displayName", accessToken)
        return obj.str("mail") ?: obj.str("userPrincipalName") ?: "(unknown)"
    }

    // ---- Mail ----

    /**
     * `GET /me/messages`. Graph rejects `$search` combined with `$filter`/`$orderby`,
     * so a text query switches to search mode and unread filtering is applied by the
     * caller instead.
     */
    suspend fun listMessages(
        accessToken: String,
        query: String?,
        unreadOnly: Boolean,
        max: Int
    ): JsonArray {
        val searching = !query.isNullOrBlank()
        val url = "$baseUrl/me/messages".toHttpUrl().newBuilder()
            .addQueryParameter("\$select", MAIL_LIST_SELECT)
            .addQueryParameter("\$top", max.toString())
            .apply {
                if (searching) {
                    addQueryParameter("\$search", "\"${query!!.trim()}\"")
                } else {
                    addQueryParameter("\$orderby", "receivedDateTime desc")
                    if (unreadOnly) addQueryParameter("\$filter", "isRead eq false")
                }
            }
            .build()
        return get(url.toString(), accessToken).values()
    }

    /** `GET /me/messages/{id}` — headers plus the full body. */
    suspend fun getMessage(accessToken: String, id: String): JsonObject =
        get("$baseUrl/me/messages/$id?\$select=$MAIL_READ_SELECT", accessToken)

    /** `POST /me/sendMail` — sends immediately and files a copy in Sent Items. */
    suspend fun sendMail(accessToken: String, message: JsonObject) {
        val body = buildJsonObject {
            put("message", message)
            put("saveToSentItems", JsonPrimitive(true))
        }
        post("$baseUrl/me/sendMail", accessToken, body)
    }

    /** `PATCH /me/messages/{id}` — flips the read flag. */
    suspend fun setRead(accessToken: String, id: String, read: Boolean) {
        val body = buildJsonObject { put("isRead", JsonPrimitive(read)) }
        patch("$baseUrl/me/messages/$id", accessToken, body)
    }

    // ---- Calendar ----

    /** `GET /me/calendarView` — expands recurrences within the window. ISO-8601 UTC bounds. */
    suspend fun calendarView(
        accessToken: String,
        startIso: String,
        endIso: String,
        max: Int
    ): JsonArray {
        val url = "$baseUrl/me/calendarView".toHttpUrl().newBuilder()
            .addQueryParameter("startDateTime", startIso)
            .addQueryParameter("endDateTime", endIso)
            .addQueryParameter("\$select", "id,subject,start,end,location,isAllDay,organizer,attendees")
            .addQueryParameter("\$orderby", "start/dateTime")
            .addQueryParameter("\$top", max.toString())
            .build()
        return get(url.toString(), accessToken, preferUtc = true).values()
    }

    /** `POST /me/events` — returns the new event id. */
    suspend fun createEvent(accessToken: String, event: JsonObject): String =
        post("$baseUrl/me/events", accessToken, event).str("id") ?: "(unknown id)"

    /** `POST /me/calendar/getSchedule` — busy blocks for the given addresses. */
    suspend fun getSchedule(
        accessToken: String,
        schedules: List<String>,
        startIso: String,
        endIso: String,
        intervalMinutes: Int
    ): JsonArray {
        val body = buildJsonObject {
            put("schedules", JsonArray(schedules.map { JsonPrimitive(it) }))
            put("startTime", dateTimeZone(startIso))
            put("endTime", dateTimeZone(endIso))
            put("availabilityViewInterval", JsonPrimitive(intervalMinutes))
        }
        return post("$baseUrl/me/calendar/getSchedule", accessToken, body).values()
    }

    // ---- To Do ----

    /** `GET /me/todo/lists` — the account's task lists. */
    suspend fun todoLists(accessToken: String): JsonArray =
        get("$baseUrl/me/todo/lists", accessToken).values()

    /** `GET /me/todo/lists/{listId}/tasks`, optionally hiding completed items. */
    suspend fun todoTasks(
        accessToken: String,
        listId: String,
        includeCompleted: Boolean,
        max: Int
    ): JsonArray {
        val url = "$baseUrl/me/todo/lists/$listId/tasks".toHttpUrl().newBuilder()
            .addQueryParameter("\$select", TODO_TASK_SELECT)
            .addQueryParameter("\$top", max.toString())
            .apply { if (!includeCompleted) addQueryParameter("\$filter", "status ne 'completed'") }
            .build()
        return get(url.toString(), accessToken).values()
    }

    /** `POST /me/todo/lists/{listId}/tasks` — returns the new task id. */
    suspend fun createTodoTask(accessToken: String, listId: String, task: JsonObject): String =
        post("$baseUrl/me/todo/lists/$listId/tasks", accessToken, task).str("id") ?: "(unknown id)"

    /** `PATCH /me/todo/lists/{listId}/tasks/{taskId}` — marks completed or reopens. */
    suspend fun setTodoTaskCompleted(
        accessToken: String,
        listId: String,
        taskId: String,
        completed: Boolean
    ) {
        val body = buildJsonObject {
            put(
                "status",
                JsonPrimitive(if (completed) "completed" else "notStarted")
            )
        }
        patch("$baseUrl/me/todo/lists/$listId/tasks/$taskId", accessToken, body)
    }

    // ---- Plumbing ----

    private fun dateTimeZone(iso: String): JsonObject = buildJsonObject {
        put("dateTime", JsonPrimitive(iso.removeSuffix("Z")))
        put("timeZone", JsonPrimitive("UTC"))
    }

    /** Graph collection responses wrap their rows in `value`. */
    private fun JsonObject.values(): JsonArray = this["value"]?.jsonArray ?: JsonArray(emptyList())

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private suspend fun get(
        url: String,
        accessToken: String,
        preferUtc: Boolean = false
    ): JsonObject = execute(
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .apply { if (preferUtc) header("Prefer", "outlook.timezone=\"UTC\"") }
            .build()
    )

    private suspend fun post(url: String, accessToken: String, body: JsonObject): JsonObject =
        execute(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()
        )

    private suspend fun patch(url: String, accessToken: String, body: JsonObject): JsonObject =
        execute(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
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

    private fun mapError(code: Int, body: String): GraphApiException {
        val detail = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]
                ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: body.take(200)
        val message = when (code) {
            401 -> "Microsoft auth expired (401): $detail"
            403 ->
                "Microsoft Graph denied this request (403) — the permission was probably not " +
                    "granted. Reconnect in Settings → Connectors. $detail"
            429, 503 -> "Microsoft Graph is throttling requests (HTTP $code) — try again later. $detail"
            400 -> "Microsoft Graph rejected the request (400): $detail"
            else -> "Microsoft Graph error (HTTP $code): $detail"
        }
        return GraphApiException(code, message)
    }
}
