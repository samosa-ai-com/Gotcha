package com.gotcha.tools

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

/**
 * `set_brightness`, `get_battery_info`, `toggle_wifi` and `open_app`.
 *
 * The interesting assertions are on the *intents* these tools fire — `toggle_wifi` cannot
 * flip the radio on Android 10+ and must instead open the Wi-Fi panel, and `open_app` must
 * launch with FLAG_ACTIVITY_NEW_TASK or nothing happens from a service context.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class SystemToolTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tool = SystemTool(context)

    private fun lastIntent(): Intent? =
        ShadowApplication.getInstance().nextStartedActivity

    // ---- set_brightness ----

    @Test
    fun `set_brightness rejects out-of-range percentages before touching settings`() {
        assertFalse(tool.setBrightness(-1).success)
        assertFalse(tool.setBrightness(101).success)
    }

    @Test
    fun `set_brightness asks for WRITE_SETTINGS when it is not granted`() {
        // Robolectric's default for WRITE_SETTINGS varies by SDK level; only assert the
        // permission-needed path when the shadow actually reports it as ungranted.
        if (Settings.System.canWrite(context)) return

        val result = tool.setBrightness(50)

        assertFalse(result.message, result.success)
        assertEquals(ToolResult.WRITE_SETTINGS, result.needsPermission)
    }

    @Test
    fun `set_brightness writes the scaled value and switches to manual mode`() {
        if (!Settings.System.canWrite(context)) return

        val result = tool.setBrightness(50)

        assertTrue(result.message, result.success)
        assertEquals(
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        )
        assertEquals(127, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
    }

    // ---- get_battery_info ----

    @Test
    fun `get_battery_info reports a percentage without throwing`() {
        val result = tool.getBatteryInfo()

        assertTrue(result.message, result.success)
        assertTrue("expected a percentage in: ${result.message}", result.message.contains("%"))
    }

    // ---- toggle_wifi ----

    /**
     * Only the permissive branch is covered here.
     *
     * On a real Android 10+ device `setWifiEnabled` is a no-op returning false, and the tool
     * degrades to opening the Wi-Fi panel. Robolectric's ShadowWifiManager honours the call
     * unconditionally — `setChangeWifiStatePermission(false)` does not make it fail — so that
     * fallback cannot be reproduced faithfully at this tier. Asserting it against a permissive
     * shadow would give false confidence, so it stays on the manual checklist instead
     * (see the `toggle_wifi` entry in feature-test-coverage.json).
     */
    @Test
    fun `toggle_wifi reports the transition when the platform allows it`() {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        wifi.isWifiEnabled = false

        val result = tool.toggleWifi(true)

        assertTrue(result.message, result.success)
        assertTrue("expected a state transition in: ${result.message}", result.message.contains("was off"))
        assertTrue(wifi.isWifiEnabled)
    }

    // ---- open_app ----

    @Test
    fun `open_app fails helpfully for an unknown package`() {
        val result = tool.openApp("com.example.definitely.not.installed")

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("No launchable app found"))
        assertTrue(
            "the message should point at a recovery path: ${result.message}",
            result.message.contains("list_installed_apps")
        )
    }

    @Test
    fun `open_app launches an installed package with NEW_TASK`() {
        val packageName = context.packageName
        val launchIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        shadowOf(context.packageManager).addActivityIfNotPresent(
            android.content.ComponentName(packageName, "$packageName.LaunchActivity")
        )
        shadowOf(context.packageManager).addIntentFilterForActivity(
            android.content.ComponentName(packageName, "$packageName.LaunchActivity"),
            android.content.IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        )

        val result = tool.openApp(packageName)

        assertTrue(result.message, result.success)
        val started = lastIntent()
        assertNotNull("no activity was started for $launchIntent", started)
        assertTrue(
            "launch intent needs NEW_TASK",
            started!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0
        )
    }
}
