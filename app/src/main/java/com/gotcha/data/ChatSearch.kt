package com.gotcha.data

/** One chat the drawer search found: its title matched, or its text did. */
data class ChatSearchHit(
    val sessionId: String,
    /** The matching line of chat text, or null when only the title matched. */
    val snippet: String? = null
)

/**
 * The drawer's chat search: matches a query against each chat's title and the
 * text of its transcript. Pure and in memory, because the drawer already holds
 * every session it lists.
 *
 * Results keep the drawer's own newest-first order, so a familiar chat stays
 * where the user expects it while a query narrows the list.
 */
object ChatSearch {

    /** Characters of context kept either side of a match. */
    private const val SNIPPET_RADIUS = 48

    /** Longest snippet shown; longer matches are cut with an ellipsis. */
    private const val SNIPPET_MAX = 120

    /** [sessions] whose title or text contains [query], in the order given. */
    fun search(sessions: List<ChatSession>, query: String): List<ChatSearchHit> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return sessions.mapNotNull { session ->
            // A title match does not hide a text match: the row still shows the
            // line, which is what tells the user why this chat is here.
            val snippet = snippetIn(session, needle)
            if (snippet != null || session.title.contains(needle, ignoreCase = true)) {
                ChatSearchHit(session.id, snippet)
            } else {
                null
            }
        }
    }

    /**
     * The first line of [session]'s text containing [needle], shortened with
     * context around the match, or null when nothing matches.
     */
    fun snippetIn(session: ChatSession, needle: String): String? {
        for (text in sessionTexts(session)) {
            val at = text.indexOf(needle, ignoreCase = true)
            if (at >= 0) return shorten(text, at, needle.length)
        }
        return null
    }

    /**
     * What a chat can be found by: its visible transcript, or the LLM-shaped
     * history for chats saved before transcripts were kept. Blank lines are
     * skipped so a match never lands on whitespace.
     */
    private fun sessionTexts(session: ChatSession): Sequence<String> = sequence {
        if (session.displayMessages.isNotEmpty()) {
            session.displayMessages.forEach { message ->
                if (message.text.isNotBlank()) yield(message.text)
            }
        } else {
            session.messages.forEach { message ->
                val text = message.textContent
                if (text.isNotBlank()) yield(text)
            }
        }
    }

    private fun shorten(text: String, matchAt: Int, matchLength: Int): String {
        val start = charBoundary(text, (matchAt - SNIPPET_RADIUS).coerceAtLeast(0))
        val end = charBoundary(text, (matchAt + matchLength + SNIPPET_RADIUS).coerceAtMost(text.length))
        val cut = text.substring(start, end).replace(WHITESPACE, " ").trim()
        val shortened = if (cut.length > SNIPPET_MAX) cut.take(charBoundary(cut, SNIPPET_MAX)).trimEnd() + "…" else cut
        return when {
            start > 0 && end < text.length -> "…$shortened…"
            start > 0 -> "…$shortened"
            end < text.length -> "$shortened…"
            else -> shortened
        }
    }

    /**
     * [index] moved off the middle of a surrogate pair: cutting a pair in half
     * would render as a replacement character in the snippet.
     */
    private fun charBoundary(text: String, index: Int): Int = when {
        index <= 0 || index >= text.length -> index
        text[index - 1].isHighSurrogate() && text[index].isLowSurrogate() -> index - 1
        else -> index
    }

    /** Newlines and the Unicode spaces that `\s` does not always cover. */
    private val WHITESPACE = Regex("[\\s\\u00A0\\u2007\\u202F]+")
}
