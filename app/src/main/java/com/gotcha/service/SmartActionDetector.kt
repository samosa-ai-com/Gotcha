package com.gotcha.service

import java.util.regex.Pattern

/**
 * High-level entity types recognized by [SmartActionDetector], ordered by default priority.
 */
enum class EntityType(val basePriority: Int) {
    OTP(100),
    PHONE(80),
    ADDRESS(80),
    EMAIL(70),
    URL(60),
    CALENDAR(40),
    CURRENCY(40),
    TRACKING_NUMBER(30),
    CHAT_REPLY(20),
    GENERIC_TEXT(10)
}

/**
 * Category of action attached to a [SmartAction].
 */
enum class ActionType {
    NATIVE_DIAL,
    NATIVE_SMS,
    NATIVE_NAVIGATE,
    NATIVE_BROWSE,
    NATIVE_COPY,
    NATIVE_SHARE,
    NATIVE_ADD_CONTACT,
    NATIVE_COMPOSE_MAIL,
    NATIVE_CALENDAR,
    NATIVE_WHATSAPP,
    LLM_SUMMARIZE,
    LLM_TRANSLATE,
    LLM_CONVERT_CURRENCY,
    LLM_CHAT_REPLY,
    LLM_GENERAL
}

/**
 * A structured, semantic action surfaced in the assistive-ball menu or the Lens action menu.
 */
data class SmartAction(
    val label: String,
    val prompt: String,
    val actionType: ActionType = ActionType.LLM_GENERAL,
    val isPrimary: Boolean = false
)

/**
 * A structured entity detected in screen text, clipboard, or notifications.
 */
data class DetectedEntity(
    val type: EntityType,
    val rawValue: String,
    val normalizedValue: String,
    val span: IntRange,
    val confidence: Float,
    val actions: List<SmartAction>,
    val timestamp: Long = System.currentTimeMillis()
) {
    /** The primary action to trigger when tapping the entity's main chip. */
    val primaryAction: SmartAction? get() = actions.firstOrNull { it.isPrimary } ?: actions.firstOrNull()
}

/**
 * Lightweight text scanner that recognises structured data types — physical
 * addresses, phone numbers, foreign-currency prices, calendar events, URLs, emails,
 * OTPs, and tracking numbers.
 */
@Suppress("TooManyFunctions", "LargeClass", "MaxLineLength", "ComplexCondition")
object SmartActionDetector {

    /** Marker prefix identifying a native-intent action (vs. a plain LLM prompt). */
    const val ACTION_PREFIX = "@@SMART:"
    const val TYPE_NAVIGATE = "NAVIGATE"
    const val TYPE_DIAL = "DIAL"
    const val TYPE_CALENDAR = "CALENDAR"
    const val TYPE_FETCH = "FETCH"
    const val TYPE_SMS = "SMS"
    const val TYPE_VIEW = "VIEW"
    const val TYPE_COPY = "COPY"
    const val TYPE_SHARE = "SHARE"
    const val TYPE_CONTACT = "CONTACT"
    const val TYPE_MAILTO = "MAILTO"
    const val TYPE_WHATSAPP = "WHATSAPP"

    /** Separator between the encoded action type and its payload. */
    const val PAYLOAD_SEP = "|"

    /**
     * Street addresses: supports single-line and multi-line house numbers, street names,
     * suffixes, optional unit/suite/building, and city/state/zip tails across line breaks.
     */
    private val addressPattern: Pattern = Pattern.compile(
        "\\b\\d{1,6}[\\s\\r\\n]+[A-Za-z0-9.'\\-]+(?:[\\s\\r\\n]+[A-Za-z0-9.'\\-]+){0,5}[\\s\\r\\n]+" +
            "(Street|St|Avenue|Ave|Road|Rd|Drive|Dr|Lane|Ln|Boulevard|Blvd|Way|" +
            "Court|Ct|Place|Pl|Terrace|Ter|Circle|Cir|Highway|Hwy|Parkway|Pkwy|" +
            "Square|Sq|Trail|Trl|Close|Crescent|Cres)\\b\\.?" +
            "(?:[\\s\\r\\n]*(?:#|Apt\\.?|Suite|Ste\\.?|Unit|Floor|Fl\\.?|Building|Bldg\\.?)\\s*\\w+)?" +
            "(?:[\\s\\r\\n]*,?[\\s\\r\\n]*[A-Za-z .'\\-]+(?:[\\s\\r\\n]*,?[\\s\\r\\n]*[A-Z]{2})?(?:[\\s\\r\\n]*\\d{5}(?:-\\d{4})?)?)?",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Phone numbers. Strict boundary checks to avoid bare digit order IDs.
     */
    private val phonePattern: Pattern = Pattern.compile(
        "(?<![\\d])(" +
            "\\+\\d{1,3}[\\s.-]?\\(?\\d{2,4}\\)?[\\s.-]?\\d{3,4}[\\s.-]?\\d{3,4}" +
            "|\\(\\d{3}\\)\\s?\\d{3}[\\s.-]?\\d{4}" +
            "|\\d{3}[\\s.-]\\d{3}[\\s.-]\\d{4}" +
            ")(?![\\d])"
    )

    private val emailPattern: Pattern = Pattern.compile(
        "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val currencyPattern: Pattern = Pattern.compile(
        "([¥€£$]|₹|INR|Rs\\.?)\\s?\\d{1,6}(\\.\\d{2})?\\b" +
            "|\\b\\d{1,6}(\\.\\d{2})?\\s?(USD|EUR|GBP|INR|JPY|CAD|AUD|₹|Rs\\.?)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val calendarPattern: Pattern = Pattern.compile(
        "\\b(meeting|appointment|event|call|lunch|dinner|reminder|deadline)\\b.{0,40}?" +
            "\\b(today|tomorrow|mon(day)?|tue(sday)?|wed(nesday)?|thu(rsday)?|fri(day)?|sat(urday)?|sun(day)?)\\b" +
            "|\\b(today|tomorrow|mon(day)?|tue(sday)?|wed(nesday)?|thu(rsday)?|fri(day)?|sat(urday)?|sun(day)?)\\b" +
            "\\s+(at\\s+)?\\d{1,2}(:\\d{2})?\\s?([ap])\\.?\\s?m\\.?" +
            "|\\b(jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|may|jun(e)?|jul(y)?|aug(ust)?|" +
            "sep(t)?(ember)?|oct(ober)?|nov(ember)?|dec(ember)?)\\s+\\d{1,2}(st|nd|rd|th)?" +
            "(,?\\s+\\d{4})?(\\s+(at\\s+)?\\d{1,2}(:\\d{2})?\\s?([ap])\\.?\\s?m\\.?)?" +
            "|\\b\\d{1,2}(:\\d{2})?\\s?([ap])\\.?\\s?m\\.?",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Enhanced URL pattern supporting scheme, bare domain/www, query params, ports, IP addresses.
     */
    private val urlPattern: Pattern = Pattern.compile(
        "(?:https?://|www\\.)[^\\s<>\"']+|\\b(?:[a-zA-Z0-9-]+\\.)+(?:com|org|net|io|dev|app|co|gov|edu|ai|in|uk|ca|au|de|fr|jp|cn|me|info|biz)(?::\\d{1,5})?(?:/[^\\s<>\"']*)?|\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?(?:/[^\\s<>\"']*)?",
        Pattern.CASE_INSENSITIVE
    )

    private val potentialCodePattern: Pattern = Pattern.compile("\\b([A-Za-z0-9]{4,8})\\b")
    private val otpKeywordCheckPattern: Pattern = Pattern.compile(
        "\\b(otp|code|verification|passcode|pin|security code|login code)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val trackingPattern: Pattern = Pattern.compile(
        "\\b(1Z[0-9A-Z]{16}|9400\\d{18})\\b"
    )

    private val reservedKeywords = setOf(
        "code", "otp", "pin", "your", "passcode", "login", "verification",
        "security", "is", "this", "here", "info", "with", "have"
    )

    /**
     * Scan text and return ALL detected entities, ranked and deduplicated.
     */
    fun detectAll(text: String, allowChat: Boolean = false): List<DetectedEntity> {
        if (text.isBlank()) return emptyList()

        val rawEntities = mutableListOf<DetectedEntity>()

        // 1. OTP
        detectOtps(text, rawEntities)

        // 2. Phone
        detectPhones(text, rawEntities)

        // 3. Address
        detectAddresses(text, rawEntities)

        // 4. Email
        detectEmails(text, rawEntities)

        // 5. URL
        detectUrls(text, rawEntities)

        // 6. Currency
        detectCurrencies(text, rawEntities)

        // 7. Calendar
        detectCalendars(text, rawEntities)

        // 8. Tracking numbers
        detectTracking(text, rawEntities)

        // 9. Chat reply fallback (if allowChat set)
        if (allowChat && looksLikeChatMessage(text)) {
            val normalized = text.trim()
            val actions = listOf(
                SmartAction(
                    label = "💬 Draft reply: ${snippet(normalized, 24)}",
                    prompt = "Draft a short, friendly reply to this message. Return only the reply text:\n\n$text",
                    actionType = ActionType.LLM_CHAT_REPLY,
                    isPrimary = true
                ),
                SmartAction(
                    label = "📋 Copy text",
                    prompt = encode(TYPE_COPY, normalized),
                    actionType = ActionType.NATIVE_COPY
                ),
                SmartAction(
                    label = "🌐 Translate",
                    prompt = "Translate the following text to English:\n\n$text",
                    actionType = ActionType.LLM_TRANSLATE
                )
            )
            rawEntities.add(
                DetectedEntity(
                    type = EntityType.CHAT_REPLY,
                    rawValue = normalized,
                    normalizedValue = normalized,
                    span = 0..text.length,
                    confidence = 0.8f,
                    actions = actions
                )
            )
        }

        // Deduplicate overlapping spans and sort by score
        return deduplicateAndRank(rawEntities)
    }

    /**
     * Proactive detection wrapper for backward compatibility.
     * Returns the primary action of the highest-ranked entity, or null.
     */
    fun detect(text: String, allowChat: Boolean = false): SmartAction? {
        val entities = detectAll(text, allowChat)
        // Proactive detect historically excluded currency & calendar unless in Lens mode
        val filtered = entities.filter { it.type != EntityType.CURRENCY && it.type != EntityType.CALENDAR }
        return filtered.firstOrNull()?.primaryAction
    }

    /**
     * Contextual detection wrapper for Lens mode.
     * Returns primary actions for all distinct entities found in the text.
     */
    fun detectContextual(text: String): List<SmartAction> {
        val entities = detectAll(text, allowChat = false)
        return entities.mapNotNull { it.primaryAction }
    }

    private fun detectOtps(text: String, out: MutableList<DetectedEntity>) {
        val m = potentialCodePattern.matcher(text)
        while (m.find()) {
            val code = m.group(1)?.trim() ?: continue
            if (code.lowercase() in reservedKeywords) continue
            // OTP candidate must contain digits or be all uppercase 4-8 chars
            val isDigitCode = code.any { it.isDigit() } && code.length >= 4
            val isUpperCode = code.all { it.isUpperCase() } && code.length >= 4
            if (isDigitCode || isUpperCode) {
                val startWindow = maxOf(0, m.start() - 40)
                val endWindow = minOf(text.length, m.end() + 40)
                val windowText = text.substring(startWindow, endWindow)
                if (otpKeywordCheckPattern.matcher(windowText).find()) {
                    val actions = listOf(
                        SmartAction(
                            label = "🔑 Copy code ${snippet(code, 10)}",
                            prompt = encode(TYPE_COPY, code),
                            actionType = ActionType.NATIVE_COPY,
                            isPrimary = true
                        ),
                        SmartAction(
                            label = "📤 Share code",
                            prompt = encode(TYPE_SHARE, code),
                            actionType = ActionType.NATIVE_SHARE
                        )
                    )
                    out.add(
                        DetectedEntity(
                            type = EntityType.OTP,
                            rawValue = code,
                            normalizedValue = code,
                            span = m.start()..m.end(),
                            confidence = 0.95f,
                            actions = actions
                        )
                    )
                }
            }
        }
    }

    private fun detectPhones(text: String, out: MutableList<DetectedEntity>) {
        val m = phonePattern.matcher(text)
        while (m.find()) {
            val raw = m.group().trim()
            val normalized = raw.replace(Regex("[^0-9+]"), "")
            val actions = listOf(
                SmartAction(
                    label = "📞 Dial ${snippet(raw, 20)}",
                    prompt = encode(TYPE_DIAL, raw),
                    actionType = ActionType.NATIVE_DIAL,
                    isPrimary = true
                ),
                SmartAction(
                    label = "💬 SMS",
                    prompt = encode(TYPE_SMS, raw),
                    actionType = ActionType.NATIVE_SMS
                ),
                SmartAction(
                    label = "💬 WhatsApp",
                    prompt = encode(TYPE_WHATSAPP, normalized),
                    actionType = ActionType.NATIVE_WHATSAPP
                ),
                SmartAction(
                    label = "👤 Save contact",
                    prompt = encode(TYPE_CONTACT, raw),
                    actionType = ActionType.NATIVE_ADD_CONTACT
                ),
                SmartAction(
                    label = "📋 Copy number",
                    prompt = encode(TYPE_COPY, raw),
                    actionType = ActionType.NATIVE_COPY
                )
            )
            out.add(
                DetectedEntity(
                    type = EntityType.PHONE,
                    rawValue = raw,
                    normalizedValue = normalized,
                    span = m.start()..m.end(),
                    confidence = 0.9f,
                    actions = actions
                )
            )
        }
    }

    private fun detectAddresses(text: String, out: MutableList<DetectedEntity>) {
        val m = addressPattern.matcher(text)
        while (m.find()) {
            val raw = m.group().trim().trimEnd(',')
            val normalized = raw.replace(Regex("[\\r\\n]+"), " ").replace(Regex("\\s+"), " ").trim()
            val actions = listOf(
                SmartAction(
                    label = "📍 Navigate: ${snippet(normalized, 24)}",
                    prompt = encode(TYPE_NAVIGATE, normalized),
                    actionType = ActionType.NATIVE_NAVIGATE,
                    isPrimary = true
                ),
                SmartAction(
                    label = "📋 Copy address",
                    prompt = encode(TYPE_COPY, normalized),
                    actionType = ActionType.NATIVE_COPY
                ),
                SmartAction(
                    label = "📤 Share address",
                    prompt = encode(TYPE_SHARE, normalized),
                    actionType = ActionType.NATIVE_SHARE
                )
            )
            out.add(
                DetectedEntity(
                    type = EntityType.ADDRESS,
                    rawValue = raw,
                    normalizedValue = normalized,
                    span = m.start()..m.end(),
                    confidence = 0.85f,
                    actions = actions
                )
            )
        }
    }

    private fun detectEmails(text: String, out: MutableList<DetectedEntity>) {
        val m = emailPattern.matcher(text)
        while (m.find()) {
            val email = m.group().trim()
            val actions = listOf(
                SmartAction(
                    label = "📧 Compose: ${snippet(email, 22)}",
                    prompt = encode(TYPE_MAILTO, email),
                    actionType = ActionType.NATIVE_COMPOSE_MAIL,
                    isPrimary = true
                ),
                SmartAction(
                    label = "📋 Copy email",
                    prompt = encode(TYPE_COPY, email),
                    actionType = ActionType.NATIVE_COPY
                ),
                SmartAction(
                    label = "📤 Share email",
                    prompt = encode(TYPE_SHARE, email),
                    actionType = ActionType.NATIVE_SHARE
                )
            )
            out.add(
                DetectedEntity(
                    type = EntityType.EMAIL,
                    rawValue = email,
                    normalizedValue = email.lowercase(),
                    span = m.start()..m.end(),
                    confidence = 0.95f,
                    actions = actions
                )
            )
        }
    }

    private fun detectUrls(text: String, out: MutableList<DetectedEntity>) {
        val m = urlPattern.matcher(text)
        while (m.find()) {
            val raw = m.group().trim()
            val cleanUrl = cleanUrl(raw) ?: continue
            val pretty = prettyUrl(cleanUrl)
            val actions = listOf(
                SmartAction(
                    label = "🌐 Open: ${snippet(pretty, 24)}",
                    prompt = encode(TYPE_VIEW, cleanUrl),
                    actionType = ActionType.NATIVE_BROWSE,
                    isPrimary = true
                ),
                SmartAction(
                    label = "📝 Summarize",
                    prompt = encode(TYPE_FETCH, cleanUrl),
                    actionType = ActionType.LLM_SUMMARIZE
                ),
                SmartAction(
                    label = "📋 Copy link",
                    prompt = encode(TYPE_COPY, cleanUrl),
                    actionType = ActionType.NATIVE_COPY
                ),
                SmartAction(
                    label = "📤 Share link",
                    prompt = encode(TYPE_SHARE, cleanUrl),
                    actionType = ActionType.NATIVE_SHARE
                )
            )
            out.add(
                DetectedEntity(
                    type = EntityType.URL,
                    rawValue = raw,
                    normalizedValue = cleanUrl,
                    span = m.start()..m.end(),
                    confidence = 0.9f,
                    actions = actions
                )
            )
        }
    }

    private fun detectCurrencies(text: String, out: MutableList<DetectedEntity>) {
        val m = currencyPattern.matcher(text)
        while (m.find()) {
            val price = m.group().trim()
            val actions = listOf(
                SmartAction(
                    label = "💵 Convert ${snippet(price, 16)}",
                    prompt = "Convert the price \"$price\" to USD and to common major currencies (EUR, GBP, INR). Show the exchange rate. Keep it brief.",
                    actionType = ActionType.LLM_CONVERT_CURRENCY,
                    isPrimary = true
                ),
                SmartAction(
                    label = "📋 Copy price",
                    prompt = encode(TYPE_COPY, price),
                    actionType = ActionType.NATIVE_COPY
                )
            )
            out.add(
                DetectedEntity(
                    type = EntityType.CURRENCY,
                    rawValue = price,
                    normalizedValue = price,
                    span = m.start()..m.end(),
                    confidence = 0.85f,
                    actions = actions
                )
            )
        }
    }

    private fun detectCalendars(text: String, out: MutableList<DetectedEntity>) {
        val m = calendarPattern.matcher(text)
        while (m.find()) {
            val event = m.group().trim()
            val actions = listOf(
                SmartAction(
                    label = "📅 Add to calendar: ${snippet(event, 24)}",
                    prompt = encode(TYPE_CALENDAR, event),
                    actionType = ActionType.NATIVE_CALENDAR,
                    isPrimary = true
                ),
                SmartAction(
                    label = "📋 Copy event",
                    prompt = encode(TYPE_COPY, event),
                    actionType = ActionType.NATIVE_COPY
                )
            )
            out.add(
                DetectedEntity(
                    type = EntityType.CALENDAR,
                    rawValue = event,
                    normalizedValue = event,
                    span = m.start()..m.end(),
                    confidence = 0.8f,
                    actions = actions
                )
            )
        }
    }

    private fun detectTracking(text: String, out: MutableList<DetectedEntity>) {
        val m = trackingPattern.matcher(text)
        while (m.find()) {
            val trackNum = m.group().trim()
            val carrier = if (trackNum.startsWith("1Z")) "UPS" else "USPS"
            val trackingUrl = if (carrier == "UPS") {
                "https://www.ups.com/track?tracknum=$trackNum"
            } else {
                "https://tools.usps.com/go/TrackConfirmAction?tRef=fullpage&tLc=2&text28777=&tLabels=$trackNum"
            }
            val actions = listOf(
                SmartAction(
                    label = "📦 Track $carrier: ${snippet(trackNum, 16)}",
                    prompt = encode(TYPE_VIEW, trackingUrl),
                    actionType = ActionType.NATIVE_BROWSE,
                    isPrimary = true
                ),
                SmartAction(
                    label = "📋 Copy tracking number",
                    prompt = encode(TYPE_COPY, trackNum),
                    actionType = ActionType.NATIVE_COPY
                )
            )
            out.add(
                DetectedEntity(
                    type = EntityType.TRACKING_NUMBER,
                    rawValue = trackNum,
                    normalizedValue = trackNum,
                    span = m.start()..m.end(),
                    confidence = 0.9f,
                    actions = actions
                )
            )
        }
    }

    private fun cleanUrl(raw: String): String? {
        var clean = raw.trimEnd('.', ',', ')', ']', '}', '>', '"', '\'', ';', ':', '!', '?')
        if (clean.isBlank()) return null
        if (!clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
            clean = "https://$clean"
        }
        return if (clean.length > 8) clean else null
    }

    private fun deduplicateAndRank(entities: List<DetectedEntity>): List<DetectedEntity> {
        val sorted = entities.sortedWith(
            compareByDescending<DetectedEntity> { calculateScore(it) }
                .thenByDescending { it.rawValue.length }
        )
        val result = mutableListOf<DetectedEntity>()
        for (entity in sorted) {
            val overlaps = result.any { existing ->
                spansOverlap(entity.span, existing.span) && existing.normalizedValue == entity.normalizedValue
            }
            if (!overlaps) {
                result.add(entity)
            }
        }
        return result
    }

    private fun spansOverlap(span1: IntRange, span2: IntRange): Boolean =
        span1.first <= span2.last && span2.first <= span1.last

    private fun calculateScore(entity: DetectedEntity): Int =
        entity.type.basePriority + (entity.confidence * 10).toInt()

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
        SmartAction(
            label = "🔗 Summarize: ${snippet(prettyUrl(url), 30)}",
            prompt = encode(TYPE_FETCH, url.trim()),
            actionType = ActionType.LLM_SUMMARIZE,
            isPrimary = true
        )

    /** Strip scheme/`www.` for a friendlier label. */
    private fun prettyUrl(url: String): String =
        url.trim()
            .replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
            .removePrefix("www.")

    /**
     * A short preview of detected data for action labels.
     */
    fun snippet(value: String, max: Int = 24): String {
        val clean = value.replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= max) clean else clean.take(max).trimEnd() + "…"
    }

    /**
     * Extract the first http(s) URL from [text], normalising a bare `www.`/domain match to an https:// URL.
     */
    fun extractUrl(text: String): String? {
        val m = urlPattern.matcher(text)
        if (!m.find()) return null
        return cleanUrl(m.group())
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
