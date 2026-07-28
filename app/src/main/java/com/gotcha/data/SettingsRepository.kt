package com.gotcha.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.gotcha.audio.AudioProvider

data class Settings(
    // Which LLM backend is active. Defaults to the original OpenAI-compatible flow.
    val provider: LlmProvider = LlmProvider.OPENAI_COMPATIBLE,
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    // Samosa AI: Samosa AI backend session JWT + connected Google account (never
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
    // ---- Appearance (Settings ▸ Appearance) ----
    /**
     * Which skin is painted. Stored as the id string rather than an enum so a
     * build that drops a skin degrades to the default instead of throwing on a
     * value it no longer knows.
     */
    val skinId: String = "deepspace",
    /**
     * Solid panels, no wallpaper. Android has no system-level "reduce
     * transparency", so this is the only way for someone to ask for one.
     */
    val reduceTransparency: Boolean = false,
    /**
     * How much of the skin's own frost to apply, as a percentage. 100 is what
     * the skin was designed with; 0 leaves the wallpaper sharp behind the glass.
     * Only bites where the device can blur live — see GlassTier.
     */
    val frostPercent: Int = 100,
    val disabledSkills: Set<String> = emptySet(),
    /**
     * Ids of connectors the user switched off. Credentials survive (re-enabling
     * needs no re-auth), but the connector contributes no tools and its skills
     * stop being injected.
     */
    val disabledConnectors: Set<String> = emptySet(),
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
    val communitySkillHosts: Set<String> = setOf("samosa-ai.example", "samosa.ai")
) {
    /** True when the active provider has everything it needs to make requests. */
    val isConfigured: Boolean
        get() = when (provider) {
            LlmProvider.SAMOSA_AI -> samosaSessionToken.isNotBlank()
            LlmProvider.OPENAI_COMPATIBLE -> baseUrl.isNotBlank()
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
        const val DEFAULT_MODEL = "gpt-4o"
    }
}

/** Stores credentials in EncryptedSharedPreferences (PRD R6). Never logged. */
class SettingsRepository(context: Context) {

    val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "gotcha_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
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

    /**
     * The chosen skin, migrating anyone who was on the old Deep Space + "Light"
     * combination. That pairing used to be two settings; it is one skin now, and
     * silently moving them to the dark one would repaint an app they had
     * deliberately set light.
     */
    private fun resolvedSkinId(): String {
        val stored = prefs.getString(KEY_SKIN_ID, null)
        if (stored != null) return stored
        val legacy = prefs.getString(KEY_LEGACY_THEME_MODE, null)
        val id = if (legacy == "LIGHT") SKIN_DEEP_SPACE_LIGHT else SKIN_DEEP_SPACE_DARK
        prefs.edit().putString(KEY_SKIN_ID, id).apply()
        return id
    }

    fun load(): Settings = Settings(
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
        skinId = resolvedSkinId(),
        reduceTransparency = prefs.getBoolean(KEY_REDUCE_TRANSPARENCY, false),
        frostPercent = prefs.getInt(KEY_FROST_PERCENT, 100),
        disabledSkills = stringSet(KEY_DISABLED_SKILLS),
        disabledConnectors = stringSet(KEY_DISABLED_CONNECTORS),
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
        communitySkillHosts = stringSet(KEY_COMMUNITY_SKILL_HOSTS, defaultCommunitySkillHosts)
    )

    fun save(settings: Settings) {
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
            .putString(KEY_SKIN_ID, settings.skinId)
            .putBoolean(KEY_REDUCE_TRANSPARENCY, settings.reduceTransparency)
            .putInt(KEY_FROST_PERCENT, settings.frostPercent)
            .putStringSet(KEY_DISABLED_SKILLS, settings.disabledSkills)
            .putStringSet(KEY_DISABLED_CONNECTORS, settings.disabledConnectors)
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
        const val KEY_SKIN_ID = "skin_id"

        /** Only read by [resolvedSkinId]'s migration; never written any more. */
        const val KEY_LEGACY_THEME_MODE = "theme_mode"
        const val SKIN_DEEP_SPACE_DARK = "deepspace"
        const val SKIN_DEEP_SPACE_LIGHT = "deepspace_light"
        const val KEY_REDUCE_TRANSPARENCY = "reduce_transparency"
        const val KEY_FROST_PERCENT = "frost_percent"
        const val KEY_DISABLED_SKILLS = "disabled_skills"
        const val KEY_DISABLED_CONNECTORS = "disabled_connectors"
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
        val defaultCommunitySkillHosts: Set<String> = setOf("samosa-ai.example", "samosa.ai")
    }
}
