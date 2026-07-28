package com.gotcha

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

        // AI Configuration is its own page now, reached from the settings home list.
        composeRule.onNodeWithTag("settings_ai_config_row").performClick()
        composeRule.waitForIdle()

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
}
