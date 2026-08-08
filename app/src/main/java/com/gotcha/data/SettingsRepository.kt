package com.gotcha.data

import android.content.Context
import android.content.SharedPreferences
import com.gotcha.BuildConfig
import com.gotcha.audio.AudioProvider

/**
 * When the wake-word listener is allowed to run, relative to the screen state.
 *
 * The listener's idle cost (issue #37, `docs/benchmark/wake-word-idle.md`) is
 * dominated by holding the microphone — the `AudioIn` wake lock, not the
 * inference. These modes trade how much of the day that happens for where the
 * user is most likely to want a hands-free trigger.
 */
enum class WakeWordListeningMode {
    /** Listen regardless of screen state — the original always-on behaviour. */
    ALWAYS,

    /** Listen only while the screen is on, so the mic LED is off when it is not. */
    SCREEN_ON,

    /** Listen only while the screen is off, for hands-free use away from the app. */
    SCREEN_OFF;

    /** Whether the listener should run given the current screen interactivity. */
    fun allows(screenInteractive: Boolean): Boolean = when (this) {
        ALWAYS -> true
        SCREEN_ON -> screenInteractive
        SCREEN_OFF -> !screenInteractive
    }
}

data class Settings(
    // Which LLM backend is active. Defaults to the Samosa AI flow.
    val provider: LlmProvider = LlmProvider.SAMOSA_AI,
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    // Samosa AI: backend session JWT + connected Google account (never
    // stores the Google ID token). Only used when provider == SAMOSA_AI.
    val samosaSessionToken: String = "",
    val samosaEmail: String = "",
    val subAgentModel: String = "", // empty = same as main agent
    val navigatorModel: String = "", // empty = same as main model
    val maxToolRounds: Int = 300,
    val maxRepeatedToolCalls: Int = 20,
    val maxNavigationToolCalls: Int = 30,
    /**
     * How many consecutive rounds may consist only of delegation tools
     * (`task`, `navigate_app`) before the run is stopped. A sub-agent hands
     * back a text report and nothing else, so a round that only delegates
     * shows the model no new evidence — repeating it is the re-delegation
     * loop from issue #20, which the byte-identical guard cannot see because
     * each call carries a freshly rephrased task string.
     */
    val maxConsecutiveDelegations: Int = 3,
    val maxContextTokens: Int = 70000,
    val apiTimeoutSeconds: Long = 0L,
    // TTS / STT settings
    val ttsProvider: AudioProvider = AudioProvider.ANDROID,
    val ttsApiBaseUrl: String = "",
    val ttsApiKey: String = "",
    val ttsApiModel: String = "",
    val ttsVoice: String = "",
    val sttProvider: AudioProvider = AudioProvider.ANDROID,
    val sttApiBaseUrl: String = "",
    val sttApiKey: String = "",
    val sttApiModel: String = "",
    val sttLanguage: String = "",
    val autoReadReplies: Boolean = false,
    /**
     * Buzz when a reply arrives. On by default: a reply can land while the user
     * is in another app, and the pattern is distinct from the error buzz so it
     * says *how* the turn ended, not just that it did.
     */
    val notifyVibrationEnabled: Boolean = true,
    /** Chime when a reply arrives. Off by default — audible in a way a buzz is not. */
    val notifyChimeEnabled: Boolean = false,
    val assistiveBallEnabled: Boolean = false,
    val wakeWordEnabled: Boolean = false,
    val wakeWordSensitivity: Float = 0.75f,
    val wakeWordListeningMode: WakeWordListeningMode = WakeWordListeningMode.ALWAYS,
    /** Server-driven notifications from `<SAMOSA_API_URL>/v1/gotcha/notifications`. */
    val serverMessagesEnabled: Boolean = true,
    /** Epoch millis of the last successful server-messages fetch. 0 = never. */
    val serverMessagesLastFetchedAt: Long = 0L,
    /** Last seen ETag from the notifications endpoint; round-tripped in `If-None-Match`. */
    val serverMessagesEtag: String = "",
    // ---- Appearance (Settings ▸ Appearance) ----
    /**
     * Which skin is painted. Stored as the id string rather than an enum so a
     * build that drops a skin degrades to the default instead of throwing on a
     * value it no longer knows.
     *
     * Keep this default in sync with [com.gotcha.ui.theme.Skins.DEFAULT_ID] — a
     * fresh install that never runs the migration reads it straight from here.
     */
    val skinId: String = "vellum",
    val disabledSkills: Set<String> = emptySet(),
    /**
     * Ids of connectors the user switched off. Credentials survive (re-enabling
     * needs no re-auth), but the connector contributes no tools and its skills
     * stop being injected.
     */
    val disabledConnectors: Set<String> = emptySet(),
    /** Interval in minutes for automatic background tool/connector refresh. 0 = Disabled (Manual only). Default: 30 minutes. */
    val connectorAutoRefreshIntervalMinutes: Int = 30,
    /** Epoch millis when connectors were last auto-refreshed or sync triggered. */
    val connectorLastRefreshedAt: Long = 0L,
    // Proactive Assistance Settings
    val proactiveEnabled: Boolean = true,
    val proactiveScanScreen: Boolean = true,
    val proactiveScanClipboard: Boolean = true,
    val proactiveScanNotifications: Boolean = true,
    val proactiveOtpEnabled: Boolean = true,
    val proactiveAutoCopyOtp: Boolean = true,
    val proactiveAppBlacklist: Set<String> = emptySet(),
    // ---- Personal info (Settings ▸ Personal Info) ----
    // Everything the user chooses to tell the agent about themselves. All of it
    // is optional, and all of it reaches the model's system prompt — see
    // AgentEngine's <user_profile> block and the style directive.
    /** What the user wants to be called. */
    val userName: String = "",
    /** Where the user is, in their own words ("Munich, Germany"). Grounds dates, units and prices. */
    val userLocation: String = "",
    /** What the user does — role, field, seniority. */
    val userOccupation: String = "",
    /** Free-text background: anything the agent should know about them by default. */
    val userBackground: String = "",
    /**
     * Free-text output preferences ("no bullet lists", "always show the command
     * you ran"). Kept apart from [userBackground] because it is an instruction
     * about *how* to answer, not a fact, and is injected next to the language
     * directive rather than into the profile block.
     */
    val userResponseStyle: String = "",
    val preferredLanguage: String = "English",
    val preferredCurrency: String = "USD",
    val communitySkillHosts: Set<String> = setOf(BuildConfig.SAMOSA_SKILL_HOST),
    /**
     * Version tag of the Terms / Disclaimer / Data Retention bundle the user has
     * accepted. Empty string means "never accepted." The first-launch consent
     * dialog writes the current [LEGAL_VERSION] here when the user agrees;
     * MainActivity re-prompts whenever the stored tag is older than
     * [LEGAL_VERSION], so a meaningful change to the legal copy forces re-acceptance
     * without losing any user data.
     */
    val legalAcceptedVersion: String = "",
    // ---- Guided setup (the feature tour) ----
    /** False on a fresh install, which is what launches the tour. */
    val hasCompletedOnboarding: Boolean = false,
    /**
     * The tour step to resume on, or blank when it is not running.
     *
     * Stored rather than derived because half the tour sends the user out to
     * Android Settings to grant a permission, and a phone under memory pressure
     * is free to kill the app while they are away. Without this the tour would
     * start again from the top on their return, which is the point most people
     * would give up on it.
     */
    val tourStepId: String = "",
    /**
     * Which edition of the tour this install has seen. A later release that adds
     * a step bumps this and replays only what is new, rather than making
     * everyone sit through the parts they have already done.
     */
    val onboardingVersion: Int = 0
) {
    /** True when the active provider has everything it needs to make requests. */
    val isConfigured: Boolean
        get() = when (provider) {
            LlmProvider.SAMOSA_AI -> samosaSessionToken.isNotBlank()
            LlmProvider.OPENAI_COMPATIBLE -> baseUrl.isNotBlank()
        }

    /**
     * Whether credentials the user has actually *saved* would answer a question.
     *
     * Stricter than [isConfigured], which on the Samosa default an untouched
     * install does not yet satisfy (no session token). The feature tour needs to
     * tell "they have set this up" from "they have not started", and a step that
     * considered itself finished before the user had done anything would be
     * exactly the kind of false progress the tour exists to avoid.
     */
    val hasUsableModel: Boolean
        get() = when (provider) {
            LlmProvider.SAMOSA_AI -> samosaSessionToken.isNotBlank() && model.isNotBlank()
            LlmProvider.OPENAI_COMPATIBLE ->
                baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
        }

    /** Base URL the networking stack should actually use for the active provider. */
    val effectiveBaseUrl: String
        get() = when (provider) {
            LlmProvider.SAMOSA_AI -> LlmProvider.SAMOSA_BASE_URL
            LlmProvider.OPENAI_COMPATIBLE -> baseUrl
        }

    /** Bearer token the networking stack should attach for the active provider. */
    val effectiveApiKey: String
        get() = when (provider) {
            LlmProvider.SAMOSA_AI -> samosaSessionToken
            LlmProvider.OPENAI_COMPATIBLE -> apiKey
        }

    /** Base URL the TTS engine should actually use. Samosa AI maps to the
     *  shared OpenAI-compatible proxy, but only when a session token is present
     *  — otherwise the URL is empty so the engine refuses to make calls until
     *  the user re-signs-in. user-supplied API mode uses the user's URL. */
    val effectiveTtsBaseUrl: String
        get() = when (ttsProvider) {
            AudioProvider.SAMOSA_AI ->
                if (samosaSessionToken.isNotBlank()) LlmProvider.SAMOSA_BASE_URL else ""
            AudioProvider.API -> ttsApiBaseUrl
            else -> ""
        }

    /** Base URL the STT engine should actually use. Samosa AI maps to the
     *  shared OpenAI-compatible proxy, but only when a session token is present
     *  — otherwise the URL is empty so the engine refuses to make calls until
     *  the user re-signs-in. user-supplied API mode uses the user's URL. */
    val effectiveSttBaseUrl: String
        get() = when (sttProvider) {
            AudioProvider.SAMOSA_AI ->
                if (samosaSessionToken.isNotBlank()) LlmProvider.SAMOSA_BASE_URL else ""
            AudioProvider.API -> sttApiBaseUrl
            else -> ""
        }

    /** Bearer token for TTS endpoint. Samosa AI uses the session JWT directly;
     *  External API uses the user key with fallback to the LLM's API key. */
    val effectiveTtsApiKey: String
        get() = when (ttsProvider) {
            AudioProvider.SAMOSA_AI -> samosaSessionToken
            AudioProvider.API -> ttsApiKey.ifBlank { effectiveApiKey }
            else -> ""
        }

    /** Bearer token for STT endpoint. Samosa AI uses the session JWT directly;
     *  External API uses the user key with fallback to the LLM's API key. */
    val effectiveSttApiKey: String
        get() = when (sttProvider) {
            AudioProvider.SAMOSA_AI -> samosaSessionToken
            AudioProvider.API -> sttApiKey.ifBlank { effectiveApiKey }
            else -> ""
        }

    /** True when Samosa AI is selected and a session token exists. */
    val isSamosaAuthenticated: Boolean
        get() = provider == LlmProvider.SAMOSA_AI && samosaSessionToken.isNotBlank()

    /**
     * True when both the chosen TTS and STT providers have what they need to
     * actually make a call. Samosa AI needs the session JWT; user-supplied
     * External API needs the base URL; Android and None are always ready.
     */
    val isSpeechConfigured: Boolean
        get() {
            val ttsOk = when (ttsProvider) {
                AudioProvider.SAMOSA_AI -> samosaSessionToken.isNotBlank()
                AudioProvider.API -> ttsApiBaseUrl.isNotBlank()
                else -> true
            }
            val sttOk = when (sttProvider) {
                AudioProvider.SAMOSA_AI -> samosaSessionToken.isNotBlank()
                AudioProvider.API -> sttApiBaseUrl.isNotBlank()
                else -> true
            }
            return ttsOk && sttOk
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1/"
        const val DEFAULT_MODEL = "chai-small"
    }
}

/**
 * Which skin an install lands on when it has never chosen one.
 *
 * Appearance used to be two settings — a Deep Space theme plus a light/dark
 * mode — and is now a single skin per look. Someone who had deliberately set
 * their app light must not be handed the dark one on upgrade, which is the
 * whole reason this exists. It runs once: [SettingsRepository] writes the
 * result, and every later load reads that instead.
 *
 * A legacy light/dark choice keeps the matching Deep Space skin. SYSTEM was the
 * stored default, so it was the theme the app actually showed for most installs
 * — it stays on Deep Space Dark rather than silently flipping those users from
 * dark to light. Only an install that never wrote the field (a fresh install,
 * where the new skin system replaced `theme_mode` entirely) lands on Vellum.
 *
 * @param legacyThemeMode the old `theme_mode` value, or null if never set.
 */
internal fun migrateSkinId(legacyThemeMode: String?): String =
    when (legacyThemeMode) {
        LEGACY_THEME_MODE_LIGHT -> SKIN_DEEP_SPACE_LIGHT
        LEGACY_THEME_MODE_DARK, LEGACY_THEME_MODE_SYSTEM -> SKIN_DEEP_SPACE_DARK
        else -> SKIN_VELLUM
    }

/** The preference file [SettingsRepository] encrypts into. */
internal const val SETTINGS_PREFS_FILE = "gotcha_settings"

/**
 * The raw preference file underneath [SettingsRepository], for change
 * notification and nothing else. Everything in it is encrypted; read values
 * through the repository.
 *
 * This exists because [EncryptedSharedPreferences] holds its listener list on
 * the *wrapper*, and `create` hands back a new wrapper every call — so a
 * listener registered through one `SettingsRepository` is never told about a
 * write made through another one, which is every write the app actually makes.
 * The file beneath is the process-wide singleton the framework caches, and it
 * notifies whoever wrote to it.
 *
 * Keys arrive encrypted, so a listener cannot match on one. Read the setting
 * back and compare instead.
 */
fun settingsChangeNotifier(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(SETTINGS_PREFS_FILE, Context.MODE_PRIVATE)

internal const val SKIN_DEEP_SPACE_DARK = "deepspace"
internal const val SKIN_DEEP_SPACE_LIGHT = "deepspace_light"
internal const val SKIN_VELLUM = "vellum"
private const val LEGACY_THEME_MODE_LIGHT = "LIGHT"
private const val LEGACY_THEME_MODE_DARK = "DARK"
private const val LEGACY_THEME_MODE_SYSTEM = "SYSTEM"

interface SettingsStore {
    fun load(): Settings
    fun save(settings: Settings)
}

/** Stores credentials in EncryptedSharedPreferences (PRD R6). Never logged. */
class SettingsRepository(context: Context) : SettingsStore {

    val prefs: SharedPreferences by lazy {
        SafeEncryptedSharedPreferences.create(context, SETTINGS_PREFS_FILE)
    }

    private fun stringSet(key: String, default: Set<String> = emptySet()): Set<String> =
        prefs.getStringSet(key, default) ?: default

    /**
     * `getString` is nullable even with a non-null default, so every field would
     * otherwise carry its own elvis — enough of them to tip [load] over detekt's
     * complexity ceiling on its own.
     */
    private fun string(key: String, default: String = ""): String =
        prefs.getString(key, default) ?: default

    /** Reads the stored skin, running the one-shot migration if it has not run. */
    private fun resolvedSkinId(): String {
        val stored = prefs.getString(KEY_SKIN_ID, null)
        if (stored != null) return stored
        val migrated = migrateSkinId(prefs.getString(KEY_LEGACY_THEME_MODE, null))
        prefs.edit().putString(KEY_SKIN_ID, migrated).apply()
        return migrated
    }

    override fun load(): Settings = Settings(
        provider = LlmProvider.fromName(prefs.getString(KEY_PROVIDER, null)),
        apiKey = string(KEY_API_KEY),
        baseUrl = string(KEY_BASE_URL, Settings.DEFAULT_BASE_URL),
        model = string(KEY_MODEL, Settings.DEFAULT_MODEL),
        samosaSessionToken = string(KEY_SAMOSA_TOKEN),
        samosaEmail = string(KEY_SAMOSA_EMAIL),
        subAgentModel = string(KEY_SUB_AGENT_MODEL),
        navigatorModel = string(KEY_NAVIGATOR_MODEL),
        maxToolRounds = prefs.getInt(KEY_MAX_TOOL_ROUNDS, 300),
        maxRepeatedToolCalls = prefs.getInt(KEY_MAX_REPEATED_TOOL_CALLS, 20),
        maxNavigationToolCalls = prefs.getInt(KEY_MAX_NAVIGATION_TOOL_CALLS, 30),
        maxConsecutiveDelegations = prefs.getInt(KEY_MAX_CONSECUTIVE_DELEGATIONS, 3),
        maxContextTokens = prefs.getInt(KEY_MAX_CONTEXT_TOKENS, 70000),
        apiTimeoutSeconds = prefs.getLong(KEY_API_TIMEOUT, 0L),
        ttsProvider = runCatching {
            AudioProvider.valueOf(string(KEY_TTS_PROVIDER, "ANDROID"))
        }.getOrDefault(AudioProvider.ANDROID),
        ttsApiBaseUrl = string(KEY_TTS_API_URL),
        ttsApiKey = string(KEY_TTS_API_KEY),
        ttsApiModel = string(KEY_TTS_API_MODEL),
        ttsVoice = string(KEY_TTS_VOICE),
        sttProvider = runCatching {
            AudioProvider.valueOf(string(KEY_STT_PROVIDER, "ANDROID"))
        }.getOrDefault(AudioProvider.ANDROID),
        sttApiBaseUrl = string(KEY_STT_API_URL),
        sttApiKey = string(KEY_STT_API_KEY),
        sttApiModel = string(KEY_STT_API_MODEL),
        sttLanguage = string(KEY_STT_LANGUAGE),
        autoReadReplies = prefs.getBoolean(KEY_AUTO_READ, false),
        notifyVibrationEnabled = prefs.getBoolean(KEY_NOTIFY_VIBRATION, true),
        notifyChimeEnabled = prefs.getBoolean(KEY_NOTIFY_CHIME, false),
        assistiveBallEnabled = prefs.getBoolean(KEY_ASSISTIVE_BALL, false),
        wakeWordEnabled = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, false),
        wakeWordSensitivity = prefs.getFloat(KEY_WAKE_WORD_SENSITIVITY, 0.75f),
        wakeWordListeningMode = runCatching {
            WakeWordListeningMode.valueOf(string(KEY_WAKE_WORD_LISTENING_MODE, "ALWAYS"))
        }.getOrDefault(WakeWordListeningMode.ALWAYS),
        serverMessagesEnabled = prefs.getBoolean(KEY_SERVER_MESSAGES_ENABLED, true),
        serverMessagesLastFetchedAt = prefs.getLong(KEY_SERVER_MESSAGES_LAST_FETCHED, 0L),
        serverMessagesEtag = string(KEY_SERVER_MESSAGES_ETAG),
        skinId = resolvedSkinId(),
        disabledSkills = stringSet(KEY_DISABLED_SKILLS),
        disabledConnectors = stringSet(KEY_DISABLED_CONNECTORS),
        connectorAutoRefreshIntervalMinutes = prefs.getInt(KEY_CONNECTOR_AUTO_REFRESH_INTERVAL, 30),
        connectorLastRefreshedAt = prefs.getLong(KEY_CONNECTOR_LAST_REFRESHED, 0L),
        proactiveEnabled = prefs.getBoolean(KEY_PROACTIVE_ENABLED, true),
        proactiveScanScreen = prefs.getBoolean(KEY_PROACTIVE_SCAN_SCREEN, true),
        proactiveScanClipboard = prefs.getBoolean(KEY_PROACTIVE_SCAN_CLIPBOARD, true),
        proactiveScanNotifications = prefs.getBoolean(KEY_PROACTIVE_SCAN_NOTIFICATIONS, true),
        proactiveOtpEnabled = prefs.getBoolean(KEY_PROACTIVE_OTP_ENABLED, true),
        proactiveAutoCopyOtp = prefs.getBoolean(KEY_PROACTIVE_AUTO_COPY_OTP, true),
        proactiveAppBlacklist = stringSet(KEY_PROACTIVE_BLACKLIST),
        userName = string(KEY_USER_NAME),
        userLocation = string(KEY_USER_LOCATION),
        userOccupation = string(KEY_USER_OCCUPATION),
        userBackground = string(KEY_USER_BACKGROUND),
        userResponseStyle = string(KEY_USER_RESPONSE_STYLE),
        preferredLanguage = string(KEY_PREFERRED_LANGUAGE, "English"),
        preferredCurrency = string(KEY_PREFERRED_CURRENCY, "USD"),
        communitySkillHosts = stringSet(KEY_COMMUNITY_SKILL_HOSTS, defaultCommunitySkillHosts),
        legalAcceptedVersion = string(KEY_LEGAL_ACCEPTED_VERSION),
        hasCompletedOnboarding = prefs.getBoolean(KEY_ONBOARDING_DONE, false),
        tourStepId = string(KEY_TOUR_STEP),
        onboardingVersion = prefs.getInt(KEY_ONBOARDING_VERSION, 0)
    )

    override fun save(settings: Settings) {
        prefs.edit()
            .putString(KEY_PROVIDER, settings.provider.name)
            .putString(KEY_API_KEY, settings.apiKey)
            .putString(KEY_BASE_URL, settings.baseUrl)
            .putString(KEY_MODEL, settings.model)
            .putString(KEY_SAMOSA_TOKEN, settings.samosaSessionToken)
            .putString(KEY_SAMOSA_EMAIL, settings.samosaEmail)
            .putString(KEY_SUB_AGENT_MODEL, settings.subAgentModel)
            .putString(KEY_NAVIGATOR_MODEL, settings.navigatorModel)
            .putInt(KEY_MAX_TOOL_ROUNDS, settings.maxToolRounds)
            .putInt(KEY_MAX_REPEATED_TOOL_CALLS, settings.maxRepeatedToolCalls)
            .putInt(KEY_MAX_NAVIGATION_TOOL_CALLS, settings.maxNavigationToolCalls)
            .putInt(KEY_MAX_CONSECUTIVE_DELEGATIONS, settings.maxConsecutiveDelegations)
            .putInt(KEY_MAX_CONTEXT_TOKENS, settings.maxContextTokens)
            .putLong(KEY_API_TIMEOUT, settings.apiTimeoutSeconds)
            .putString(KEY_TTS_PROVIDER, settings.ttsProvider.name)
            .putString(KEY_TTS_API_URL, settings.ttsApiBaseUrl)
            .putString(KEY_TTS_API_KEY, settings.ttsApiKey)
            .putString(KEY_TTS_API_MODEL, settings.ttsApiModel)
            .putString(KEY_TTS_VOICE, settings.ttsVoice)
            .putString(KEY_STT_PROVIDER, settings.sttProvider.name)
            .putString(KEY_STT_API_URL, settings.sttApiBaseUrl)
            .putString(KEY_STT_API_KEY, settings.sttApiKey)
            .putString(KEY_STT_API_MODEL, settings.sttApiModel)
            .putString(KEY_STT_LANGUAGE, settings.sttLanguage)
            .putBoolean(KEY_AUTO_READ, settings.autoReadReplies)
            .putBoolean(KEY_NOTIFY_VIBRATION, settings.notifyVibrationEnabled)
            .putBoolean(KEY_NOTIFY_CHIME, settings.notifyChimeEnabled)
            .putBoolean(KEY_ASSISTIVE_BALL, settings.assistiveBallEnabled)
            .putBoolean(KEY_WAKE_WORD_ENABLED, settings.wakeWordEnabled)
            .putFloat(KEY_WAKE_WORD_SENSITIVITY, settings.wakeWordSensitivity)
            .putString(KEY_WAKE_WORD_LISTENING_MODE, settings.wakeWordListeningMode.name)
            .putBoolean(KEY_SERVER_MESSAGES_ENABLED, settings.serverMessagesEnabled)
            .putLong(KEY_SERVER_MESSAGES_LAST_FETCHED, settings.serverMessagesLastFetchedAt)
            .putString(KEY_SERVER_MESSAGES_ETAG, settings.serverMessagesEtag)
            .putString(KEY_SKIN_ID, settings.skinId)
            .putStringSet(KEY_DISABLED_SKILLS, settings.disabledSkills)
            .putStringSet(KEY_DISABLED_CONNECTORS, settings.disabledConnectors)
            .putInt(KEY_CONNECTOR_AUTO_REFRESH_INTERVAL, settings.connectorAutoRefreshIntervalMinutes)
            .putLong(KEY_CONNECTOR_LAST_REFRESHED, settings.connectorLastRefreshedAt)
            .putBoolean(KEY_PROACTIVE_ENABLED, settings.proactiveEnabled)
            .putBoolean(KEY_PROACTIVE_SCAN_SCREEN, settings.proactiveScanScreen)
            .putBoolean(KEY_PROACTIVE_SCAN_CLIPBOARD, settings.proactiveScanClipboard)
            .putBoolean(KEY_PROACTIVE_SCAN_NOTIFICATIONS, settings.proactiveScanNotifications)
            .putBoolean(KEY_PROACTIVE_OTP_ENABLED, settings.proactiveOtpEnabled)
            .putBoolean(KEY_PROACTIVE_AUTO_COPY_OTP, settings.proactiveAutoCopyOtp)
            .putStringSet(KEY_PROACTIVE_BLACKLIST, settings.proactiveAppBlacklist)
            .putString(KEY_USER_NAME, settings.userName)
            .putString(KEY_USER_LOCATION, settings.userLocation)
            .putString(KEY_USER_OCCUPATION, settings.userOccupation)
            .putString(KEY_USER_BACKGROUND, settings.userBackground)
            .putString(KEY_USER_RESPONSE_STYLE, settings.userResponseStyle)
            .putString(KEY_PREFERRED_LANGUAGE, settings.preferredLanguage)
            .putString(KEY_PREFERRED_CURRENCY, settings.preferredCurrency)
            .putStringSet(KEY_COMMUNITY_SKILL_HOSTS, settings.communitySkillHosts)
            .putString(KEY_LEGAL_ACCEPTED_VERSION, settings.legalAcceptedVersion)
            .putBoolean(KEY_ONBOARDING_DONE, settings.hasCompletedOnboarding)
            .putString(KEY_TOUR_STEP, settings.tourStepId)
            .putInt(KEY_ONBOARDING_VERSION, settings.onboardingVersion)
            .apply()
    }

    /**
     * Persist tour progress alone.
     *
     * The tour moves while other screens are open and holding unsaved edits of
     * their own — that is the entire point of a coach mark. A full [save] here
     * would take the settings as they were when the page loaded and write them
     * back over whatever the user has since typed.
     */
    fun saveTourProgress(stepId: String?, completed: Boolean, version: Int) {
        prefs.edit()
            .putString(KEY_TOUR_STEP, stepId.orEmpty())
            .putBoolean(KEY_ONBOARDING_DONE, completed)
            .putInt(KEY_ONBOARDING_VERSION, version)
            .apply()
    }

    /** Persist just the Samosa session token + account email after a sign-in. */
    fun saveSamosaSession(token: String, email: String) {
        prefs.edit()
            .putString(KEY_SAMOSA_TOKEN, token)
            .putString(KEY_SAMOSA_EMAIL, email)
            .apply()
    }

    /** Clear the Samosa session (logout / 401). Leaves all other settings intact. */
    fun clearSamosaSession() {
        prefs.edit()
            .remove(KEY_SAMOSA_TOKEN)
            .remove(KEY_SAMOSA_EMAIL)
            .apply()
    }

    /**
     * A per-install anonymous UUID for the feedback form, generated once and
     * persisted so repeat feedback can be cross-checked without identifying the
     * user. Independent of the Samosa account — used when not signed in.
     */
    fun anonymousFeedbackId(): String {
        val existing = prefs.getString(KEY_FEEDBACK_ANONYMOUS_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val id = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_FEEDBACK_ANONYMOUS_ID, id).apply()
        return id
    }

    private companion object {
        const val KEY_PROVIDER = "llm_provider"
        const val KEY_API_KEY = "api_key"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_SAMOSA_TOKEN = "samosa_session_token"
        const val KEY_SAMOSA_EMAIL = "samosa_email"
        const val KEY_SUB_AGENT_MODEL = "sub_agent_model"
        const val KEY_NAVIGATOR_MODEL = "navigator_model"
        const val KEY_MAX_TOOL_ROUNDS = "max_tool_rounds"
        const val KEY_MAX_REPEATED_TOOL_CALLS = "max_repeated_tool_calls"
        const val KEY_MAX_NAVIGATION_TOOL_CALLS = "max_navigation_tool_calls"
        const val KEY_MAX_CONSECUTIVE_DELEGATIONS = "max_consecutive_delegations"
        const val KEY_MAX_CONTEXT_TOKENS = "max_context_tokens"
        const val KEY_API_TIMEOUT = "api_timeout"
        const val KEY_TTS_PROVIDER = "tts_provider"
        const val KEY_TTS_API_URL = "tts_api_url"
        const val KEY_TTS_API_KEY = "tts_api_key"
        const val KEY_TTS_API_MODEL = "tts_api_model"
        const val KEY_TTS_VOICE = "tts_voice"
        const val KEY_STT_PROVIDER = "stt_provider"
        const val KEY_STT_API_URL = "stt_api_url"
        const val KEY_STT_API_KEY = "stt_api_key"
        const val KEY_STT_API_MODEL = "stt_api_model"
        const val KEY_STT_LANGUAGE = "stt_language"
        const val KEY_AUTO_READ = "auto_read"
        const val KEY_NOTIFY_VIBRATION = "notify_vibration"
        const val KEY_NOTIFY_CHIME = "notify_chime"
        const val KEY_ASSISTIVE_BALL = "assistive_ball_enabled"
        const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        const val KEY_WAKE_WORD_SENSITIVITY = "wake_word_sensitivity"
        const val KEY_WAKE_WORD_LISTENING_MODE = "wake_word_listening_mode"
        const val KEY_SERVER_MESSAGES_ENABLED = "server_messages_enabled"
        const val KEY_SERVER_MESSAGES_LAST_FETCHED = "server_messages_last_fetched"
        const val KEY_SERVER_MESSAGES_ETAG = "server_messages_etag"
        const val KEY_SKIN_ID = "skin_id"

        /** Only read by [migrateSkinId]; never written any more. */
        const val KEY_LEGACY_THEME_MODE = "theme_mode"
        const val KEY_DISABLED_SKILLS = "disabled_skills"
        const val KEY_DISABLED_CONNECTORS = "disabled_connectors"
        const val KEY_CONNECTOR_AUTO_REFRESH_INTERVAL = "connector_auto_refresh_interval"
        const val KEY_CONNECTOR_LAST_REFRESHED = "connector_last_refreshed"
        const val KEY_PROACTIVE_ENABLED = "proactive_enabled"
        const val KEY_PROACTIVE_SCAN_SCREEN = "proactive_scan_screen"
        const val KEY_PROACTIVE_SCAN_CLIPBOARD = "proactive_scan_clipboard"
        const val KEY_PROACTIVE_SCAN_NOTIFICATIONS = "proactive_scan_notifications"
        const val KEY_PROACTIVE_OTP_ENABLED = "proactive_otp_enabled"
        const val KEY_PROACTIVE_AUTO_COPY_OTP = "proactive_auto_copy_otp"
        const val KEY_PROACTIVE_BLACKLIST = "proactive_blacklist"
        const val KEY_USER_NAME = "user_name"
        const val KEY_USER_LOCATION = "user_location"
        const val KEY_USER_OCCUPATION = "user_occupation"
        const val KEY_USER_BACKGROUND = "user_background"
        const val KEY_USER_RESPONSE_STYLE = "user_response_style"
        const val KEY_PREFERRED_LANGUAGE = "preferred_language"
        const val KEY_PREFERRED_CURRENCY = "preferred_currency"
        const val KEY_COMMUNITY_SKILL_HOSTS = "community_skill_hosts"
        const val KEY_LEGAL_ACCEPTED_VERSION = "legal_accepted_version"
        const val KEY_ONBOARDING_DONE = "onboarding_completed"
        const val KEY_TOUR_STEP = "tour_step_id"
        const val KEY_ONBOARDING_VERSION = "onboarding_version"
        const val KEY_FEEDBACK_ANONYMOUS_ID = "feedback_anonymous_id"
        val defaultCommunitySkillHosts: Set<String> = setOf(BuildConfig.SAMOSA_SKILL_HOST)
    }
}

/**
 * Bump this whenever any of the three legal documents in `assets/legal/`
 * changes in a way the user must re-acknowledge. The first-launch consent
 * dialog re-prompts whenever the stored accepted version is older than this
 * constant, so non-material edits (typo fixes, formatting) should leave it
 * unchanged.
 */
const val LEGAL_VERSION: String = "2"
