package com.gotcha.notifications

import android.util.Log
import com.gotcha.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * HTTP client for `GET <SAMOSA_API_URL>/v1/gotcha/notifications`.
 *
 * Mirrors the [com.gotcha.audio.AudioApi] pattern (OkHttp directly, no
 * Retrofit). Surfaces a 304 via [NotificationsApiResult.NotModified] so the
 * dispatcher can short-circuit without re-parsing. A 401 fires the existing
 * Samosa session-invalidation path — v1 doesn't expect it (the public route
 * is unauthenticated) but the wiring is in place for a future signed-in feed.
 *
 * [fetch] is `open` so tests can subclass with a stub implementation and
 * skip the HTTP client entirely.
 */
open class NotificationApi(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val bearerToken: String = "",
    private val onUnauthorized: () -> Unit = {},
    private val timeoutSeconds: Long = 15L
) {
    private val client: OkHttpClient
    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    init {
        val logging = HttpLoggingInterceptor().apply {
            // The request URL is the configured endpoint — not for release logcat.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        client = OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds * 2, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                if (bearerToken.isNotBlank()) {
                    request.addHeader("Authorization", "Bearer $bearerToken")
                }
                chain.proceed(request.build())
            }
            .addInterceptor(logging)
            .build()
    }

    open suspend fun fetch(ifNoneMatch: String?): NotificationsApiResult = withContext(Dispatchers.IO) {
        fetchBlocking(ifNoneMatch)
    }

    /** Blocking fetch — must be called off the main thread (e.g. inside
     *  [fetch], which switches to [Dispatchers.IO]). Open for tests that want
     *  to skip the dispatcher hop. */
    internal open fun fetchBlocking(ifNoneMatch: String?): NotificationsApiResult {
        val url = "${baseUrl.trimEnd('/')}/v1/gotcha/notifications"
        val request = Request.Builder().url(url).get().apply {
            if (!ifNoneMatch.isNullOrBlank()) {
                addHeader("If-None-Match", "\"$ifNoneMatch\"")
            }
        }.build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 304 -> NotificationsApiResult.NotModified
                    response.code == 401 -> {
                        onUnauthorized()
                        NotificationsApiResult.Error(401)
                    }
                    !response.isSuccessful -> NotificationsApiResult.Error(response.code)
                    else -> {
                        val body = response.body?.string().orEmpty()
                        val etag = response.header("ETag")?.trim('"') ?: ""
                        parse(body, etag)
                    }
                }
            }
        } catch (e: Exception) {
            try { Log.w(TAG, "Notifications fetch failed", e) } catch (_: Throwable) {}
            NotificationsApiResult.NetworkError
        }
    }

    private fun parse(body: String, etag: String): NotificationsApiResult {
        return try {
            val obj = jsonParser.parseToJsonElement(body).jsonObject
            val messagesArray = obj["messages"]?.jsonArray ?: return NotificationsApiResult.Parsed(
                NotificationsEnvelope(messages = emptyList(), etag = etag)
            )
            // Deserialize via kotlinx.serialization for shape fidelity; fall back to
            // empty envelope if a single message is malformed so one bad entry
            // doesn't sink the whole feed.
            val messages = messagesArray.mapNotNull { element ->
                runCatching {
                    jsonParser.decodeFromJsonElement(
                        NotificationMessage.serializer(),
                        element
                    )
                }.getOrNull()
            }
            val envelopeEtag = obj["etag"]?.jsonPrimitive?.content ?: etag
            NotificationsApiResult.Parsed(
                NotificationsEnvelope(messages = messages, etag = envelopeEtag)
            )
        } catch (e: Exception) {
            try { Log.w(TAG, "Notifications parse failed", e) } catch (_: Throwable) {}
            NotificationsApiResult.Error(-1)
        }
    }

    companion object {
        private const val TAG = "NotificationApi"

        /** OpenAI-compatible proxy exposed by the Samosa AI backend; reused as the
         *  notifications host so a single network identity covers LLM/audio/notifications. */
        val DEFAULT_BASE_URL: String = BuildConfig.SAMOSA_API_URL
    }
}

sealed interface NotificationsApiResult {
    data class Parsed(val envelope: NotificationsEnvelope) : NotificationsApiResult
    data object NotModified : NotificationsApiResult
    data class Error(val httpStatus: Int) : NotificationsApiResult
    data object NetworkError : NotificationsApiResult
}
