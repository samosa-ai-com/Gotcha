package com.gotcha.testutil

import android.content.Context
import com.gotcha.MainActivity
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.debug.seedTestSettings

/**
 * Seeds [com.gotcha.data.SettingsRepository] in-process before the activity under
 * test launches. Instrumentation runs in the target app's process, so it shares the
 * same AndroidKeystore-backed EncryptedSharedPreferences — this must happen
 * *before* `ActivityScenario.launch()`, not via `ActivityScenarioRule`, since the
 * activity reads settings synchronously in `onCreate`.
 */
object TestSeed {

    fun seedConfigured(
        context: Context,
        baseUrl: String,
        apiKey: String = "test",
        model: String = "test-model",
        assistiveBallEnabled: Boolean = false
    ) {
        seedTestSettings(
            context = context,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            assistiveBallEnabled = assistiveBallEnabled
        )
    }

    /** Blank base URL — `Settings.isConfigured` is false, routing MainActivity to Settings. */
    fun seedUnconfigured(context: Context) {
        val repository = SettingsRepository(context)
        repository.save(
            Settings(provider = LlmProvider.OPENAI_COMPATIBLE, baseUrl = "", apiKey = "")
        )
        repository.prefs.edit()
            .putBoolean(MainActivity.KEY_FIRST_LAUNCH_DONE, true)
            .putBoolean(MainActivity.KEY_SUPPRESS_MEDIA_PROJECTION_PROMPT, true)
            .apply()
    }
}
