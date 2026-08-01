package com.gotcha.notifications

import android.content.Context
import com.gotcha.data.SafeEncryptedSharedPreferences

/**
 * Local delivery log + ETag store for server-driven notifications.
 *
 * Backed by [SafeEncryptedSharedPreferences] so it survives the same KeyStore
 * failure modes as the settings store. The delivery log is a `Set<String>` of
 * message ids and a parallel `Map<String, Int>` of per-id counts — together
 * they answer the only question the dispatcher asks: "has this id been
 * delivered enough times?".
 */
class NotificationStore(context: Context) {

    private val prefs = SafeEncryptedSharedPreferences.create(context, PREFS_FILE)

    fun deliveredIds(): Set<String> = prefs.getStringSet(KEY_DELIVERED_IDS, emptySet())
        .orEmpty()
        .toSet()

    fun deliveryCount(id: String): Int = prefs.getInt("$KEY_DELIVERED_COUNT_PREFIX$id", 0)

    fun recordDelivery(id: String, at: Long = System.currentTimeMillis()) {
        val count = deliveryCount(id) + 1
        val updated = deliveredIds().toMutableSet().apply { add(id) }
        prefs.edit()
            .putStringSet(KEY_DELIVERED_IDS, updated)
            .putInt("$KEY_DELIVERED_COUNT_PREFIX$id", count)
            .putLong("$KEY_DELIVERED_LAST_PREFIX$id", at)
            .apply()
    }

    fun etag(): String = prefs.getString(KEY_ETAG, "").orEmpty()

    fun setEtag(value: String) {
        prefs.edit().putString(KEY_ETAG, value).apply()
    }

    fun lastFetchedAt(): Long = prefs.getLong(KEY_LAST_FETCHED, 0L)

    fun setLastFetchedAt(value: Long) {
        prefs.edit().putLong(KEY_LAST_FETCHED, value).apply()
    }

    /**
     * Drop delivery-log entries that are old enough that they can never
     * affect a future fetch (the server's `expires_at` is at most ~30 days
     * out, so anything older than that is dead weight).
     *
     * Counts are kept only for ids still in [deliveredIds]; this stops the
     * count map from leaking entries for messages no longer remembered.
     */
    fun prune(now: Long) {
        val cutoff = now - PRUNE_AFTER_MS
        val ids = deliveredIds()
        if (ids.isEmpty()) return
        val survivors = mutableSetOf<String>()
        val editor = prefs.edit()
        ids.forEach { id ->
            val lastDelivered = prefs.getLong("$KEY_DELIVERED_LAST_PREFIX$id", 0L)
            if (lastDelivered in 1L until cutoff) {
                editor.remove("$KEY_DELIVERED_COUNT_PREFIX$id")
                editor.remove("$KEY_DELIVERED_LAST_PREFIX$id")
            } else {
                survivors.add(id)
            }
        }
        if (survivors.size != ids.size) {
            // Write the survivor set once after the loop so partial writes
            // don't overwrite each other.
            editor.putStringSet(KEY_DELIVERED_IDS, survivors)
        }
        editor.apply()
    }

    private companion object {
        const val PREFS_FILE = "gotcha_notifications"
        const val KEY_DELIVERED_IDS = "delivered_ids"
        const val KEY_ETAG = "etag"
        const val KEY_LAST_FETCHED = "last_fetched"
        const val KEY_DELIVERED_COUNT_PREFIX = "count:"
        const val KEY_DELIVERED_LAST_PREFIX = "last:"
        const val PRUNE_AFTER_MS = 30L * 24L * 60L * 60L * 1000L
    }
}
