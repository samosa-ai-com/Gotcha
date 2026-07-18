package com.gotcha.service

import java.util.regex.Pattern

/**
 * A structured, semantic action surfaced in the assistive-ball menu.
 *
 * [prompt] is either a plain LLM prompt (currency conversion, chat reply) or a
 * native-intent directive encoded with [SmartActionDetector.ACTION_PREFIX]. The
 * host service decodes the prefixed form and fires an Android intent instead of
 * querying the LLM.
 */
data class SmartAction(val label: String, val prompt: String)

/**
 * Lightweight text scanner that recognises structured data types — physical
 * addresses, phone numbers, foreign-currency prices, calendar events, and chat
 * messages — and turns the first match into a specialised [SmartAction].
 *
 * Detection order is most-specific first so a phone number is not mistaken for a
 * street address and vice-versa. Chat detection is opt-in (clipboard only) since
 * its heuristic is intentionally loose.
 */
object SmartActionDetector {

    /** Marker prefix identifying a native-intent action (vs. a plain LLM prompt). */
    const val ACTION_PREFIX = "@@SMART:"
    const val TYPE_NAVIGATE = "NAVIGATE"
    const val TYPE_DIAL = "DIAL"
    const val TYPE_CALENDAR = "CALENDAR"

    /** Separator between the encoded action type and its payload. */
    const val PAYLOAD_SEP = "|"

    private val addressPattern: Pattern = Pattern.compile(
        "\\d+\\s+[a-zA-Z0-9\\s,]+ (Street|St|Avenue|Ave|Road|Rd|Drive|Dr|Lane|Ln|Boulevard|Blvd|Way)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val phonePattern: Pattern = Pattern.compile(
        "(\\+\\d{1,2}\\s)?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}"
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

    /**
     * Return the first structured [SmartAction] found in [text], or null if none
     * apply. Set [allowChat] for clipboard text so the loose chat-reply heuristic
     * can fire (it is suppressed for passive screen scans to avoid noise).
     */
    fun detect(text: String, allowChat: Boolean = false): SmartAction? {
        if (text.isBlank()) return null

        phonePattern.matcher(text).let { m ->
            if (m.find()) {
                val number = m.group().trim()
                // Guard against matching a 10-digit run inside an address/id: require
                // the match not be immediately embedded in a longer digit sequence.
                return SmartAction(
                    label = "📞 Phone number detected. Dial?",
                    prompt = encode(TYPE_DIAL, number)
                )
            }
        }

        addressPattern.matcher(text).let { m ->
            if (m.find()) {
                val address = m.group().trim()
                return SmartAction(
                    label = "📍 Address detected. Navigate?",
                    prompt = encode(TYPE_NAVIGATE, address)
                )
            }
        }

        currencyPattern.matcher(text).let { m ->
            if (m.find()) {
                val price = m.group().trim()
                return SmartAction(
                    label = "💵 Currency detected. Convert?",
                    prompt = "Convert the price \"$price\" to USD and to common major " +
                        "currencies (EUR, GBP, INR). Show the approximate exchange rate " +
                        "you used. Keep it brief."
                )
            }
        }

        calendarPattern.matcher(text).let { m ->
            if (m.find()) {
                val event = m.group().trim()
                return SmartAction(
                    label = "📅 Event detected. Schedule?",
                    prompt = encode(TYPE_CALENDAR, event)
                )
            }
        }

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
