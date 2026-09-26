package com.gotcha.tools

import com.gotcha.data.CompletionPreview
import com.gotcha.data.Settings
import com.gotcha.data.WakeWordListeningMode
import com.gotcha.i18n.Language
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * The settings `update_gotcha_settings` may change (issue #99), and the
 * validation that stands between the model and [com.gotcha.data.SettingsRepository].
 *
 * An explicit allowlist, not a key-value passthrough: every key here maps to
 * one [Settings] field with its own parser. Credentials (API keys, the Samosa
 * session), the model and provider, endpoints, run limits the model could use
 * to raise its own budget, community skill hosts and the legal/onboarding
 * state are deliberately absent — a model that could write those could undo
 * the safety model or leak a secret. The user profile has its own tool.
 *
 * Kept free of Android and of the registries so the whole rule set is unit
 * testable: the caller hands in the skins, connectors and skills that exist.
 */

/** What a setting change touches, shown on the confirmation so the user can weigh it. */
enum class SettingImpact(val label: String) {
    PRIVACY("privacy"),
    NOTIFICATIONS("notifications"),
    PERMISSIONS("permissions"),
    DEVICE_CONTROL("device control")
}

/** The ids that exist on this install, each mapped to the name the user knows it by. */
data class SettingsCatalog(
    val skins: Map<String, String>,
    val connectors: Map<String, String>,
    val skills: Map<String, String>
)

/** One validated change. [apply] writes it; the old value is read with [describe]. */
class SettingChange internal constructor(
    val key: String,
    val label: String,
    val impacts: Set<SettingImpact>,
    private val read: (Settings) -> String,
    private val write: (Settings) -> Settings,
    /** The requested value exactly as sent, so the change can be carried and re-validated. */
    val rawValue: JsonElement
) {
    fun describe(settings: Settings): String = read(settings)
    fun apply(settings: Settings): Settings = write(settings)
}

/** A change set against a known [current] state, ready to show and to apply. */
data class SettingsChangePlan(
    val changes: List<SettingChange>,
    val current: Settings
) {
    val impacts: Set<SettingImpact> get() = changes.flatMapTo(sortedSetOf<SettingImpact>()) { it.impacts }

    /** Applies every change onto [base] — the freshly loaded settings at write time. */
    fun applyTo(base: Settings): Settings = changes.fold(base) { s, change -> change.apply(s) }

    /** "Label: old → new" for each change, in request order. */
    fun lines(): List<String> {
        val requested = applyTo(current)
        return changes.map { "${it.label}: ${it.describe(current)} → ${it.describe(requested)}" }
    }
}

/** Why a request was refused. Nothing is written when this comes back. */
class SettingsUpdateRejected(val reasons: List<String>) :
    Exception(reasons.joinToString("; "))

private fun onOff(value: Boolean) = if (value) "On" else "Off"

/** A top-level key: its label, what it touches, and how to parse, read and write it. */
// One row of the allowlist; every field is used by the parser, the prompt or the schema.
@Suppress("LongParameterList")
private class Spec(
    val key: String,
    val label: String,
    val impacts: Set<SettingImpact>,
    val accepts: String,
    /** JSON Schema type, and the allowed strings for a choice — for the tool schema. */
    val jsonType: String,
    val choices: List<String>? = null,
    val parse: (JsonPrimitive) -> Any?,
    val read: (Settings) -> String,
    val write: (Settings, Any) -> Settings
)

private fun boolSpec(
    key: String,
    label: String,
    impacts: Set<SettingImpact>,
    get: (Settings) -> Boolean,
    set: (Settings, Boolean) -> Settings
) = Spec(
    key,
    label,
    impacts,
    "true or false",
    "boolean",
    parse = { if (it.isString) null else it.booleanOrNull },
    read = { onOff(get(it)) },
    write = { s, v -> set(s, v as Boolean) }
)

private fun <T> choiceSpec(
    key: String,
    label: String,
    impacts: Set<SettingImpact>,
    choices: Map<String, T>,
    display: (T) -> String,
    get: (Settings) -> T,
    set: (Settings, T) -> Settings
) = Spec(
    key, label, impacts, "one of " + choices.keys.joinToString(", "), "string", choices.keys.toList(),
    parse = { p ->
        if (!p.isString) {
            null
        } else {
            choices.entries.firstOrNull { it.key.equals(p.content.trim(), ignoreCase = true) }?.value
        }
    },
    read = { display(get(it)) },
    write = { s, v ->
        @Suppress("UNCHECKED_CAST")
        set(s, v as T)
    }
)

/** Parses `update_gotcha_settings` arguments against the allowlist. */
object GotchaSettingsUpdate {

    const val CONNECTORS_KEY = "connectors"
    const val SKILLS_KEY = "skills"

    private val languages: Map<String, String> = Language.entries.associate { it.label to it.label }

    /** `voice_language` also takes this to mean "speak in the reply language" (stored blank). */
    const val FOLLOW_REPLY_LANGUAGE = "same_as_replies"

    private fun specs(catalog: SettingsCatalog): List<Spec> =
        notificationSpecs() + localNotificationSpecs() + voiceSpecs() + appearanceSpecs(catalog) + proactiveSpecs() + wakeWordSpecs()

    private fun notificationSpecs(): List<Spec> = listOf(
        boolSpec(
            "reply_vibration",
            "Vibrate when a reply arrives",
            setOf(SettingImpact.NOTIFICATIONS),
            { it.notifyVibrationEnabled },
            { s, v -> s.copy(notifyVibrationEnabled = v) }
        ),
        boolSpec(
            "reply_chime",
            "Chime when a reply arrives",
            setOf(SettingImpact.NOTIFICATIONS),
            { it.notifyChimeEnabled },
            { s, v -> s.copy(notifyChimeEnabled = v) }
        ),
        boolSpec(
            "completion_notifications",
            "Notify when a background task finishes",
            setOf(SettingImpact.NOTIFICATIONS),
            { it.chatCompletionNotificationsEnabled },
            { s, v -> s.copy(chatCompletionNotificationsEnabled = v) }
        ),
        choiceSpec(
            "completion_preview",
            "Reply shown in the task-finished notification",
            // FULL puts the whole reply on the lock screen.
            setOf(SettingImpact.NOTIFICATIONS, SettingImpact.PRIVACY),
            CompletionPreview.entries.associateBy { it.name.lowercase() },
            { it.name.lowercase() },
            { it.chatCompletionPreview },
            { s, v -> s.copy(chatCompletionPreview = v) }
        ),
        boolSpec(
            "server_messages",
            "Messages from Samosa AI",
            setOf(SettingImpact.NOTIFICATIONS),
            { it.serverMessagesEnabled },
            { s, v -> s.copy(serverMessagesEnabled = v) }
        )
    )

    /** Gotcha's own proactive notifications (issue #100). */
    private fun localNotificationSpecs(): List<Spec> = listOf(
        boolSpec(
            "local_notifications",
            "Gotcha's own reminders and tips",
            setOf(SettingImpact.NOTIFICATIONS),
            { it.localNotificationsEnabled },
            { s, v -> s.copy(localNotificationsEnabled = v) }
        ),
        boolSpec(
            "unfinished_chat_reminders",
            "Remind me about unfinished chats",
            setOf(SettingImpact.NOTIFICATIONS),
            { it.unfinishedChatRemindersEnabled },
            { s, v -> s.copy(unfinishedChatRemindersEnabled = v) }
        ),
        boolSpec(
            "routine_suggestions",
            "Suggest routines when they're due",
            setOf(SettingImpact.NOTIFICATIONS),
            { it.routineSuggestionsEnabled },
            { s, v -> s.copy(routineSuggestionsEnabled = v) }
        ),
        boolSpec(
            "inactivity_reminders",
            "Remind me after a quiet spell",
            setOf(SettingImpact.NOTIFICATIONS),
            { it.inactivityRemindersEnabled },
            { s, v -> s.copy(inactivityRemindersEnabled = v) }
        ),
        choiceSpec(
            "inactivity_days",
            "Days without Gotcha before a reminder",
            setOf(SettingImpact.NOTIFICATIONS),
            listOf(2, 3, 4, 7, 14).associateBy { it.toString() },
            { it.toString() },
            { it.inactivityDays },
            { s, v -> s.copy(inactivityDays = v) }
        ),
        boolSpec(
            "quiet_hours",
            "Quiet hours for reminders and tips",
            setOf(SettingImpact.NOTIFICATIONS),
            { it.quietHoursEnabled },
            { s, v -> s.copy(quietHoursEnabled = v) }
        ),
        choiceSpec(
            "max_notifications_per_day",
            "Most reminders and tips a day",
            setOf(SettingImpact.NOTIFICATIONS),
            listOf(1, 2, 3).associateBy { it.toString() },
            { it.toString() },
            { it.maxLocalNotificationsPerDay },
            { s, v -> s.copy(maxLocalNotificationsPerDay = v) }
        ),
        boolSpec(
            "notifications_name_chats",
            "Name chats in notifications",
            setOf(SettingImpact.NOTIFICATIONS, SettingImpact.PRIVACY),
            { it.notificationsMentionChats },
            { s, v -> s.copy(notificationsMentionChats = v) }
        ),
        boolSpec(
            "daily_tips",
            "A daily tip with something to try",
            setOf(SettingImpact.NOTIFICATIONS),
            { it.dailyTipsEnabled },
            { s, v -> s.copy(dailyTipsEnabled = v) }
        )
    )

    private fun voiceSpecs(): List<Spec> = listOf(
        boolSpec(
            "auto_read_replies",
            "Read replies aloud",
            setOf(SettingImpact.PRIVACY),
            { it.autoReadReplies },
            { s, v -> s.copy(autoReadReplies = v) }
        ),
        choiceSpec(
            "reply_language",
            "Reply language",
            emptySet(),
            languages,
            { it },
            { it.preferredLanguage },
            { s, v -> s.copy(preferredLanguage = v) }
        ),
        choiceSpec(
            "voice_language",
            "Speech language",
            emptySet(),
            languages + (FOLLOW_REPLY_LANGUAGE to ""),
            { it.ifBlank { "Same as replies" } },
            { it.voiceLanguage },
            { s, v -> s.copy(voiceLanguage = v) }
        )
    )

    private fun appearanceSpecs(catalog: SettingsCatalog): List<Spec> = listOf(
        choiceSpec(
            "skin",
            "Skin",
            emptySet(),
            catalog.skins.keys.associateWith { it },
            { catalog.skins[it] ?: it },
            { it.skinId },
            { s, v -> s.copy(skinId = v) }
        )
    )

    private fun proactiveSpecs(): List<Spec> = listOf(
        boolSpec(
            "proactive_enabled",
            "Proactive assistance",
            setOf(SettingImpact.PRIVACY),
            { it.proactiveEnabled },
            { s, v -> s.copy(proactiveEnabled = v) }
        ),
        boolSpec(
            "proactive_scan_screen",
            "Proactive: scan the screen",
            setOf(SettingImpact.PRIVACY),
            { it.proactiveScanScreen },
            { s, v -> s.copy(proactiveScanScreen = v) }
        ),
        boolSpec(
            "proactive_scan_clipboard",
            "Proactive: scan the clipboard",
            setOf(SettingImpact.PRIVACY),
            { it.proactiveScanClipboard },
            { s, v -> s.copy(proactiveScanClipboard = v) }
        ),
        boolSpec(
            "proactive_scan_notifications",
            "Proactive: scan notifications",
            setOf(SettingImpact.PRIVACY, SettingImpact.NOTIFICATIONS),
            { it.proactiveScanNotifications },
            { s, v -> s.copy(proactiveScanNotifications = v) }
        ),
        boolSpec(
            "proactive_otp_detection",
            "Proactive: detect one-time codes",
            setOf(SettingImpact.PRIVACY),
            { it.proactiveOtpEnabled },
            { s, v -> s.copy(proactiveOtpEnabled = v) }
        ),
        boolSpec(
            "proactive_auto_copy_otp",
            "Proactive: copy one-time codes automatically",
            setOf(SettingImpact.PRIVACY),
            { it.proactiveAutoCopyOtp },
            { s, v -> s.copy(proactiveAutoCopyOtp = v) }
        )
    )

    // Turning the wake word on or off stays in Settings: it needs the mic and the overlay.
    private fun wakeWordSpecs(): List<Spec> = listOf(
        choiceSpec(
            "wake_word_mode",
            "Wake word listens",
            setOf(SettingImpact.DEVICE_CONTROL, SettingImpact.PRIVACY),
            WakeWordListeningMode.entries.associateBy { it.name.lowercase() },
            { it.name.lowercase() },
            { it.wakeWordListeningMode },
            { s, v -> s.copy(wakeWordListeningMode = v) }
        ),
        Spec(
            "wake_word_sensitivity",
            "Wake word sensitivity",
            setOf(SettingImpact.DEVICE_CONTROL),
            "a number from 0 to 1 (higher triggers more easily)",
            "number",
            parse = { p ->
                if (p.isString) null else p.doubleOrNull?.takeIf { it in 0.0..1.0 }?.toFloat()
            },
            read = { "${(it.wakeWordSensitivity * 100).toInt()}%" },
            write = { s, v -> s.copy(wakeWordSensitivity = v as Float) }
        )
    )

    /** Every top-level key the tool accepts. */
    fun keys(catalog: SettingsCatalog): List<String> = specs(catalog).map { it.key } + CONNECTORS_KEY + SKILLS_KEY

    /**
     * JSON Schema `properties` for the tool's `changes` object, built from the same
     * specs that validate it so the schema can never offer a key the parser refuses.
     */
    fun schemaProperties(catalog: SettingsCatalog): JsonObject = buildJsonObject {
        for (spec in specs(catalog)) {
            put(
                spec.key,
                buildJsonObject {
                    put("type", spec.jsonType)
                    put("description", "${spec.label}: ${spec.accepts}.")
                    spec.choices?.let { choices ->
                        put("enum", kotlinx.serialization.json.JsonArray(choices.map(::JsonPrimitive)))
                    }
                }
            )
        }
        put(
            CONNECTORS_KEY,
            toggleSchema(
                "Turn connectors on (true) or off (false), keyed by connector id: " +
                    catalog.connectors.keys.joinToString(", ") + ". Off keeps the sign-in but " +
                    "removes that connector's tools."
            )
        )
        put(
            SKILLS_KEY,
            toggleSchema("Turn skills on (true) or off (false), keyed by skill id as search_skills reports it.")
        )
    }

    private fun toggleSchema(description: String) = buildJsonObject {
        put("type", "object")
        put("description", description)
        put("additionalProperties", buildJsonObject { put("type", "boolean") })
    }

    /**
     * Validates [requested] — the tool's `changes` object — into a change list.
     *
     * All-or-nothing: one unknown key or bad value refuses the whole request, so
     * the user is never asked to approve half of what the model meant.
     */
    fun parse(requested: JsonObject, catalog: SettingsCatalog): Result<List<SettingChange>> {
        if (requested.isEmpty()) return Result.failure(SettingsUpdateRejected(listOf("no settings given")))
        val byKey = specs(catalog).associateBy { it.key }
        val changes = mutableListOf<SettingChange>()
        val errors = mutableListOf<String>()
        for ((key, value) in requested) {
            when (key) {
                CONNECTORS_KEY -> parseToggles(
                    key, value, catalog.connectors, "Connector",
                    setOf(SettingImpact.PRIVACY, SettingImpact.PERMISSIONS),
                    { it.disabledConnectors }, { s, v -> s.copy(disabledConnectors = v) },
                    changes, errors
                )
                SKILLS_KEY -> parseToggles(
                    key, value, catalog.skills, "Skill", emptySet(),
                    { it.disabledSkills }, { s, v -> s.copy(disabledSkills = v) },
                    changes, errors
                )
                else -> {
                    val spec = byKey[key]
                    if (spec == null) {
                        errors += "'$key' is not a setting this tool can change"
                        continue
                    }
                    val parsed = (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let(spec.parse)
                    if (parsed == null) {
                        errors += "'$key' must be ${spec.accepts}, got $value"
                    } else {
                        changes += SettingChange(
                            key, spec.label, spec.impacts, spec.read, { s -> spec.write(s, parsed) }, value
                        )
                    }
                }
            }
        }
        return if (errors.isEmpty()) Result.success(changes) else Result.failure(SettingsUpdateRejected(errors))
    }

    /** A `{id: true|false}` object over a disabled-id set; one change per id. */
    @Suppress("LongParameterList")
    private fun parseToggles(
        key: String,
        value: JsonElement,
        known: Map<String, String>,
        noun: String,
        impacts: Set<SettingImpact>,
        get: (Settings) -> Set<String>,
        set: (Settings, Set<String>) -> Settings,
        changes: MutableList<SettingChange>,
        errors: MutableList<String>
    ) {
        val toggles = value as? JsonObject
        if (toggles == null || toggles.isEmpty()) {
            errors += "'$key' must be an object of id → true (enabled) or false (disabled)"
            return
        }
        for ((id, flag) in toggles) {
            val name = known[id]
            val enabled = (flag as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            when {
                name == null -> errors += "unknown ${noun.lowercase()} '$id'" +
                    if (known.isEmpty()) "" else " (known: ${known.keys.sorted().joinToString(", ")})"
                enabled == null -> errors += "'$key.$id' must be true or false"
                else -> changes += SettingChange(
                    "$key.$id", "$noun: $name", impacts,
                    read = { s -> if (id in get(s)) "Off" else "On" },
                    write = { s -> set(s, if (enabled) get(s) - id else get(s) + id) },
                    rawValue = flag
                )
            }
        }
    }

    /**
     * Drops the changes that would leave [current] as it is. An empty plan means
     * there is nothing to ask the user about.
     */
    fun plan(changes: List<SettingChange>, current: Settings): SettingsChangePlan =
        SettingsChangePlan(changes.filter { it.apply(current) != current }, current)

    /** Rebuilds the `changes` object from a change list, to carry it across the confirmation. */
    fun toJson(changes: List<SettingChange>): JsonObject = buildJsonObject {
        val connectors = mutableMapOf<String, JsonElement>()
        val skills = mutableMapOf<String, JsonElement>()
        for (change in changes) {
            when {
                change.key.startsWith("$CONNECTORS_KEY.") ->
                    connectors[change.key.removePrefix("$CONNECTORS_KEY.")] = change.rawValue
                change.key.startsWith("$SKILLS_KEY.") ->
                    skills[change.key.removePrefix("$SKILLS_KEY.")] = change.rawValue
                else -> put(change.key, change.rawValue)
            }
        }
        if (connectors.isNotEmpty()) put(CONNECTORS_KEY, JsonObject(connectors))
        if (skills.isNotEmpty()) put(SKILLS_KEY, JsonObject(skills))
    }

    /** Marker [ToolExecutor] returns so [com.gotcha.agent.AgentEngine] asks before writing. */
    const val CONFIRM_PREFIX = "CONFIRM_UPDATE_SETTINGS:"

    /** A validated request carried across the confirmation. */
    data class Request(val changes: List<SettingChange>, val reason: String?)

    fun encodePayload(changes: List<SettingChange>, reason: String?): String {
        val payload = buildJsonObject {
            put("changes", toJson(changes))
            if (!reason.isNullOrBlank()) put("reason", reason)
        }
        return java.util.Base64.getEncoder().encodeToString(payload.toString().toByteArray())
    }

    /**
     * Decodes and re-validates a payload from [encodePayload]. Re-validating costs
     * nothing and means the write never trusts the marker text on its own.
     */
    fun decodePayload(payload: String, catalog: SettingsCatalog): Request? = runCatching {
        val text = String(java.util.Base64.getDecoder().decode(payload))
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(text) as JsonObject
        val changes = parse(obj["changes"] as JsonObject, catalog).getOrThrow()
        Request(changes, (obj["reason"] as? JsonPrimitive)?.content)
    }.getOrNull()

    /** What the model is told once an approved change is saved. */
    fun appliedMessage(plan: SettingsChangePlan): String =
        "The user approved and these settings are now saved: " + plan.lines().joinToString("; ") + "."

    /** The confirmation text: what changes, from what to what, and what it touches. */
    fun describe(plan: SettingsChangePlan, reason: String?): String = buildString {
        if (!reason.isNullOrBlank()) append("Why: ").append(reason.trim()).append("\n\n")
        append("Change these Gotcha settings:\n")
        plan.lines().forEach { append("• ").append(it).append('\n') }
        append('\n')
        val impacts = plan.impacts
        if (impacts.isEmpty()) {
            append("Does not affect privacy, notifications, permissions or device control.")
        } else {
            append("Affects: ").append(impacts.joinToString(", ") { it.label }).append('.')
        }
    }
}
