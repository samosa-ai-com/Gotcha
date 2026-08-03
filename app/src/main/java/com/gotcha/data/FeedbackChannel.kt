package com.gotcha.data

import android.content.Context
import android.os.Build
import com.gotcha.BuildConfig
import com.gotcha.auth.SamosaAuthApi
import com.gotcha.llm.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pre-fill values for the feedback form. Each nullable field maps to one
 * form entry; null omits the field, so the consent sheet's toggles control
 * exactly what leaves the device.
 */
data class FeedbackPrefill(
    val appVersion: String? = null,
    val deviceModel: String? = null,
    val androidVersion: String? = null,
    val userId: String? = null,
    val usageStats: String? = null,
    val chatLog: String? = null
)

/**
 * Builds the pre-filled Google Forms URL and resolves the feedback metadata.
 *
 * The form URL and its `entry.*` keys are injected at build time via the
 * `FEEDBACK_*` environment variables or `local.properties` (both gitignored) and
 * are never committed — a blank [BuildConfig.FEEDBACK_FORM_URL] keeps a public
 * checkout inert. The values drift if the form is edited, so they live entirely
 * in configuration rather than in this source.
 */
object FeedbackChannel {

    /** Whether a form is configured. Blank form URL disables the feedback row. */
    fun isConfigured(): Boolean = BuildConfig.FEEDBACK_FORM_URL.isNotBlank()

    /** App version / device model / Android version metadata. Needs no permission. */
    fun deviceMetadata(context: Context): FeedbackPrefill = FeedbackPrefill(
        appVersion = versionName(context),
        deviceModel = "${Build.MODEL} (${Build.MANUFACTURER})",
        androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    )

    private fun versionName(context: Context): String? =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Builds the pre-filled form URL. [formUrl] and [entries] default to the
     * build-time configuration but are parameterized so unit tests can exercise
     * the URL building without depending on local config.
     */
    fun buildFeedbackUrl(
        prefill: FeedbackPrefill,
        formUrl: String = BuildConfig.FEEDBACK_FORM_URL,
        entries: FeedbackEntries = configuredEntries()
    ): String {
        val base = formUrl.trimEnd('/')
        if (base.isBlank()) return ""

        val params = mutableListOf<String>()
        fun put(key: String, value: String?) {
            if (key.isNotBlank() && !value.isNullOrBlank()) {
                params += "$key=${encodeQueryValue(value)}"
            }
        }
        put(entries.appVersion, prefill.appVersion)
        put(entries.deviceModel, prefill.deviceModel)
        put(entries.androidVersion, prefill.androidVersion)
        put(entries.userId, prefill.userId)
        put(entries.usageStats, prefill.usageStats)
        put(entries.chatLog, prefill.chatLog)

        return if (params.isEmpty()) base else "$base?usp=pp_url&${params.joinToString("&")}"
    }

    /**
     * Percent-encodes a value for a query string, leaving only the RFC 3986
     * unreserved characters intact (so spaces become %20, newlines %0A, etc.).
     * Kept pure JVM so the URL builder is unit-testable without Robolectric.
     */
    private fun encodeQueryValue(value: String): String {
        val hex = "0123456789ABCDEF"
        return buildString {
            for (b in value.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                val unreserved = c in 'a'.code..'z'.code ||
                    c in 'A'.code..'Z'.code ||
                    c in '0'.code..'9'.code ||
                    c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
                if (unreserved) {
                    append(c.toChar())
                } else {
                    append('%')
                    append(hex[c shr 4])
                    append(hex[c and 0x0F])
                }
            }
        }
    }

    fun configuredEntries(): FeedbackEntries = FeedbackEntries(
        userId = BuildConfig.FEEDBACK_ENTRY_USER_ID,
        appVersion = BuildConfig.FEEDBACK_ENTRY_APP_VERSION,
        deviceModel = BuildConfig.FEEDBACK_ENTRY_DEVICE_MODEL,
        androidVersion = BuildConfig.FEEDBACK_ENTRY_ANDROID_VERSION,
        usageStats = BuildConfig.FEEDBACK_ENTRY_USAGE_STATS,
        chatLog = BuildConfig.FEEDBACK_ENTRY_CHAT_LOG
    )

    /**
     * Resolves the "User ID" pre-fill: the Samosa account id from `GET /me`
     * when signed in, falling back to the account email, else null so the
     * caller can substitute the anonymous install id. Never throws — a network
     * hiccup must not block the feedback flow.
     */
    suspend fun resolveSamosaUserId(
        token: String,
        fallbackEmail: String,
        api: SamosaAuthApi = SamosaAuthApi.create()
    ): String? {
        if (token.isBlank()) return null
        val id = try {
            api.me("Bearer $token").id
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return id?.takeIf { it.isNotBlank() } ?: fallbackEmail.takeIf { it.isNotBlank() }
    }

    /** Most recent messages, role-labelled, capped at [maxChars]. */
    fun chatLogExcerpt(messages: List<ChatMessage>, maxChars: Int = DEFAULT_EXCERPT_CHARS): String {
        val full = messages.joinToString("\n") { message ->
            val text = message.textContent.trim()
            if (text.isBlank()) {
                ""
            } else {
                val label = when (message.role) {
                    "user" -> "User"
                    "assistant" -> "Assistant"
                    else -> message.role
                }
                "$label: $text"
            }
        }.trim()
        if (full.length <= maxChars) return full
        val marker = "\n… truncated …\n"
        val head = (maxChars - marker.length) * 3 / 4
        val tail = maxChars - marker.length - head
        return full.take(head) + marker + full.takeLast(tail)
    }

    /** Excerpt from the most recently modified chat session, or null if none. */
    suspend fun recentChatExcerpt(context: Context, maxChars: Int = DEFAULT_EXCERPT_CHARS): String? =
        withContext(Dispatchers.IO) {
            val newest = ChatHistoryRepository(context).listSessions().firstOrNull()
            newest?.let { chatLogExcerpt(it.messages, maxChars) }
        }

    /** The form's `entry.<id>` query keys, one per pre-filled field. */
    data class FeedbackEntries(
        val userId: String,
        val appVersion: String,
        val deviceModel: String,
        val androidVersion: String,
        val usageStats: String,
        val chatLog: String
    )

    const val DEFAULT_EXCERPT_CHARS = 3500
}
