package com.gotcha

import android.content.Intent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gotcha.data.SettingsRepository
import com.gotcha.service.AssistiveBallService
import com.gotcha.testutil.MockLlm
import com.gotcha.testutil.ShellPermissions
import com.gotcha.testutil.TestSeed
import com.gotcha.tools.WakeWordBootReceiver
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for issue #148: enabling the floating ball without
 * RECORD_AUDIO killed the app with SecurityException from startForeground.
 * The toggle must ask first, and every unguarded start path must degrade to
 * "ball off" instead of crashing.
 */
@RunWith(AndroidJUnit4::class)
class AssistiveBallMicPermissionTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val mockLlm = MockLlm()

    /**
     * Deny the mic, or skip the test. Revoking a HELD runtime permission
     * kills our own process ("permissions revoked"), taking the test runner
     * down with it — so when an earlier class (e.g. AssistiveBallTest's
     * GrantPermissionRule) already granted it, skip instead of revoking.
     * Revoking an already-denied permission is a no-op and kills nothing.
     */
    private fun requireMicDenied(context: android.content.Context) {
        val granted = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        assumeFalse("mic already granted by an earlier test class", granted)
        ShellPermissions.revokeMic()
    }

    @After
    fun tearDown() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.startService(AssistiveBallService.stopIntent(ctx))
        runCatching { scenario?.close() }
        mockLlm.shutdown()
        // Restore mic to denied WITHOUT granting first: revoking a held
        // runtime permission kills our own process ("permissions revoked"),
        // taking the instrumentation runner down with it. Revoking an
        // already-denied permission is a no-op and kills nothing.
        ShellPermissions.revokeMic()
        ShellPermissions.revokeOverlay()
    }

    @Test
    fun serviceStartWithoutMic_survivesAndPersistsBallOff() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        mockLlm.start()
        TestSeed.seedConfigured(context, baseUrl = mockLlm.baseUrl)
        // Ball + wake word on, so the fallback's persist-off is observable.
        val repository = SettingsRepository(context)
        repository.save(repository.load().copy(assistiveBallEnabled = true, wakeWordEnabled = true))
        requireMicDenied(context)

        context.startForegroundService(AssistiveBallService.startIntent(context))
        Thread.sleep(3000)

        // Reaching here means the process survived the start. The ball must
        // have degraded to off and persisted that state.
        assertFalse(AssistiveBallService.isRunning.value)
        val settings = repository.load()
        assertFalse(settings.assistiveBallEnabled)
        assertFalse(settings.wakeWordEnabled)
    }

    @Test
    fun bootRestartWithoutMic_doesNotStart() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        mockLlm.start()
        TestSeed.seedConfigured(context, baseUrl = mockLlm.baseUrl)
        val repository = SettingsRepository(context)
        repository.save(repository.load().copy(assistiveBallEnabled = true, wakeWordEnabled = true))
        requireMicDenied(context)

        WakeWordBootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        Thread.sleep(2000)

        assertFalse(AssistiveBallService.isRunning.value)
    }

    @Test
    fun toggleWithoutMic_showsRationaleAndStaysOffOnDeny() {
        ShellPermissions.grantOverlay()
        mockLlm.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TestSeed.seedConfigured(context, baseUrl = mockLlm.baseUrl)
        requireMicDenied(context)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_input").assertExists()

        // The toggle lives in Settings ▸ Assistive Ball and Wake Word.
        composeRule.onNode(hasContentDescription("Open menu")).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_assistive_ball_row").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_assistive_ball").performClick()
        composeRule.waitForIdle()

        // The mic rationale appears instead of the ball starting.
        composeRule.onNodeWithText(
            "Gotcha needs the microphone to hear you \u2014 for voice input, recording, and the \"Hey Gotcha\" wake word."
        ).assertExists()
        composeRule.onNodeWithText("Not now").performClick()
        composeRule.waitForIdle()

        assertFalse(AssistiveBallService.isRunning.value)
        assertFalse(SettingsRepository(context).load().assistiveBallEnabled)
    }
}
