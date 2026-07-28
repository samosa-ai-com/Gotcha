package com.gotcha

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gotcha.testutil.MockLlm
import com.gotcha.testutil.TestSeed
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeLaunchTest {

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
    fun configuredInstall_landsOnChatRoute() {
        mockLlm.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TestSeed.seedConfigured(context, baseUrl = mockLlm.baseUrl)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("chat_input").assertExists()
    }

    @Test
    fun unconfiguredInstall_landsOnSettingsRoute() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TestSeed.seedUnconfigured(context)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_ai_config_row").assertExists()
    }
}
