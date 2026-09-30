package com.gotcha.agent

import com.gotcha.data.isScreenCapture
import com.gotcha.llm.ChatMessage

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

    /** How much of the opening text names a chat until it has a title. */
    const val FALLBACK_CHARS = 30

    /** The name of a chat that opened with images alone, until it has a title. */
    const val IMAGE_CHAT = "Image chat"

    private const val NEW_CHAT = "New Chat"

    /**
     * The first thing the user wrote in the chat, trimmed, or null while every
     * message so far is attachments alone. An image-only message carries a
     * single space for the model (see [com.gotcha.llm.IMAGE_ONLY_TEXT]), and
     * the agent's own screen captures are user messages too; neither is
     * something to name a chat by.
     */
    fun openingText(messages: List<ChatMessage>): String? =
        messages.firstOrNull { it.role == "user" && it.textContent.isNotBlank() && !isScreenCapture(it.textContent) }
            ?.textContent
            ?.trim()

    /**
     * What a chat is called until it has a generated title: the start of its
     * [openingText], or [IMAGE_CHAT] when it opened with images alone. Never
     * blank, so the chat list and the top bar always have something to show.
     */
    fun fallback(messages: List<ChatMessage>): String =
        openingText(messages)?.take(FALLBACK_CHARS)
            ?: if (messages.any { it.role == "user" && it.hasImage }) IMAGE_CHAT else NEW_CHAT

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
