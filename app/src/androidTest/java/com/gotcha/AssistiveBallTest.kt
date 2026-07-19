package com.gotcha

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gotcha.service.AssistiveBallService
import com.gotcha.testutil.MockLlm
import com.gotcha.testutil.ShellPermissions
import com.gotcha.testutil.TestSeed
import com.gotcha.ui.ASSISTIVE_BALL_CONTENT_DESCRIPTION
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the assistive-ball overlay, which Espresso/Compose test cannot see
 * (SYSTEM_ALERT_WINDOW is a separate window) — UiAutomator walks the full window
 * hierarchy instead.
 */
@RunWith(AndroidJUnit4::class)
class AssistiveBallTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    // AssistiveBallService starts as a microphone-type foreground service; on
    // API 34+ that throws SecurityException unless RECORD_AUDIO is granted at
    // start time. The real app requests it during first-launch onboarding,
    // which TestSeed skips — so grant it here to match the state a real user
    // is in when the ball toggle is reachable. (Passed locally only because
    // reused GMD AVDs kept an earlier grant; fresh CI devices crashed.)
    @get:Rule
    val grantMic: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private var scenario: ActivityScenario<MainActivity>? = null
    private val mockLlm = MockLlm()
    private lateinit var device: UiDevice

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .startService(AssistiveBallService.stopIntent(InstrumentationRegistry.getInstrumentation().targetContext))
        // device.pressHome() + relaunching via the ball's "Open App" (a new task, outside
        // ActivityScenario's control) leaves ActivityScenario's tracked lifecycle stale;
        // close() then throws a spurious NPE (androidx.test does not support external
        // navigation interleaved with an owned ActivityScenario). Tear-down should not
        // fail the test over that.
        runCatching { scenario?.close() }
        mockLlm.shutdown()
        ShellPermissions.revokeOverlay()
    }

    @Test
    fun enablingBall_showsOverlay_survivesHome_andOpensApp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ShellPermissions.grantOverlay()
        mockLlm.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TestSeed.seedConfigured(context, baseUrl = mockLlm.baseUrl)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("chat_input").assertExists()
        composeRule.onNode(hasContentDescription("Turn on assistive ball")).performClick()
        composeRule.waitForIdle()

        assertTrue(
            "Ball overlay did not appear",
            device.wait(Until.hasObject(By.desc(ASSISTIVE_BALL_CONTENT_DESCRIPTION)), 5_000)
        )

        device.pressHome()
        assertTrue(
            "Ball did not survive returning home",
            device.wait(Until.hasObject(By.desc(ASSISTIVE_BALL_CONTENT_DESCRIPTION)), 5_000)
        )

        // The ball auto-docks to the screen edge (mostly off-screen, only a thin
        // peek sliver visible) ~2.5s after appearing, racing against how long the
        // waits above took. Re-locate + retry the tap a few times rather than
        // depend on winning that race on the first attempt.
        var menuOpened = false
        repeat(3) {
            if (!menuOpened) {
                device.findObject(By.desc(ASSISTIVE_BALL_CONTENT_DESCRIPTION))?.click()
                menuOpened = device.wait(Until.hasObject(By.textContains("Open App")), 2_000)
            }
        }
        assertTrue("Ball menu did not open", menuOpened)
        device.findObject(By.textContains("Open App")).click()

        assertTrue(
            "MainActivity did not foreground after tapping Open App",
            device.wait(Until.hasObject(By.pkg("com.gotcha").depth(0)), 5_000)
        )
        assertEquals(true, AssistiveBallService.isRunning.value)
    }
}
