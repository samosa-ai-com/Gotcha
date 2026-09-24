package com.gotcha.ui

/**
 * The search index behind the field at the top of the settings home list.
 *
 * A page's own title and summary are only half of what people type: nobody
 * looks for "Assistive Ball and Wake Word" by its title — they type "wake
 * word", "api key" or "read aloud", naming a *control* somewhere inside a page.
 * [keywordsFor] carries those aliases, so the index answers for fields that have
 * no row of their own on the home list.
 *
 * Kept free of Compose so the matching is a plain unit test.
 */

/** One searchable settings page: the page itself plus the words that find it. */
data class SettingsSearchEntry(
    val page: SettingsPage,
    val keywords: List<String>
) {
    /**
     * How the result names itself. A page inside a hub says which hub it is in —
     * "AI › Speech (TTS / STT)" — because the home list never shows that row, so
     * the result is the only place the path can be learned.
     */
    val breadcrumb: String
        get() = page.parentPage()?.let { "${it.title} › ${page.title}" } ?: page.title

    /** Everything this entry matches against, lowercased once at construction. */
    private val haystack: String =
        (listOf(page.title, page.summary) + keywords).joinToString(" ").lowercase()

    internal fun matches(token: String): Boolean = haystack.contains(token)
}

/**
 * Keyword lists read as prose here — one comma-separated blob per page — because
 * a quoted-string list of forty aliases is a wall nobody rereads.
 */
private fun words(csv: String): List<String> =
    csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Aliases for the controls a page owns, in the words people actually type.
 *
 * Exhaustive on purpose: a new [SettingsPage] fails to compile here rather than
 * shipping a page that search can't find. An empty list is a fine answer when
 * the title and summary already say everything.
 */
@Suppress("CyclomaticComplexMethod")
private fun keywordsFor(page: SettingsPage): List<String> = when (page) {
    SettingsPage.PERSONAL_INFO -> words(
        """
        name, currency, reply style, tone, about me, profile
        """
    )
    SettingsPage.LANGUAGE -> words(
        """
        app language, voice language, reply language, translate, locale
        """
    )
    SettingsPage.AI -> words(
        """
        assistant, llm, brain, provider
        """
    )
    SettingsPage.AI_CONFIG -> words(
        """
        api key, base url, endpoint, model, provider, max tool rounds,
        agent limits, timeout, samosa sign in, sign in, login, credits, openai,
        temperature
        """
    )
    SettingsPage.SPEECH -> words(
        """
        tts, stt, voice, transcription, transcribe, read aloud, read replies,
        speak, microphone, kokoro, whisper
        """
    )
    SettingsPage.PERMISSIONS -> words(
        """
        accessibility, notification access, device admin, root, health connect,
        camera, location, contacts, sms, storage, display over other apps,
        overlay, grant, allow
        """
    )
    SettingsPage.TERMUX -> words(
        """
        linux, shell, command, terminal, ffmpeg, package, bash
        """
    )
    SettingsPage.SKILLS -> words(
        """
        plugin, skill, community, import, mcp
        """
    )
    SettingsPage.PROACTIVE -> words(
        """
        otp, screen scanning, offers, suggestions, clipboard, privacy
        """
    )
    SettingsPage.ASSISTIVE_BALL -> words(
        """
        wake word, hey gotcha, floating ball, bubble, overlay, hands free,
        hands-free, always listening, sensitivity
        """
    )
    SettingsPage.APPEARANCE -> words(
        """
        theme, dark mode, light mode, wallpaper, skin, font, colors
        """
    )
    SettingsPage.NOTIFICATIONS -> words(
        """
        alerts, sound, vibrate, server messages, announcements, sync,
        daily tip, tips, suggestions, ideas, reminder, reminders, unfinished, routine,
        inactivity, quiet hours, do not disturb, history, inbox, privacy
        """
    )
    SettingsPage.ABOUT -> words(
        """
        version, contact, support, company, samosa
        """
    )
    SettingsPage.ABOUT_SAMOSA -> words(
        """
        mission, products, pricing, developers, samosa ai
        """
    )
    SettingsPage.LEGAL -> words(
        """
        terms, privacy policy, disclaimer, data retention, licence, license
        """
    )
}

/** Every settings page, in home-list declaration order. */
val settingsSearchIndex: List<SettingsSearchEntry> =
    SettingsPage.entries.map { SettingsSearchEntry(it, keywordsFor(it)) }

/**
 * Pages matching [query], case-insensitively, over title, summary and keywords.
 *
 * Every whitespace-separated word of the query has to match somewhere in the
 * entry, so word order doesn't matter ("word wake" finds the ball) and each
 * extra word narrows rather than widens the result. A blank query matches
 * nothing: the caller shows the normal, unfiltered list instead.
 */
fun filterSettings(query: String): List<SettingsSearchEntry> {
    val tokens = query.lowercase().split(' ', '\t', '\n').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return emptyList()
    return settingsSearchIndex.filter { entry -> tokens.all { entry.matches(it) } }
}
