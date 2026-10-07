package com.gotcha.tools

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.gotcha.service.GotchaAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The accessibility probe, which used to exist in three drifting copies —
 * [DeviceCapabilities], `AccessibilityTool` and `PermissionItems` each read the
 * settings string their own way and each got a different answer. When they
 * disagreed, the tools were gated on one answer while the `<env>` block reported
 * another, and a disabled setting surfaced as an unrelated failure (issue #76).
 *
 * No service can be bound under Robolectric, so `instance` stays null throughout
 * and these tests pin the settings-reading half: exactly the half that was wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AccessibilityCapabilityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val longForm get() = "${context.packageName}/com.gotcha.service.GotchaAccessibilityService"
    private val shortForm get() = "${context.packageName}/.service.GotchaAccessibilityService"

    @Before
    fun clearSetting() {
        write(services = "", masterSwitch = 0)
    }

    private fun write(services: String, masterSwitch: Int) {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            services
        )
        Settings.Secure.putInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            masterSwitch
        )
    }

    @Test
    fun `the flattened long form counts as enabled`() {
        write(services = longForm, masterSwitch = 1)
        assertTrue(DeviceCapabilities.accessibilityEnabled(context))
    }

    @Test
    fun `the short form counts as enabled`() {
        // Written by a backup restore, by `adb settings put`, and by some OEM
        // ROMs. Matching only the long form reported "off" on a device where the
        // service was bound and working — the model then told the user to enable
        // something that was already on.
        write(services = shortForm, masterSwitch = 1)
        assertTrue(DeviceCapabilities.accessibilityEnabled(context))
    }

    @Test
    fun `our entry is found among other enabled services`() {
        write(services = "com.other/.A11yService:$longForm:com.third/.Svc", masterSwitch = 1)
        assertTrue(DeviceCapabilities.accessibilityEnabled(context))
    }

    @Test
    fun `the master switch being off means disabled even while we are listed`() {
        // The framework leaves ENABLED_ACCESSIBILITY_SERVICES populated when the
        // master switch goes off, so reading only the list reports a service
        // Android has already stopped — tools exposed, every call failing.
        write(services = longForm, masterSwitch = 0)
        assertFalse(DeviceCapabilities.accessibilityEnabled(context))
    }

    @Test
    fun `another app's service does not count as ours`() {
        write(services = "com.other/.GotchaAccessibilityService", masterSwitch = 1)
        assertFalse(DeviceCapabilities.accessibilityEnabled(context))
    }

    @Test
    fun `an empty setting is disabled, not a crash`() {
        write(services = "", masterSwitch = 1)
        assertFalse(DeviceCapabilities.accessibilityEnabled(context))
    }

    @Test
    fun `switched on but unbound is its own state`() {
        // The state that produced the wrong advice: "enabled" by the setting, but
        // with no service to serve a single call. It must not read as OFF (which
        // would tell the user to enable an already-enabled service) and must not
        // read as AVAILABLE (which would expose tools that cannot work).
        write(services = longForm, masterSwitch = 1)
        assertEquals(null, GotchaAccessibilityService.instance)
        assertEquals(
            AccessibilityState.ENABLED_BUT_NOT_BOUND,
            DeviceCapabilities.accessibilityState(context)
        )
    }

    @Test
    fun `switched off reads as off`() {
        write(services = "", masterSwitch = 0)
        assertEquals(AccessibilityState.OFF, DeviceCapabilities.accessibilityState(context))
    }

    @Test
    fun `an unbound service withholds the accessibility tools`() {
        // The gate is binding, not the setting. Offering tap/read_screen against
        // an unbound service is what let AppNavigatorSession grind through every
        // step and report "reached N steps without completing the task".
        write(services = longForm, masterSwitch = 1)
        val hidden = DeviceCapabilities.hiddenToolNames(context)
        assertTrue("read_screen" in hidden)
        assertTrue("tap" in hidden)
        assertTrue("navigate_app" in hidden)
    }

    @Test
    fun `the permissions screen still shows a granted-but-unbound service as granted`() {
        // "Has the user granted it" is a different question from "can it serve a
        // call": an Android-unbound service must not make the toggle read as off.
        write(services = longForm, masterSwitch = 1)
        assertTrue(com.gotcha.ui.isAccessibilityGranted(context))
    }
}
