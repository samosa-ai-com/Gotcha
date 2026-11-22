package com.gotcha.telemetry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
    val deviceClass: String,
    val installProfile: InstallProfile = InstallProfile()
)

data class InstallProfile(
    val source: String = "sideload",
    val oemSkin: String = "other",
    val ram: String = "unknown",
    val storage: String = "unknown",
    val screenDp: String = "unknown",
    val density: String = "unknown",
    val refresh: String = "unknown",
    val rooted: Boolean = false
)

sealed interface RegisterResult {
    data class Registered(
        val installId: String,
        val token: String,
        val region: String,
        val consentRequired: Boolean,
        val enabled: Boolean,
        val sample: Double?
    ) : RegisterResult
    data object Failed : RegisterResult
}

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
    private val enrollmentKey: String,
    private val client: OkHttpClient = defaultClient()
) {

    fun send(installId: String, token: String, app: AppInfo, events: List<QueuedEvent>, sentAt: Long): SendResult {
        val body = envelope(installId, app, events, sentAt).toString().toByteArray()
        val request = Request.Builder()
            .url(endpoint.trimEnd('/') + "/v1/events")
            .header("Authorization", "Bearer $token")
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

    fun register(app: AppInfo, installedAt: Long): RegisterResult {
        val body = buildJsonObject {
            put("product", TelemetrySchema.PRODUCT)
            put("schema", TelemetrySchema.VERSION)
            put("installed_at", installedAt)
            put(
                "app",
                buildJsonObject {
                    put("version", app.version)
                    put("code", app.code)
                    put("build", app.build)
                    put("install_source", app.installProfile.source)
                }
            )
            put(
                "device",
                buildJsonObject {
                    put("sdk", app.sdk)
                    put("release", android.os.Build.VERSION.RELEASE.orEmpty())
                    put("security_patch", android.os.Build.VERSION.SECURITY_PATCH.orEmpty())
                    put("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
                    put("manufacturer", android.os.Build.MANUFACTURER.orEmpty())
                    put("model", android.os.Build.MODEL.orEmpty())
                    put("oem_skin", app.installProfile.oemSkin)
                    put("device_class", app.deviceClass)
                    put("ram", app.installProfile.ram)
                    put("storage", app.installProfile.storage)
                    put("screen_dp", app.installProfile.screenDp)
                    put("density", app.installProfile.density)
                    put("refresh", app.installProfile.refresh)
                    put("emulator", android.os.Build.FINGERPRINT.orEmpty().contains("generic"))
                    put("rooted", app.installProfile.rooted)
                    put(
                        "timezone_offset",
                        java.time.ZoneId.systemDefault().rules
                            .getOffset(java.time.Instant.ofEpochMilli(installedAt)).id.let {
                                if (it == "Z") "+00:00" else it
                            }
                    )
                }
            )
            put("locale", app.locale)
        }
        val request = Request.Builder().url(endpoint.trimEnd('/') + "/v1/install")
            .header("Authorization", "Bearer $enrollmentKey")
            .post(body.toString().toRequestBody(JSON)).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code != 200 && response.code != 409) return RegisterResult.Failed
                val obj = Json.parseToJsonElement(response.body?.string().orEmpty()) as JsonObject
                val id = (obj["install_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val token = (obj["token"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val region = (obj["region"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (id.isBlank() || token.isBlank() || region !in setOf("eea", "uk", "other")) {
                    RegisterResult.Failed
                } else {
                    val config = obj["config"] as? JsonObject
                    RegisterResult.Registered(
                        id,
                        token,
                        region,
                        (obj["consent_required"] as? JsonPrimitive)?.booleanOrNull ?: true,
                        (config?.get("enabled") as? JsonPrimitive)?.booleanOrNull ?: true,
                        (config?.get("sample") as? JsonPrimitive)?.doubleOrNull
                    )
                }
            }
        } catch (_: Exception) {
            RegisterResult.Failed
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
