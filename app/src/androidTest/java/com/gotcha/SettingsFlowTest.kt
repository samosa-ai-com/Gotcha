package com.gotcha

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gotcha.data.SettingsRepository
import com.gotcha.testutil.TestSeed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsFlowTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val testBaseUrl = "http://localhost:8080/v1/"
    private val testModel = "flow-test-model"

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun enteredSettings_persistAcrossRelaunch() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TestSeed.seedUnconfigured(context)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        // An unconfigured install opens on the AI Configuration page itself, so
        // there is no category row to click through first.
        composeRule.onNodeWithTag("settings_base_url").performTextReplacement(testBaseUrl)
        composeRule.onNodeWithTag("settings_model").performTextReplacement(testModel)
        // The Save button sits below the fold in the scrollable Settings column;
        // performClick() dispatches at the node's laid-out coordinates without
        // scrolling it into view first, so an off-screen click silently no-ops.
        composeRule.onNodeWithTag("settings_save").performScrollTo().performClick()
        composeRule.waitForIdle()

        scenario?.close()

        val persisted = SettingsRepository(context).load()
        assertEquals(testBaseUrl, persisted.baseUrl)
        assertEquals(testModel, persisted.model)

        // Relaunch and confirm the configured route is reached with the saved settings intact.
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_input").assertExists()
    }

    @Test
    fun personalInfo_persistsAcrossRelaunch() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TestSeed.seedUnconfigured(context)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        // First run opens on AI Configuration; Back reaches the category list.
        composeRule.onNodeWithTag("settings_back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_personal_info_row").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_user_name").performTextReplacement("Ada")
        composeRule.onNodeWithTag("settings_user_response_style")
            .performScrollTo()
            .performTextReplacement("No bullet lists.")
        composeRule.onNodeWithTag("settings_save_personal_info").performScrollTo().performClick()
        composeRule.waitForIdle()

        scenario?.close()

        val persisted = SettingsRepository(context).load()
        assertEquals("Ada", persisted.userName)
        assertEquals("No bullet lists.", persisted.userResponseStyle)
    }

    @Test
    fun settingsSubPage_opensFromTheListAndBackReturnsToIt() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TestSeed.seedUnconfigured(context)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        // First run opens on AI Configuration; Back from a sub-page lands on the
        // category list rather than leaving Settings.
        composeRule.onNodeWithTag("settings_base_url").assertExists()
        composeRule.onNodeWithTag("settings_back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_ai_config_row").assertExists()

        // Opening another category replaces the list with that page.
        composeRule.onNodeWithTag("settings_speech_row").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_ai_config_row").assertDoesNotExist()
        composeRule.onNodeWithText("TTS Provider").assertExists()
    }
}
