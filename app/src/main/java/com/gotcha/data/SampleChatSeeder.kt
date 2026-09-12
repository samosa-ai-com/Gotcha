package com.gotcha.data

import android.content.SharedPreferences
import android.util.Log

/**
 * Puts [SampleChats] on disk the first time Gotcha runs with an empty chat list.
 *
 * Seeding happens at most once per install, and only into a list that has
 * nothing in it: an existing user upgrading into this version keeps the chat
 * list they had, and a user who deletes the samples never sees them come back.
 * Both of those are the same rule — the flag is written whether or not anything
 * was actually seeded, so "have we done this?" is answered once and for all.
 */
internal object SampleChatSeeder {

    const val SEEDED_KEY = "seeded_sample_chats_v1"

    /**
     * Returns true when samples were written. Best-effort: a failure here must
     * not stop the app from opening, so it is logged and the flag left unset,
     * which lets the next launch try again against a still-empty list.
     */
    suspend fun seedIfNeeded(repository: ChatHistoryRepository, prefs: SharedPreferences): Boolean {
        if (prefs.getBoolean(SEEDED_KEY, false)) return false
        return try {
            val seed = repository.listSessions().isEmpty()
            if (seed) {
                // touch = false: the crafted timestamps are what order the two
                // samples in the drawer, and a save-time stamp would flatten them.
                SampleChats.all().forEach { repository.saveSession(it, touch = false) }
            }
            prefs.edit().putBoolean(SEEDED_KEY, true).apply()
            seed
        } catch (e: Exception) {
            Log.w("Gotcha", "seedIfNeeded: failed to seed sample chats", e)
            false
        }
    }
}
