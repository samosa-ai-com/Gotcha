package com.gotcha.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.gotcha.audio.AudioProvider

data class Settings(
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val maxToolRounds: Int = 30,
    val maxContextTokens: Int = 40000,
    val apiTimeoutSeconds: Long = 0L,
    // TTS / STT settings
    val ttsProvider: AudioProvider = AudioProvider.NONE,
    val ttsApiBaseUrl: String = "",
    val ttsApiModel: String = "",
    val sttProvider: AudioProvider = AudioProvider.NONE,
    val sttApiBaseUrl: String = "",
    val sttApiModel: String = "",
    val autoReadReplies: Boolean = false,
    val assistiveBallEnabled: Boolean = false
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank()

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

    fun load(): Settings = Settings(
        apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
        baseUrl = prefs.getString(KEY_BASE_URL, Settings.DEFAULT_BASE_URL)
            ?: Settings.DEFAULT_BASE_URL,
        model = prefs.getString(KEY_MODEL, Settings.DEFAULT_MODEL) ?: Settings.DEFAULT_MODEL,
        maxToolRounds = prefs.getInt(KEY_MAX_TOOL_ROUNDS, 30),
        maxContextTokens = prefs.getInt(KEY_MAX_CONTEXT_TOKENS, 40000),
        apiTimeoutSeconds = prefs.getLong(KEY_API_TIMEOUT, 0L),
        ttsProvider = AudioProvider.valueOf(prefs.getString(KEY_TTS_PROVIDER, "NONE") ?: "NONE"),
        ttsApiBaseUrl = prefs.getString(KEY_TTS_API_URL, "") ?: "",
        ttsApiModel = prefs.getString(KEY_TTS_API_MODEL, "") ?: "",
        sttProvider = AudioProvider.valueOf(prefs.getString(KEY_STT_PROVIDER, "NONE") ?: "NONE"),
        sttApiBaseUrl = prefs.getString(KEY_STT_API_URL, "") ?: "",
        sttApiModel = prefs.getString(KEY_STT_API_MODEL, "") ?: "",
        autoReadReplies = prefs.getBoolean(KEY_AUTO_READ, false),
        assistiveBallEnabled = prefs.getBoolean(KEY_ASSISTIVE_BALL, false)
    )

    fun save(settings: Settings) {
        prefs.edit()
            .putString(KEY_API_KEY, settings.apiKey)
            .putString(KEY_BASE_URL, settings.baseUrl)
            .putString(KEY_MODEL, settings.model)
            .putInt(KEY_MAX_TOOL_ROUNDS, settings.maxToolRounds)
            .putInt(KEY_MAX_CONTEXT_TOKENS, settings.maxContextTokens)
            .putLong(KEY_API_TIMEOUT, settings.apiTimeoutSeconds)
            .putString(KEY_TTS_PROVIDER, settings.ttsProvider.name)
            .putString(KEY_TTS_API_URL, settings.ttsApiBaseUrl)
            .putString(KEY_TTS_API_MODEL, settings.ttsApiModel)
            .putString(KEY_STT_PROVIDER, settings.sttProvider.name)
            .putString(KEY_STT_API_URL, settings.sttApiBaseUrl)
            .putString(KEY_STT_API_MODEL, settings.sttApiModel)
            .putBoolean(KEY_AUTO_READ, settings.autoReadReplies)
            .putBoolean(KEY_ASSISTIVE_BALL, settings.assistiveBallEnabled)
            .apply()
    }

    private companion object {
        const val KEY_API_KEY = "api_key"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_MAX_TOOL_ROUNDS = "max_tool_rounds"
        const val KEY_MAX_CONTEXT_TOKENS = "max_context_tokens"
        const val KEY_API_TIMEOUT = "api_timeout"
        const val KEY_TTS_PROVIDER = "tts_provider"
        const val KEY_TTS_API_URL = "tts_api_url"
        const val KEY_TTS_API_MODEL = "tts_api_model"
        const val KEY_STT_PROVIDER = "stt_provider"
        const val KEY_STT_API_URL = "stt_api_url"
        const val KEY_STT_API_MODEL = "stt_api_model"
        const val KEY_AUTO_READ = "auto_read"
        const val KEY_ASSISTIVE_BALL = "assistive_ball_enabled"
    }
}
