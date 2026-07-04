package com.gotcha.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class Settings(
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val confirmSensitiveActions: Boolean = true
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1/"
        const val DEFAULT_MODEL = "gpt-4o"
    }
}

/** Stores credentials in EncryptedSharedPreferences (PRD R6). Never logged. */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences by lazy {
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
        confirmSensitiveActions = prefs.getBoolean(KEY_CONFIRM_SENSITIVE, true)
    )

    fun save(settings: Settings) {
        prefs.edit()
            .putString(KEY_API_KEY, settings.apiKey)
            .putString(KEY_BASE_URL, settings.baseUrl)
            .putString(KEY_MODEL, settings.model)
            .putBoolean(KEY_CONFIRM_SENSITIVE, settings.confirmSensitiveActions)
            .apply()
    }

    private companion object {
        const val KEY_API_KEY = "api_key"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_CONFIRM_SENSITIVE = "confirm_sensitive"
    }
}
