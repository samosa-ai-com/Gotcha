package com.gotcha

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gotcha.testutil.MockLlm
import com.gotcha.testutil.TestSeed
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Termux guided-setup page, smoke-tested on the CI emulator where Termux is
 * never installed: the checklist must render and offer the install action. The
 * installed / configured branches need a real Termux and are QA'd by hand (see
 * docs/termux-setup.md).
 */
@RunWith(AndroidJUnit4::class)
class TermuxSetupFlowTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val mockLlm = MockLlm()

    @After
    fun tearDown() {
        scenario?.close()
        mockLlm.shutdown()
    }

    @Test
    fun termuxPage_guidesInstallWhenTermuxIsAbsent() {
        mockLlm.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TestSeed.seedConfigured(context, baseUrl = mockLlm.baseUrl)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        // Chat home → drawer → Settings → Termux (Linux shell).
        composeRule.onNodeWithContentDescription("Open menu").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_termux_row").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Install Termux").assertExists()
        composeRule.onNodeWithText("Termux is not installed.").assertExists()
        composeRule.onNodeWithText("Install Termux from F-Droid").assertExists()
    }
}
