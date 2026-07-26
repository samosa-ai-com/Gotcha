package com.gotcha.agent

/**
 * Validation for LLM-generated chat titles.
 *
 * Weaker models routinely ignore the "write a title" system turn and answer the
 * quoted message instead, producing prose like "Sure! I've created the file
 * `notes.md`…". That string then becomes the chat name *and* the on-disk folder
 * name, so it is worth rejecting: the truncated-first-message fallback is a much
 * better name than a stray assistant reply.
 */
object ChatTitle {

    /** Generous vs. the 3-6 words asked for, so only prose gets rejected. */
    private const val MAX_CHARS = 60
    private const val MAX_WORDS = 10

    /** Openers that mark a reply-to-the-user rather than a title. */
    private val REPLY_OPENERS = listOf(
        "sure", "certainly", "of course", "okay", "ok,", "alright", "absolutely",
        "done", "got it", "no problem", "here's", "here is", "here are",
        "i've", "i have", "i'll", "i will", "i can", "i'm", "i am"
    )

    /** Returns a usable title, or null if [raw] doesn't look like one. */
    fun sanitize(raw: String?): String? {
        val firstLine = raw?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotBlank() }
            ?: return null
        val title = firstLine.trim('"', '\'', '.', ' ')
        if (title.isBlank() || title.length > MAX_CHARS) return null
        if (title.split(Regex("\\s+")).size > MAX_WORDS) return null
        if (REPLY_OPENERS.any { title.startsWith(it, ignoreCase = true) }) return null
        return title
    }
}
