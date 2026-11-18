package com.gotcha.telemetry

import androidx.annotation.StringRes
import com.gotcha.R
import com.gotcha.agent.skills.SkillRegistry
import com.gotcha.connectors.ConnectorCatalog
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.service.SmartActionDetector
import com.gotcha.ui.tour.defaultTourSteps
import kotlinx.serialization.json.JsonPrimitive

/** What happened to a connector. */
enum class ConnectorAction(override val wire: String) : WireWord {
    CONNECT("connect"),
    REFRESH("refresh"),
    DISCONNECT("disconnect"),
    ERROR("error")
}

/** How a skill reached the model: its one-line summary was offered, or the model fetched its instructions. */
enum class SkillVia(override val wire: String) : WireWord {
    OFFERED("offered"),
    FETCHED("fetched")
}

/**
 * A permission Gotcha can use, by kind. Watched as a whole set (see
 * [PermissionWatch]) because most are granted outside the app, in Android
 * Settings, where no callback reaches us.
 */
enum class PermissionName(override val wire: String) : WireWord {
    MIC("mic"),
    CAMERA("camera"),
    LOCATION("location"),
    CONTACTS("contacts"),
    CALENDAR("calendar"),
    PHONE("phone"),
    SMS("sms"),
    READ_SMS("read_sms"),
    CALL_LOG("call_log"),
    PHOTOS("photos"),
    NOTIFICATIONS("notifications"),
    ALL_FILES("all_files"),
    WRITE_SETTINGS("write_settings"),
    DND("dnd"),
    USAGE_ACCESS("usage_access"),
    DEVICE_ADMIN("device_admin"),
    ACCESSIBILITY("accessibility"),
    NOTIFICATION_LISTENER("notification_listener"),
    OVERLAY("overlay"),
    EXACT_ALARM("exact_alarm"),
    BATTERY("battery"),
    HEALTH("health")
}

/** What happened to a step of the guided setup. */
enum class TourAction(override val wire: String) : WireWord {
    /** The step became the current one. */
    REACHED("reached"),
    DONE("done"),

    /** Passed over because it belongs to the other branch (say, Samosa sign-in for a BYO user). */
    SKIPPED("skipped"),

    /** The user left the tour with "Skip tour" while on this step. */
    ABANDONED("abandoned")
}

enum class SetupAction(override val wire: String) : WireWord {
    SAVED("saved"),
    TESTED("tested")
}

/** Screen Lens (a region the user drew) or Screen Companion (the ball's own suggestions). */
enum class LensKind(override val wire: String) : WireWord {
    LENS("lens"),
    COMPANION("companion")
}

/** Which Lens or Companion action was tapped — the kind of action, never its text. */
enum class LensAction(override val wire: String) : WireWord {
    SELECT("select"),
    COPY("copy"),
    TRANSLATE("translate"),
    SAVE("save"),
    ASK("ask"),
    NAVIGATE("navigate"),
    DIAL("dial"),
    CALENDAR("calendar"),
    SUMMARIZE_LINK("summarize_link"),
    SMS("sms"),
    OPEN_LINK("open_link"),
    SHARE("share"),
    ADD_CONTACT("add_contact"),
    MAIL("mail"),
    WHATSAPP("whatsapp"),
    CONVERT("convert"),

    /** A suggestion answered by the model rather than by an Android intent. */
    PROMPT("prompt");

    companion object {
        /** The kind of a suggestion, from its encoded prompt; the payload is never looked at. */
        fun ofSuggestion(prompt: String): LensAction = when (SmartActionDetector.decode(prompt)?.first) {
            null -> PROMPT
            SmartActionDetector.TYPE_NAVIGATE -> NAVIGATE
            SmartActionDetector.TYPE_DIAL -> DIAL
            SmartActionDetector.TYPE_CALENDAR -> CALENDAR
            SmartActionDetector.TYPE_FETCH -> SUMMARIZE_LINK
            SmartActionDetector.TYPE_SMS -> SMS
            SmartActionDetector.TYPE_VIEW -> OPEN_LINK
            SmartActionDetector.TYPE_COPY -> COPY
            SmartActionDetector.TYPE_SHARE -> SHARE
            SmartActionDetector.TYPE_CONTACT -> ADD_CONTACT
            SmartActionDetector.TYPE_MAILTO -> MAIL
            SmartActionDetector.TYPE_WHATSAPP -> WHATSAPP
            SmartActionDetector.TYPE_CONVERT -> CONVERT
            else -> PROMPT
        }
    }
}

private inline fun <reified E> featureWords(): Vocab.Words where E : Enum<E>, E : WireWord =
    Vocab.Words(enumValues<E>().map { it.wire }.toSet())

private fun wordOf(value: WireWord) = JsonPrimitive(value.wire)

/**
 * The feature-adoption events: connectors, skills, permissions, the guided
 * setup, AI setup and Lens. Kept apart from [TelemetryEvents] only to keep each
 * file a readable size; [TelemetryEvents.defineAll] registers both.
 */
object FeatureEvents {

    const val CONNECTOR = "connector"
    const val SKILL_USED = "skill_used"
    const val PERMISSION = "permission"
    const val TOUR = "tour"
    const val SETUP = "setup"
    const val LENS = "lens"

    /** Sent in place of a community skill's id, which the user's import chose. */
    const val CUSTOM_SKILL = "custom"

    internal fun define(schema: TelemetrySchema) {
        schema.define(
            CONNECTOR,
            "connector" to Prop(Vocab.Words(ConnectorCatalog.all.map { it.id }.toSet())),
            "action" to Prop(featureWords<ConnectorAction>()),
            "mcp_servers" to Prop(Vocab.Words(Buckets.COUNT), optional = true)
        )
        schema.define(
            SKILL_USED,
            // Read when an event is checked, not now: bundled skills load from the APK's assets at start.
            "skill" to Prop(Vocab.Dynamic { SkillRegistry.bundledSkillIds() + CUSTOM_SKILL }),
            "via" to Prop(featureWords<SkillVia>())
        )
        schema.define(
            PERMISSION,
            "name" to Prop(featureWords<PermissionName>()),
            "granted" to Prop(Vocab.Bool)
        )
        schema.define(
            TOUR,
            "step" to Prop(Vocab.Words(defaultTourSteps().map { it.id }.toSet())),
            "action" to Prop(featureWords<TourAction>())
        )
        schema.define(
            SETUP,
            "action" to Prop(featureWords<SetupAction>()),
            "provider" to Prop(featureWords<ProviderKind>()),
            "endpoint" to Prop(featureWords<EndpointClass>()),
            "has_key" to Prop(Vocab.Bool),
            "test_ok" to Prop(Vocab.Bool, optional = true)
        )
        schema.define(
            LENS,
            "kind" to Prop(featureWords<LensKind>()),
            "action" to Prop(featureWords<LensAction>())
        )
    }

    /**
     * A connector was connected, refreshed by hand, disconnected, or failed to
     * connect. [mcpServers] is how many MCP servers are set up, for MCP only;
     * their names and addresses are never part of it.
     */
    fun connector(id: String, action: ConnectorAction, mcpServers: Int? = null) = TelemetryEvent(
        CONNECTOR,
        buildMap {
            put("connector", JsonPrimitive(id))
            put("action", wordOf(action))
            mcpServers?.let { put("mcp_servers", JsonPrimitive(Buckets.count(it))) }
        }
    )

    /** A skill reached the model. A skill the user imported is sent as [CUSTOM_SKILL]. */
    fun skillUsed(skillId: String, via: SkillVia) = TelemetryEvent(
        SKILL_USED,
        mapOf(
            "skill" to JsonPrimitive(if (skillId in SkillRegistry.bundledSkillIds()) skillId else CUSTOM_SKILL),
            "via" to wordOf(via)
        )
    )

    fun permission(name: PermissionName, granted: Boolean) = TelemetryEvent(
        PERMISSION,
        mapOf("name" to wordOf(name), "granted" to JsonPrimitive(granted))
    )

    fun tour(stepId: String, action: TourAction) = TelemetryEvent(
        TOUR,
        mapOf("step" to JsonPrimitive(stepId), "action" to wordOf(action))
    )

    /**
     * The AI setup was saved, or its connection test ran. Says which kind of
     * provider and server, and whether a key is filled in — never the key, the
     * address or the model.
     */
    fun setup(
        action: SetupAction,
        provider: LlmProvider,
        baseUrl: String,
        hasKey: Boolean,
        testOk: Boolean? = null
    ) = TelemetryEvent(
        SETUP,
        buildMap {
            put("action", wordOf(action))
            put("provider", wordOf(if (provider == LlmProvider.SAMOSA_AI) ProviderKind.SAMOSA else ProviderKind.BYO))
            put("endpoint", wordOf(EndpointClass.of(provider, baseUrl)))
            put("has_key", JsonPrimitive(hasKey))
            testOk?.let { put("test_ok", JsonPrimitive(it)) }
        }
    )

    /** [setup] for settings as they would be saved. */
    fun setup(settings: Settings, action: SetupAction, testOk: Boolean? = null) = setup(
        action,
        settings.provider,
        settings.effectiveBaseUrl,
        settings.effectiveApiKey.isNotBlank(),
        testOk
    )

    fun lens(kind: LensKind, action: LensAction) = TelemetryEvent(
        LENS,
        mapOf("kind" to wordOf(kind), "action" to wordOf(action))
    )

    @StringRes
    fun descriptionOf(name: String): Int? = when (name) {
        CONNECTOR -> R.string.telemetry_event_connector
        SKILL_USED -> R.string.telemetry_event_skill_used
        PERMISSION -> R.string.telemetry_event_permission
        TOUR -> R.string.telemetry_event_tour
        SETUP -> R.string.telemetry_event_setup
        LENS -> R.string.telemetry_event_lens
        else -> null
    }
}
