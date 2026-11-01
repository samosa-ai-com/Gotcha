package com.gotcha.data

import android.content.Context

/**
 * One-time "you have seen this" flags for features introduced after the user's
 * first run (second brain, scheduled tasks).
 *
 * Deliberately not in [Settings]: these are not preferences, nothing outside
 * the home screen reads them, and a feature that has been shown must never
 * reappear just because the settings blob was reset. Plain prefs, because the
 * values are one bit and say nothing about the user.
 */
class FeatureIntroStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether the chip for [feature] has already been tapped once. */
    fun hasSeen(feature: String): Boolean = prefs.getBoolean(key(feature), false)

    /** Retires the chip for [feature]: it is not a nag, it is an introduction. */
    fun markSeen(feature: String) {
        prefs.edit().putBoolean(key(feature), true).apply()
    }

    private fun key(feature: String) = "seen_$feature"

    companion object {
        const val FILE = "feature_intro"

        /** The memory chip on the home screen. */
        const val MEMORY = "memory"

        /** The scheduled-tasks chip on the home screen. */
        const val SCHEDULE = "schedule"
    }
}
