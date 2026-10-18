package com.gotcha.data

import android.content.SharedPreferences

/**
 * What the "Enjoying Gotcha?" ask remembers: whether it was answered, when it
 * last showed, and how often it was waved off. Kept in the settings prefs.
 */
class StarPromptStore(private val prefs: SharedPreferences) {

    fun answered(): Boolean = prefs.getBoolean(KEY_ANSWERED, false)

    fun lastShownAt(): Long = prefs.getLong(KEY_LAST_SHOWN, 0L)

    fun dismissals(): Int = prefs.getInt(KEY_DISMISSALS, 0)

    /** Recorded as the ask appears, so one the process died under still waits out its cooldown. */
    fun markShown(at: Long) {
        prefs.edit().putLong(KEY_LAST_SHOWN, at).apply()
    }

    fun markAnswered() {
        prefs.edit().putBoolean(KEY_ANSWERED, true).apply()
    }

    fun markDismissed() {
        prefs.edit().putInt(KEY_DISMISSALS, dismissals() + 1).apply()
    }

    companion object {
        const val KEY_ANSWERED = "star_prompt_answered"
        const val KEY_LAST_SHOWN = "star_prompt_last_shown_at"
        const val KEY_DISMISSALS = "star_prompt_dismissals"
    }
}
