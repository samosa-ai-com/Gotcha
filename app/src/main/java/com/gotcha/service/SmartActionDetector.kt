package com.gotcha.service

import java.util.regex.Pattern

/**
 * A structured, semantic action surfaced in the assistive-ball menu or the Lens
 * action menu.
 *
 * [prompt] is either a plain LLM prompt (currency conversion, chat reply) or a
 * native-intent directive encoded with [SmartActionDetector.ACTION_PREFIX]. The
 * host service decodes the prefixed form and fires an Android intent instead of
 * querying the LLM.
 */
data class SmartAction(val label: String, val prompt: String)

/**
 * Lightweight text scanner that recognises structured data types — physical
 * addresses, phone numbers, foreign-currency prices, and calendar events — and
 * turns them into specialised [SmartAction]s.
 *
 * Two entry points with deliberately different aggressiveness:
 *  - [detect] powers *proactive* offers (passive screen scans + clipboard). It is
 *    conservative: only phone, address, and (clipboard-only) chat, and it returns
 *    at most one action. Currency and calendar are intentionally excluded here
 *    because a screen often shows several prices/dates and we cannot know which
 *    one the user means — that ambiguity is resolved by the user in Lens mode.
 *  - [detectContextual] powers the *Lens* menu, where the user has already framed
 *    a specific region, so ambiguity is gone. It returns every distinct action
 *    found in the selection (currency, calendar, phone, address).
 */
object SmartActionDetector {

    /** Marker prefix identifying a native-intent action (vs. a plain LLM prompt). */
    const val ACTION_PREFIX = "@@SMART:"
    const val TYPE_NAVIGATE = "NAVIGATE"
    const val TYPE_DIAL = "DIAL"
    const val TYPE_CALENDAR = "CALENDAR"

    /**
     * Fetch a URL's content and summarize it. Payload is the URL. Unlike the other
     * types this does not fire an OS intent — the host fetches the page and hands
     * the text to the LLM. See [AssistiveBallService.handleFetchAndSummarize].
     */
    const val TYPE_FETCH = "FETCH"

    /** Separator between the encoded action type and its payload. */
    const val PAYLOAD_SEP = "|"

    private val addressPattern: Pattern = Pattern.compile(
        "\\d+\\s+[a-zA-Z0-9\\s,]+ (Street|St|Avenue|Ave|Road|Rd|Drive|Dr|Lane|Ln|Boulevard|Blvd|Way)\\b",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Phone numbers. Deliberately strict to avoid firing on bare digit runs like
     * order IDs or tracking numbers. A match requires ONE of:
     *  - a `+` country code (separators then optional), or
     *  - a parenthesised area code `(415) 555-2671`, or
     *  - explicit separators between the 3-3-4 groups `415-555-2671`.
     * A plain `4155552671` (no separators, no parens, no `+`) does NOT match.
     * Digit-boundary guards stop it matching inside a longer number.
     */
    private val phonePattern: Pattern = Pattern.compile(
        "(?<![\\d])(" +
            "\\+\\d{1,3}[\\s.-]?\\(?\\d{2,4}\\)?[\\s.-]?\\d{3,4}[\\s.-]?\\d{3,4}" +
            "|\\(\\d{3}\\)\\s?\\d{3}[\\s.-]?\\d{4}" +
            "|\\d{3}[\\s.-]\\d{3}[\\s.-]\\d{4}" +
            ")(?![\\d])"
    )

    private val currencyPattern: Pattern = Pattern.compile(
        "([¥€£$])\\s?\\d{1,5}(\\.\\d{2})?"
    )

    private val calendarPattern: Pattern = Pattern.compile(
        "\\b(meeting|appointment|event|call|lunch|dinner)\\b.{0,40}?" +
            "\\b(today|tomorrow|mon(day)?|tue(sday)?|wed(nesday)?|thu(rsday)?|fri(day)?|sat(urday)?|sun(day)?)\\b" +
            "|\\b(today|tomorrow|mon(day)?|tue(sday)?|wed(nesday)?|thu(rsday)?|fri(day)?|sat(urday)?|sun(day)?)\\b" +
            "\\s+at\\s+\\d{1,2}(:\\d{2})?\\s?(am|pm)",
        Pattern.CASE_INSENSITIVE
    )

    /** First http(s):// URL or bare www./domain in a block of text. */
    private val urlPattern: Pattern = Pattern.compile(
        "(https?://[^\\s]+" +
            "|www\\.[a-zA-Z0-9-]+\\.[a-zA-Z]{2,}[^\\s]*" +
            "|[a-zA-Z0-9-]+\\.(com|org|net|io|dev|app|co|gov|edu|ai)(/[^\\s]*)?)",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Proactive detection for passive scans / clipboard. Conservative: returns at
     * most one action, and never offers currency or calendar (too ambiguous when
     * a screen shows several). Set [allowChat] for clipboard text so the loose
     * chat-reply heuristic can fire.
     */
    fun detect(text: String, allowChat: Boolean = false): SmartAction? {
        if (text.isBlank()) return null

        detectPhone(text)?.let { return it }
        detectAddress(text)?.let { return it }

        if (allowChat && looksLikeChatMessage(text)) {
            return SmartAction(
                label = "💬 Message copied. Draft reply?",
                prompt = "Draft a short, friendly reply to this message. Return only the " +
                    "reply text:\n\n$text"
            )
        }

        return null
    }

    /**
     * Contextual detection for Lens mode. The user has framed a specific region,
     * so every distinct action found is offered (order: currency, calendar, phone,
     * address). Returns an empty list when nothing structured is present.
     */
    fun detectContextual(text: String): List<SmartAction> {
        if (text.isBlank()) return emptyList()
        val actions = mutableListOf<SmartAction>()
        detectCurrency(text)?.let { actions.add(it) }
        detectCalendar(text)?.let { actions.add(it) }
        detectPhone(text)?.let { actions.add(it) }
        detectAddress(text)?.let { actions.add(it) }
        return actions
    }

    private fun detectPhone(text: String): SmartAction? {
        val m = phonePattern.matcher(text)
        if (!m.find()) return null
        val number = m.group().trim()
        return SmartAction(
            label = "📞 Dial $number?",
            prompt = encode(TYPE_DIAL, number)
        )
    }

    private fun detectAddress(text: String): SmartAction? {
        val m = addressPattern.matcher(text)
        if (!m.find()) return null
        val address = m.group().trim()
        return SmartAction(
            label = "📍 Navigate to address?",
            prompt = encode(TYPE_NAVIGATE, address)
        )
    }

    private fun detectCurrency(text: String): SmartAction? {
        val m = currencyPattern.matcher(text)
        if (!m.find()) return null
        val price = m.group().trim()
        return SmartAction(
            label = "💵 Convert $price?",
            prompt = "Convert the price \"$price\" to USD and to common major " +
                "currencies (EUR, GBP, INR). Show the approximate exchange rate " +
                "you used. Keep it brief."
        )
    }

    private fun detectCalendar(text: String): SmartAction? {
        val m = calendarPattern.matcher(text)
        if (!m.find()) return null
        val event = m.group().trim()
        return SmartAction(
            label = "📅 Add \"$event\" to calendar?",
            prompt = encode(TYPE_CALENDAR, event)
        )
    }

    /**
     * Loose heuristic for a copied chat bubble: short-ish, conversational text
     * that either carries a "Sender: message" prefix or reads like a question/
     * direct address. Kept conservative so it does not fire on arbitrary copies.
     */
    private fun looksLikeChatMessage(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length !in 2..300) return false
        val hasSpeakerPrefix = Regex("^[A-Za-z][\\w .]{0,24}:\\s+\\S").containsMatchIn(trimmed)
        val looksConversational = trimmed.endsWith("?") ||
            Regex("\\b(hey|hi|hello|thanks|please|can you|are you|you free|lmk|wyd)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(trimmed)
        return hasSpeakerPrefix || looksConversational
    }

    private fun encode(type: String, payload: String): String =
        "$ACTION_PREFIX$type$PAYLOAD_SEP$payload"

    /** Build a native "fetch this URL and summarize" action. */
    fun fetchAction(url: String): SmartAction =
        SmartAction(label = "🔗 Summarize link?", prompt = encode(TYPE_FETCH, url.trim()))

    /**
     * Extract the first http(s) URL from [text], normalising a bare `www.`/domain
     * match to an https:// URL. Returns null when no plausible URL is present.
     */
    fun extractUrl(text: String): String? {
        val m = urlPattern.matcher(text)
        if (!m.find()) return null
        val raw = m.group().trim().trimEnd('.', ',', ')', ']', '"', '\'')
        return when {
            raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true) -> raw
            else -> "https://$raw"
        }
    }

    /** True when [prompt] encodes a native intent (vs. a plain LLM prompt). */
    fun isNativeAction(prompt: String): Boolean = prompt.startsWith(ACTION_PREFIX)

    /** Decode a native-action prompt into (type, payload), or null if not one. */
    fun decode(prompt: String): Pair<String, String>? {
        if (!isNativeAction(prompt)) return null
        val body = prompt.removePrefix(ACTION_PREFIX)
        val sep = body.indexOf(PAYLOAD_SEP)
        if (sep < 0) return null
        return body.substring(0, sep) to body.substring(sep + PAYLOAD_SEP.length)
    }
}
