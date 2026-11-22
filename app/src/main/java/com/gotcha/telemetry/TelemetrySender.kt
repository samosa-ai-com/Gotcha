package com.gotcha.telemetry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/** What the batch envelope says about this build and phone. Nothing in it names the user. */
data class AppInfo(
    val version: String,
    val code: Int,
    val build: String,
    val sdk: Int,
    val locale: String,
    val deviceClass: String
)

/** What one POST came to, as far as the queue and the switch are concerned. */
sealed interface SendResult {
    /** Accepted. [enabled] false is the server asking the app to stop sending for now. */
    data class Accepted(val enabled: Boolean, val sample: Double?) : SendResult

    /** The server will never take this batch (400, 413): drop it rather than retry forever. */
    data object Rejected : SendResult

    /** The ingest key was refused: stop until an app update brings a new one. */
    data object Unauthorized : SendResult

    /** Rate limited: try again after [retryAfterMs]. */
    data class Throttled(val retryAfterMs: Long) : SendResult

    /** Server error or no connection: keep the batch for the next flush. */
    data object Failed : SendResult
}

/**
 * Posts batches to the ingest endpoint.
 *
 * Its own [OkHttpClient], deliberately: the model client carries the Samosa
 * session token, and nothing that could tie usage data to an account may ride
 * along here. No body logging either.
 */
class TelemetrySender(
    private val endpoint: String,
    private val key: String,
    private val client: OkHttpClient = defaultClient()
) {

    fun send(installId: String, app: AppInfo, events: List<QueuedEvent>, sentAt: Long): SendResult {
        val body = envelope(installId, app, events, sentAt).toString().toByteArray()
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $key")
            .header("Content-Encoding", "gzip")
            .post(gzip(body).toRequestBody(JSON))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when (response.code) {
                    in 200..299 -> accepted(text)
                    HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> SendResult.Unauthorized
                    HTTP_TOO_MANY -> SendResult.Throttled(retryAfter(text) ?: DEFAULT_RETRY_MS)
                    in 400..499 -> SendResult.Rejected
                    else -> SendResult.Failed
                }
            }
        } catch (_: IOException) {
            SendResult.Failed
        }
    }

    /** Asks the server to delete everything sent under [installId]. True once it confirms. */
    fun delete(installId: String): Boolean {
        val request = Request.Builder()
            .url(endpoint.trimEnd('/') + "/" + installId)
            .header("Authorization", "Bearer $key")
            .delete()
            .build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: IOException) {
            false
        }
    }

    private fun accepted(text: String): SendResult.Accepted {
        val obj = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull()
        return SendResult.Accepted(
            enabled = (obj?.get("enabled") as? JsonPrimitive)?.booleanOrNull ?: true,
            sample = (obj?.get("sample") as? JsonPrimitive)?.doubleOrNull
        )
    }

    private fun retryAfter(text: String): Long? = runCatching {
        ((Json.parseToJsonElement(text) as JsonObject)["retry_after_ms"] as JsonPrimitive).longOrNull
    }.getOrNull()

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val TIMEOUT_SECONDS = 10L
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY = 429
        private const val DEFAULT_RETRY_MS = 15L * 60L * 1000L

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
            .build()

        internal fun envelope(installId: String, app: AppInfo, events: List<QueuedEvent>, sentAt: Long) =
            buildJsonObject {
                put("schema", TelemetrySchema.VERSION)
                put("product", TelemetrySchema.PRODUCT)
                put("install_id", installId)
                put("sent_at", sentAt)
                put(
                    "app",
                    buildJsonObject {
                        put("version", app.version)
                        put("code", app.code)
                        put("build", app.build)
                        put("sdk", app.sdk)
                        put("locale", app.locale)
                        put("device_class", app.deviceClass)
                    }
                )
                put("events", JsonArray(events.map { it.toJson() }))
            }

        private fun gzip(bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(bytes) }
            return out.toByteArray()
        }
    }
}
