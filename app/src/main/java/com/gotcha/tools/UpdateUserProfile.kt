package com.gotcha.tools

import com.gotcha.data.Settings

/** New profile facts the agent recorded in `update_user_profile`. Null = field not touched. */
data class ProfileUpdate(
    val occupation: String? = null,
    val background: String? = null,
    val replyStyle: String? = null
)

/** Which fields actually changed, plus the settings to persist. Null when nothing changed. */
data class ProfileUpdateResult(
    val updated: Settings,
    val changedFields: List<String>
)

/** Word caps for the free-form profile fields, keeping the system prompt compact. */
const val BACKGROUND_MAX_WORDS = 250
const val REPLY_STYLE_MAX_WORDS = 50

private val WHITESPACE = Regex("\\s+")

/** Collapses runs of whitespace to single spaces and trims. */
internal fun normalizeProfileText(text: String): String =
    text.replace(WHITESPACE, " ").trim()

/** Word count of a normalized profile value (runs of whitespace already collapsed). */
internal fun profileWordCount(text: String): Int =
    if (text.isBlank()) 0 else text.split(" ").size

/** Hard cap: keeps the first [maxWords] words. A safety net, not the primary constraint. */
internal fun truncateProfileWords(text: String, maxWords: Int): String {
    val words = text.split(" ")
    if (words.size <= maxWords) return text
    return words.take(maxWords).joinToString(" ")
}

/**
 * Merges an agent-initiated profile update onto [current].
 *
 * Semantics:
 * - `occupation` is a single-line title: provided + non-blank replaces it.
 * - `background` and `reply_style` are modify-and-extend free text: the model passes the
 *   complete merged value; this applies whitespace normalization, enforces the word caps,
 *   and only persists when the value materially differs (the low-frequency guard).
 * - Blank values never erase stored information.
 *
 * Returns null when nothing material changed, so callers can skip the write.
 */
fun mergeProfileUpdate(current: Settings, update: ProfileUpdate): ProfileUpdateResult? {
    var occupation = current.userOccupation
    var background = current.userBackground
    var replyStyle = current.userResponseStyle
    val changed = mutableListOf<String>()

    update.occupation?.let { proposed ->
        val normalized = normalizeProfileText(proposed)
        if (normalized.isNotEmpty() && normalized != occupation) {
            occupation = normalized
            changed += "occupation"
        }
    }
    update.background?.let { proposed ->
        val normalized = truncateProfileWords(normalizeProfileText(proposed), BACKGROUND_MAX_WORDS)
        if (normalized.isNotEmpty() && normalized != background) {
            background = normalized
            changed += "background"
        }
    }
    update.replyStyle?.let { proposed ->
        val normalized = truncateProfileWords(normalizeProfileText(proposed), REPLY_STYLE_MAX_WORDS)
        if (normalized.isNotEmpty() && normalized != replyStyle) {
            replyStyle = normalized
            changed += "reply_style"
        }
    }

    if (changed.isEmpty()) return null
    return ProfileUpdateResult(
        updated = current.copy(
            userOccupation = occupation,
            userBackground = background,
            userResponseStyle = replyStyle
        ),
        changedFields = changed
    )
}
