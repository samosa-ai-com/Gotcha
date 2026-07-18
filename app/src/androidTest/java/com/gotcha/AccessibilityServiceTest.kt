package com.gotcha

import android.app.UiAutomation
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gotcha.service.GotchaAccessibilityService
import com.gotcha.testutil.ShellPermissions
import com.gotcha.testutil.TestSeed
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Separate class from other UiAutomator tests: UiAutomation suppresses other
 * accessibility services by default, so FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
 * must be requested before the first UiDevice/UiAutomation acquisition in the
 * process — mixing that with plain UiAutomator tests in one class risks whichever
 * runs first winning the flag for the whole class.
 *
 * Known limitation (confirmed on API 33 emulator, 2026-07-19): binding reliably
 * fails when the app is under active self-instrumentation, even with the app
 * process already warm and un-stopped (`dumpsys package` showed stopped=false)
 * and after waiting 45s. The identical sequence — enable the secure setting after
 * launching the app once — binds in ~0-5s via plain `adb shell` outside
 * instrumentation. AccessibilityManagerService appears to treat the instrumented
 * process differently for new service binds; this is a known rough edge in the
 * Android testing ecosystem, not a bug in GotchaAccessibilityService itself
 * (`instance` is confirmed non-null immediately when bound normally). Left in the
 * suite as a best-effort check rather than removed — worth revisiting with the
 * `android:process` isolation approach or Test Orchestrator if it needs to be
 * made reliable.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityServiceTest {

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }

    @After
    fun tearDown() {
        ShellPermissions.disableAccessibilityService()
    }

    @Test
    fun enablingViaSecureSettings_connectsService() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TestSeed.seedUnconfigured(context)
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        ShellPermissions.enableAccessibilityService()
        val connected = pollUntil(timeoutMs = 10_000) {
            GotchaAccessibilityService.instance != null
        }

        scenario.close()
        assertTrue("GotchaAccessibilityService did not connect", connected)
    }

    private fun pollUntil(timeoutMs: Long, intervalMs: Long = 200, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(intervalMs)
        }
        return condition()
    }
}
