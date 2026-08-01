package com.gotcha.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The assistive ball is built once and then lives as long as the service, so it
 * has to be *told* when the theme changes rather than re-reading it on the next
 * draw. [settingsChangeNotifier] is the telling.
 *
 * The trap it exists to avoid: `EncryptedSharedPreferences` keeps its listener
 * list on the wrapper object, and `create` returns a new wrapper every call. A
 * listener registered through one [SettingsRepository] therefore never hears
 * about a write made through another — and every writer in this app makes its
 * own. Registering on the raw file works because that one *is* shared.
 *
 * What is asserted here is the half that a JVM test can reach: the raw file is
 * process-wide and it notifies. The other half — that the repository encrypts
 * into this same file — is held by [SETTINGS_PREFS_FILE] being the one constant
 * both use, because `EncryptedSharedPreferences` needs the AndroidKeyStore and
 * cannot be constructed under Robolectric at all.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsChangeNotifierTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The framework holds listeners weakly; this outlives the registration. */
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Test
    fun `every caller gets the same preferences object`() {
        assertSame(
            "a per-caller instance would mean a per-caller listener list, which " +
                "is the exact bug this function exists to route around",
            settingsChangeNotifier(context),
            settingsChangeNotifier(context)
        )
    }

    @Test
    fun `a write by one caller reaches a listener registered by another`() {
        val fired = intArrayOf(0)
        val watcher = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> fired[0]++ }
        listener = watcher
        settingsChangeNotifier(context).registerOnSharedPreferenceChangeListener(watcher)

        settingsChangeNotifier(context).edit().putString("some_encrypted_key", "x").apply()

        assertTrue("the notifier must notify", fired[0] > 0)
        settingsChangeNotifier(context).unregisterOnSharedPreferenceChangeListener(watcher)
    }

    @Test
    fun `unregistering stops the notifications`() {
        val fired = intArrayOf(0)
        val watcher = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> fired[0]++ }
        listener = watcher
        val prefs = settingsChangeNotifier(context)
        prefs.registerOnSharedPreferenceChangeListener(watcher)
        prefs.unregisterOnSharedPreferenceChangeListener(watcher)

        prefs.edit().putString("another_key", "y").apply()

        // The ball unregisters on dismiss; a listener that kept firing would be
        // repainting a window that is no longer on screen.
        assertTrue("unregister must actually detach", fired[0] == 0)
    }
}
