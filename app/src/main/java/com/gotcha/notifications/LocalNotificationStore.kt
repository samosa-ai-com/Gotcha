package com.gotcha.notifications

import android.content.Context
import android.content.SharedPreferences
import com.gotcha.data.SafeEncryptedSharedPreferences
import com.gotcha.tools.AgentMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** One notification as the in-app inbox lists it (issue #100). */
@Serializable
data class InboxEntry(
    val id: String,
    val category: String,
    val title: String,
    val body: String,
    val postedAt: Long,
    val read: Boolean = false,
    /** Chat to open, for a [NotificationTarget.Chat]. */
    val sessionId: String? = null,
    /** Prompt and mode for a new chat, for a [NotificationTarget.Draft]. */
    val prompt: String? = null,
    val agentMode: String? = null,
    /** Link a server message carries. */
    val url: String? = null
) {
    val categoryOrNull: NotificationCategory?
        get() = NotificationCategory.entries.firstOrNull { it.name == category }

    val target: NotificationTarget
        get() = when {
            sessionId != null -> NotificationTarget.Chat(sessionId)
            prompt != null -> NotificationTarget.Draft(
                prompt,
                runCatching { AgentMode.valueOf(agentMode.orEmpty()) }.getOrDefault(AgentMode.MONITOR)
            )
            else -> NotificationTarget.Home
        }
}

/**
 * The on-device memory behind Gotcha's own notifications (issue #100): the
 * inbox, the log of proactive notifications that drives deduplication and the
 * daily cap, when Gotcha was last opened, and which chats are kept out of
 * notifications. Encrypted, like settings, because titles and prompts are
 * personal. Nothing here leaves the phone.
 */
class LocalNotificationStore internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(SafeEncryptedSharedPreferences.create(context, PREFS_FILE))

    private val json = Json { ignoreUnknownKeys = true }
    private val entrySerializer = ListSerializer(InboxEntry.serializer())
    private val sentSerializer = ListSerializer(SentRecord.serializer())

    // ---- inbox ----

    /** Newest first. */
    fun entries(): List<InboxEntry> = decode(KEY_ENTRIES, entrySerializer).sortedByDescending { it.postedAt }

    fun unreadCount(): Int = entries().count { !it.read }

    /** Adds a notification to the inbox and returns its id, which its tap intent carries. */
    fun addEntry(
        category: NotificationCategory,
        title: String,
        body: String,
        target: NotificationTarget,
        url: String? = null,
        now: Long = System.currentTimeMillis()
    ): String {
        val entry = InboxEntry(
            id = UUID.randomUUID().toString(),
            category = category.name,
            title = title,
            body = body,
            postedAt = now,
            sessionId = (target as? NotificationTarget.Chat)?.sessionId,
            prompt = (target as? NotificationTarget.Draft)?.prompt,
            agentMode = (target as? NotificationTarget.Draft)?.mode?.name,
            url = url
        )
        val kept = (listOf(entry) + entries())
            .filter { now - it.postedAt < KEEP_MS }
            .take(MAX_ENTRIES)
        encode(KEY_ENTRIES, entrySerializer, kept)
        return entry.id
    }

    fun markRead(id: String) {
        val all = entries()
        if (all.none { it.id == id && !it.read }) return
        encode(KEY_ENTRIES, entrySerializer, all.map { if (it.id == id) it.copy(read = true) else it })
    }

    fun markAllRead() {
        val all = entries()
        if (all.all { it.read }) return
        encode(KEY_ENTRIES, entrySerializer, all.map { it.copy(read = true) })
    }

    /**
     * Empties the inbox. The sent log is kept on purpose: clearing what you
     * have read must not bring back reminders that were already sent.
     */
    fun clearHistory() {
        prefs.edit().remove(KEY_ENTRIES).commit()
    }

    // ---- proactive notifications sent ----

    fun sent(): List<SentRecord> = decode(KEY_SENT, sentSerializer)

    fun recordSent(candidate: LocalCandidate, now: Long = System.currentTimeMillis()) {
        val kept = (sent() + SentRecord(candidate.dedupKey, candidate.category.name, now))
            .filter { now - it.at < KEEP_MS }
        encode(KEY_SENT, sentSerializer, kept)
    }

    // ---- when Gotcha was last opened ----

    fun lastOpenedAt(): Long = prefs.getLong(KEY_LAST_OPENED, 0L)

    fun setLastOpenedAt(value: Long) {
        prefs.edit().putLong(KEY_LAST_OPENED, value).apply()
    }

    // ---- chats kept out of notifications ----

    /** The user's own choice for [sessionId]: true kept out, false allowed, null not chosen. */
    fun chatChoice(sessionId: String): Boolean? = when (sessionId) {
        in stringSet(KEY_KEPT_OUT) -> true
        in stringSet(KEY_ALLOWED) -> false
        else -> null
    }

    fun isChatSensitive(sessionId: String, personaId: String?): Boolean =
        isChatSensitive(personaId, chatChoice(sessionId))

    fun setChatKeptOut(sessionId: String, keptOut: Boolean) {
        val out = stringSet(KEY_KEPT_OUT).toMutableSet()
        val allowed = stringSet(KEY_ALLOWED).toMutableSet()
        if (keptOut) {
            out += sessionId
            allowed -= sessionId
        } else {
            out -= sessionId
            allowed += sessionId
        }
        prefs.edit().putStringSet(KEY_KEPT_OUT, out).putStringSet(KEY_ALLOWED, allowed).apply()
    }

    /** Forgets a deleted chat's choice. */
    fun forgetChat(sessionId: String) {
        prefs.edit()
            .putStringSet(KEY_KEPT_OUT, stringSet(KEY_KEPT_OUT) - sessionId)
            .putStringSet(KEY_ALLOWED, stringSet(KEY_ALLOWED) - sessionId)
            .apply()
    }

    private fun stringSet(key: String): Set<String> = prefs.getStringSet(key, emptySet()).orEmpty().toSet()

    private fun <T> decode(key: String, serializer: kotlinx.serialization.KSerializer<List<T>>): List<T> =
        prefs.getString(key, null)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()

    private fun <T> encode(key: String, serializer: kotlinx.serialization.KSerializer<List<T>>, value: List<T>) {
        // commit, not apply: receivers write here just before their process may go.
        prefs.edit().putString(key, json.encodeToString(serializer, value)).commit()
    }

    companion object {
        private const val PREFS_FILE = "gotcha_local_notifications"
        private const val KEY_ENTRIES = "inbox_entries"
        private const val KEY_SENT = "sent_records"
        private const val KEY_LAST_OPENED = "last_opened_at"
        private const val KEY_KEPT_OUT = "chats_kept_out"
        private const val KEY_ALLOWED = "chats_allowed"
        private const val KEEP_MS = 30L * DAY_MS
        private const val MAX_ENTRIES = 100

        /** Carried by every tap intent Gotcha posts, so opening one marks its inbox entry read. */
        const val EXTRA_INBOX_ENTRY_ID = "com.gotcha.INBOX_ENTRY_ID"
    }
}
