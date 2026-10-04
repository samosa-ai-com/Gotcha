package com.gotcha.ui

import androidx.annotation.StringRes
import com.gotcha.R
import com.gotcha.i18n.StringLookup

/**
 * The search index behind the field at the top of the settings home list.
 *
 * A page's own title and summary are only half of what people type: nobody
 * looks for "Assistive Ball and Wake Word" by its title — they type "wake
 * word", "api key" or "read aloud", naming a *control* somewhere inside a page.
 * [entryFor] carries those aliases, so the index answers for fields that have
 * no row of their own on the home list — and, where the alias names one field,
 * says which, so the page can open on it rather than at the top.
 *
 * Kept free of Compose so the matching is a plain unit test. Titles, summaries
 * and field labels are string resources, read through a [StringLookup], so a
 * query matches them in the app's display language; the aliases stay English,
 * which still finds a page for someone who types the English name.
 */

/**
 * One control a search can land on, not just the page it sits in.
 *
 * [testTag] is the control's tag on its page: the page scrolls it into view and
 * tints it briefly (see [settingsHighlight]). [section] is the tag of the
 * [SettingsAdvancedSection] it is folded inside, if any — the section has to
 * open before the field exists to scroll to.
 */
data class SettingsField(
    val testTag: String,
    @StringRes val label: Int,
    val keywords: List<String> = emptyList(),
    val section: String? = null
) {
    internal fun matches(token: String, text: StringLookup): Boolean =
        (listOf(text(label)) + keywords).joinToString(" ").lowercase().contains(token)
}

/**
 * One searchable settings page: the page itself, the words that find it as a
 * whole, and the [fields] inside it that a query can point at more precisely.
 */
data class SettingsSearchEntry(
    val page: SettingsPage,
    val keywords: List<String>,
    val fields: List<SettingsField> = emptyList()
) {
    /**
     * How the result names itself. A page inside a hub says which hub it is in —
     * "AI › Speech (TTS / STT)" — because the home list never shows that row, so
     * the result is the only place the path can be learned.
     */
    fun breadcrumb(text: StringLookup): String =
        page.parentPage()?.let { "${text(it.title)} › ${text(page.title)}" } ?: text(page.title)

    /** Everything this entry matches against, lowercased. */
    private fun haystack(text: StringLookup): String =
        (
            listOf(text(page.title), text(page.summary)) + keywords +
                fields.flatMap { listOf(text(it.label)) + it.keywords }
            )
            .joinToString(" ")
            .lowercase()

    internal fun matches(tokens: List<String>, text: StringLookup): Boolean {
        val haystack = haystack(text)
        return tokens.all { haystack.contains(it) }
    }

    /**
     * The field [tokens] name, or null when they name the page itself.
     *
     * A query spelling out the page's whole title asks for the page, so it
     * highlights nothing even when a field would also match ("language" is
     * not a request for the App language row). Part of a title is fair game:
     * "wake word" is half of "Assistive Ball and Wake Word" and still means the
     * switch. Otherwise the first field matching every token wins, in page
     * order; words spread across the page's aliases and a field find the page
     * but no one field.
     */
    internal fun fieldFor(tokens: List<String>, text: StringLookup): SettingsField? {
        if (wordsOf(tokens.joinToString(" ")).containsAll(wordsOf(text(page.title)))) return null
        return fields.firstOrNull { field -> tokens.all { field.matches(it, text) } }
    }
}

/** Lowercase words of [text], punctuation dropped: "Speech (TTS / STT)" → speech, tts, stt. */
private fun wordsOf(text: String): Set<String> =
    text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()

/** A page a query found, and the control inside it the query named, if any. */
data class SettingsSearchResult(
    val entry: SettingsSearchEntry,
    val field: SettingsField?
) {
    val page: SettingsPage get() = entry.page

    /** The breadcrumb, carried one step further down when a field matched. */
    fun label(text: StringLookup): String =
        this.field?.let { "${entry.breadcrumb(text)} › ${text(it.label)}" } ?: entry.breadcrumb(text)
}

/**
 * Keyword lists read as prose here — one comma-separated blob each — because
 * a quoted-string list of forty aliases is a wall nobody rereads.
 */
private fun words(csv: String): List<String> =
    csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** Short form for the index below: tag, label, then the aliases as prose. */
private fun field(testTag: String, @StringRes label: Int, csv: String = "", section: String? = null) =
    SettingsField(testTag, label, words(csv), section)

/**
 * Aliases for a page and for the controls it owns, in the words people actually
 * type. A word that names one control belongs to that control's [field], so a
 * result can open the page on it; what names the page as a whole ("privacy",
 * "assistant") stays with the page.
 *
 * Exhaustive on purpose: a new [SettingsPage] fails to compile here rather than
 * shipping a page that search can't find. No aliases and no fields is a fine
 * answer when the title and summary already say everything.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun entryFor(page: SettingsPage): SettingsSearchEntry = when (page) {
    SettingsPage.PERSONAL_INFO -> SettingsSearchEntry(
        page,
        words("about me, profile"),
        listOf(
            field("settings_user_name", R.string.settings_field_user_name),
            field("settings_user_response_style", R.string.settings_field_user_response_style, "tone"),
            field("settings_user_currency", R.string.settings_field_user_currency)
        )
    )
    SettingsPage.LANGUAGE -> SettingsSearchEntry(
        page,
        words("translate, locale"),
        listOf(
            field("settings_open_app_locale", R.string.settings_field_open_app_locale),
            field("settings_voice_language", R.string.settings_field_voice_language),
            field(
                "settings_stt_language",
                R.string.settings_field_stt_language,
                "stt language, speech to text language, transcription override"
            ),
            field("settings_reply_language", R.string.settings_field_reply_language)
        )
    )
    SettingsPage.AI -> SettingsSearchEntry(
        page,
        words("assistant, llm, brain, provider")
    )
    SettingsPage.AI_CONFIG -> SettingsSearchEntry(
        page,
        words("samosa sign in, sign in, login, credits, temperature"),
        listOf(
            field("settings_llm_provider", R.string.settings_field_llm_provider, "provider, openai, samosa"),
            field("settings_api_key", R.string.settings_field_api_key),
            field("settings_base_url", R.string.settings_field_base_url, "endpoint"),
            field("settings_model", R.string.settings_field_model, "model"),
            field(
                "settings_max_tool_rounds",
                R.string.settings_field_max_tool_rounds,
                "agent limits",
                section = AI_ADVANCED_SECTION
            ),
            field(
                "settings_api_timeout",
                R.string.settings_field_api_timeout,
                "timeout",
                section = AI_ADVANCED_SECTION
            )
        )
    )
    SettingsPage.SPEECH -> SettingsSearchEntry(
        page,
        words("voice, speak, microphone, kokoro, whisper"),
        listOf(
            field("settings_tts_provider", R.string.settings_field_tts_provider, "tts, text to speech"),
            field(
                "settings_stt_provider",
                R.string.settings_field_stt_provider,
                "stt, transcription, transcribe, speech to text"
            ),
            field("settings_auto_read_replies", R.string.settings_field_auto_read_replies, "read aloud, read replies")
        )
    )
    SettingsPage.PERMISSIONS -> SettingsSearchEntry(
        page,
        words(
            """
            accessibility, notification access, device admin, root, health connect,
            camera, location, contacts, sms, storage, display over other apps,
            overlay, grant, allow
            """
        )
    )
    SettingsPage.TERMUX -> SettingsSearchEntry(
        page,
        words("linux, shell, command, terminal, ffmpeg, package, bash")
    )
    SettingsPage.SKILLS -> SettingsSearchEntry(
        page,
        words("plugin, skill, community, import, mcp")
    )
    SettingsPage.PROACTIVE -> SettingsSearchEntry(
        page,
        words("privacy"),
        listOf(
            field("settings_proactive_enabled", R.string.settings_field_proactive_enabled, "offers, suggestions"),
            field("settings_proactive_scan_screen", R.string.settings_field_proactive_scan_screen, "screen scanning"),
            field("settings_proactive_scan_clipboard", R.string.settings_field_proactive_scan_clipboard, "clipboard"),
            field("settings_proactive_otp", R.string.settings_field_proactive_otp, "otp")
        )
    )
    SettingsPage.ASSISTIVE_BALL -> SettingsSearchEntry(
        page,
        words("overlay, hands free, hands-free"),
        listOf(
            field("settings_assistive_ball", R.string.settings_field_assistive_ball, "floating ball, bubble"),
            field(
                "settings_pause_night_light",
                R.string.settings_field_pause_night_light,
                "night mode, blue light, yellow tint, eye comfort, screenshot colours, screenshot colors"
            ),
            field("settings_wake_word", R.string.settings_field_wake_word, "always listening"),
            field("settings_wake_word_sensitivity", R.string.settings_field_wake_word_sensitivity, "sensitivity")
        )
    )
    SettingsPage.APPEARANCE -> SettingsSearchEntry(
        page,
        words("theme, dark mode, light mode, wallpaper, skin, font, colors")
    )
    SettingsPage.NOTIFICATIONS -> SettingsSearchEntry(
        page,
        words(
            """
            alerts, daily tip, tips, suggestions, ideas, reminder, reminders, unfinished,
            routine, inactivity, quiet hours, do not disturb, history, inbox, privacy
            """
        ),
        listOf(
            field("settings_notify_vibration", R.string.settings_field_notify_vibration, "vibrate"),
            field("settings_notify_chime", R.string.settings_field_notify_chime, "sound"),
            field("settings_task_finished_enabled", R.string.settings_field_task_finished_enabled),
            field(
                "settings_server_messages_enabled",
                R.string.settings_field_server_messages_enabled,
                "announcements, sync"
            )
        )
    )
    SettingsPage.ABOUT -> SettingsSearchEntry(
        page,
        words("version, contact, support, company, samosa")
    )
    SettingsPage.ABOUT_SAMOSA -> SettingsSearchEntry(
        page,
        words("mission, products, pricing, developers, samosa ai")
    )
    SettingsPage.LEGAL -> SettingsSearchEntry(
        page,
        words("terms, privacy policy, disclaimer, data retention, licence, license")
    )
}

/** The tag of AI Configuration's collapsed "Advanced settings" section. */
internal const val AI_ADVANCED_SECTION = "settings_ai_advanced"

/** Every settings page, in home-list declaration order. */
val settingsSearchIndex: List<SettingsSearchEntry> = SettingsPage.entries.map(::entryFor)

/** The field tagged [testTag] on [page], or null if the index has none. */
fun settingsFieldFor(page: SettingsPage, testTag: String): SettingsField? =
    settingsSearchIndex.first { it.page == page }.fields.firstOrNull { it.testTag == testTag }

/**
 * Pages matching [query], case-insensitively, over title, summary and keywords.
 *
 * Every whitespace-separated word of the query has to match somewhere in the
 * entry, so word order doesn't matter ("word wake" finds the ball) and each
 * extra word narrows rather than widens the result. A blank query matches
 * nothing: the caller shows the normal, unfiltered list instead.
 */
fun filterSettings(query: String, text: StringLookup): List<SettingsSearchResult> {
    val tokens = query.lowercase().split(' ', '\t', '\n').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return emptyList()
    return settingsSearchIndex
        .filter { entry -> entry.matches(tokens, text) }
        .map { entry -> SettingsSearchResult(entry, entry.fieldFor(tokens, text)) }
}
