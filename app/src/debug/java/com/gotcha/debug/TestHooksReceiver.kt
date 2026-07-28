package com.gotcha.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gotcha.MainActivity
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository

/**
 * Debug-only broadcast receiver that seeds app settings for automated testing.
 * Absent from release builds. Bypasses the UI entirely so instrumented tests and
 * Maestro flows can configure the app before launch without touching
 * EncryptedSharedPreferences directly (seeding must happen in-process, since the
 * prefs file is encrypted with an AndroidKeystore-backed key).
 *
 * Invoke with an explicit component to bypass API-26+ implicit-broadcast limits:
 * adb shell am broadcast -n com.gotcha/com.gotcha.debug.TestHooksReceiver \
 *   -a com.gotcha.debug.SEED_SETTINGS \
 *   --es base_url "http://localhost:8080/v1/" --es api_key test --es model test-model
 */
class TestHooksReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SEED_SETTINGS -> seedSettings(context, intent)
            ACTION_SET_APPEARANCE -> setAppearance(context, intent)
        }
    }

    /**
     * Sets the theme from adb, and logs what the app will actually paint.
     *
     * Appearance bugs are hard to tell apart from the outside: a skin whose
     * wallpaper is missing and a skin the device has quietly downgraded to solid
     * panels look identical in a screenshot. This makes the inputs settable and
     * the resolved state readable without driving the UI.
     */
    private fun setAppearance(context: Context, intent: Intent) {
        val repository = SettingsRepository(context)
        val current = repository.load()
        val updated = current.copy(
            skinId = intent.getStringExtra(EXTRA_SKIN) ?: current.skinId,
            reduceTransparency = intent.getBooleanExtra(
                EXTRA_REDUCE_TRANSPARENCY,
                current.reduceTransparency
            ),
            frostPercent = intent.getIntExtra(EXTRA_FROST, current.frostPercent)
        )
        repository.save(updated)
        Log.i(
            TAG,
            "appearance: skin=${updated.skinId} " +
                "reduceTransparency=${updated.reduceTransparency} " +
                "frost=${updated.frostPercent}"
        )
    }

    private fun seedSettings(context: Context, intent: Intent) {
        val repository = SettingsRepository(context)
        val current = repository.load()
        repository.save(
            current.copy(
                provider = LlmProvider.OPENAI_COMPATIBLE,
                baseUrl = intent.getStringExtra(EXTRA_BASE_URL) ?: current.baseUrl,
                apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: current.apiKey,
                model = intent.getStringExtra(EXTRA_MODEL) ?: current.model,
                assistiveBallEnabled = intent.getBooleanExtra(
                    EXTRA_ASSISTIVE_BALL,
                    current.assistiveBallEnabled
                )
            )
        )

        repository.prefs.edit()
            .putBoolean(MainActivity.KEY_FIRST_LAUNCH_DONE, true)
            .putBoolean(MainActivity.KEY_SUPPRESS_MEDIA_PROJECTION_PROMPT, true)
            .apply()
    }

    companion object {
        private const val TAG = "GotchaTestHooks"
        const val ACTION_SEED_SETTINGS = "com.gotcha.debug.SEED_SETTINGS"
        const val ACTION_SET_APPEARANCE = "com.gotcha.debug.SET_APPEARANCE"
        const val EXTRA_SKIN = "skin"
        const val EXTRA_REDUCE_TRANSPARENCY = "reduce_transparency"
        const val EXTRA_FROST = "frost"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_MODEL = "model"
        const val EXTRA_ASSISTIVE_BALL = "assistive_ball"
    }
}

/** Convenience for in-process callers (androidTest) that want the same seeding logic. */
fun seedTestSettings(
    context: Context,
    baseUrl: String,
    apiKey: String = "test",
    model: String = "test-model",
    assistiveBallEnabled: Boolean = false
) {
    val repository = SettingsRepository(context)
    repository.save(
        Settings(
            provider = LlmProvider.OPENAI_COMPATIBLE,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            assistiveBallEnabled = assistiveBallEnabled
        )
    )
    repository.prefs.edit()
        .putBoolean(MainActivity.KEY_FIRST_LAUNCH_DONE, true)
        .putBoolean(MainActivity.KEY_SUPPRESS_MEDIA_PROJECTION_PROMPT, true)
        .apply()
}
