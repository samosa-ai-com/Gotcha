package com.gotcha

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
 * The drawer holds destinations (new chat, recents, scheduled tasks,
 * connectors, settings); import and whole-library backup live in
 * Settings ▸ Chats.
 */
@RunWith(AndroidJUnit4::class)
class DrawerSettingsNavTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val mockLlm = MockLlm()

    @After
    fun tearDown() {
        runCatching { scenario?.close() }
        mockLlm.shutdown()
    }

    private fun launch() {
        mockLlm.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TestSeed.seedConfigured(context, baseUrl = mockLlm.baseUrl)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_input").assertExists()
    }

    private fun openDrawer() {
        composeRule.onNode(hasContentDescription("Open menu")).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun drawer_listsScheduledTasks_notImportOrBackup() {
        launch()
        openDrawer()
        composeRule.onNodeWithText("Scheduled tasks").assertExists()
        composeRule.onNodeWithText("Import chats").assertDoesNotExist()
        composeRule.onNodeWithText("Back up all chats").assertDoesNotExist()
    }

    @Test
    fun drawer_scheduledTasks_opensScheduledTasksPage() {
        launch()
        openDrawer()
        composeRule.onNodeWithText("Scheduled tasks").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("scheduled_add_task").assertExists()
    }

    @Test
    fun drawer_scheduledTasks_backReturnsToChat() {
        launch()
        openDrawer()
        composeRule.onNodeWithText("Scheduled tasks").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("scheduled_add_task").assertExists()
        // Its own route like Connectors: back returns to chat, not Settings.
        composeRule.onNodeWithTag("settings_back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_input").assertExists()
    }

    @Test
    fun settings_chatsPage_listsImportAndBackup() {
        launch()
        openDrawer()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_chats_row").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_chats_import").assertExists()
        composeRule.onNodeWithTag("settings_chats_backup_all").assertExists()
    }
}
