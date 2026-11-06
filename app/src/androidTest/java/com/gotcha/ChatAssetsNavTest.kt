package com.gotcha

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gotcha.data.GotchaStorage
import com.gotcha.testutil.MockLlm
import com.gotcha.testutil.TestSeed
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Chat ⋮ menu ▸ Chat assets: browse folders, long-press for actions. */
@RunWith(AndroidJUnit4::class)
class ChatAssetsNavTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val mockLlm = MockLlm()

    @After
    fun tearDown() {
        runCatching { scenario?.close() }
        mockLlm.shutdown()
        GotchaStorage.resetRootPathForTests()
    }

    @Test
    fun chatMenu_assets_opensEmptyAssets_andBackReturns() {
        mockLlm.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TestSeed.seedConfigured(context, baseUrl = mockLlm.baseUrl)
        seedSessionWithMessage(context, "assets-nav-empty", "Assets empty")
        openChatFromDrawer("Assets empty")

        // A fresh seeded chat has no folder: the empty state must show.
        composeRule.onNodeWithTag("chat_options").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_assets").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_assets_empty").assertExists()

        composeRule.onNodeWithTag("chat_assets_back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_input").assertExists()
    }

    @Test
    fun chatMenu_assets_browsesFoldersAndDeletes() {
        mockLlm.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TestSeed.seedConfigured(context, baseUrl = mockLlm.baseUrl)
        seedSessionWithMessage(context, "assets-nav-test", "Assets nav")
        // Point the chat-folder root at app-private storage: shared external
        // storage needs All-files access the test device may not have, and the
        // screen reads this same override in-process.
        GotchaStorage.rootPath = File(context.filesDir, "assets-test-root").absolutePath
        val chatDir = GotchaStorage.findOrCreateChatDir("assets-nav-test", "Assets nav")
        File(chatDir, "docs").mkdirs()
        File(chatDir, "docs/report.txt").writeText("hello")
        openChatFromDrawer("Assets nav")

        composeRule.onNodeWithTag("chat_options").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_assets").performClick()
        composeRule.waitForIdle()

        // Into the subfolder, long-press the file, delete it with confirm.
        composeRule.onNodeWithText("docs").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("report.txt").assertExists()
        composeRule.onNodeWithText("report.txt").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_asset_share").assertExists()
        composeRule.onNodeWithTag("chat_asset_delete").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_asset_delete_yes").performClick()
        composeRule.waitUntil(5000) {
            composeRule.onAllNodesWithText("report.txt").fetchSemanticsNodes().isEmpty()
        }

        // Back up to the chat folder: the file is gone, the folder remains.
        composeRule.onNodeWithTag("chat_assets_back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("docs").assertExists()
    }

    @Test
    fun chatMenu_assets_missingFolder_showsEmpty() {
        mockLlm.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TestSeed.seedConfigured(context, baseUrl = mockLlm.baseUrl)
        seedSessionWithMessage(context, "assets-nav-gone", "Assets gone")
        GotchaStorage.rootPath = File(context.filesDir, "assets-test-root").absolutePath
        val chatDir = GotchaStorage.findOrCreateChatDir("assets-nav-gone", "Assets gone")
        File(chatDir, "vanished").mkdirs()
        File(chatDir, "vanished/old.txt").writeText("stale")
        openChatFromDrawer("Assets gone")

        composeRule.onNodeWithTag("chat_options").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_assets").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("vanished").assertExists()

        // The folder disappears between listing and tap (an agent run or an
        // external file manager beat us to it): entering must show the empty
        // state, never the previous listing's actionable rows.
        File(chatDir, "vanished").deleteRecursively()
        composeRule.onNodeWithText("vanished").performClick()
        composeRule.waitUntil(5000) {
            composeRule.onAllNodesWithTag("chat_assets_empty")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("old.txt").assertDoesNotExist()
    }

    private fun seedSessionWithMessage(context: android.content.Context, id: String, title: String) {
        kotlinx.coroutines.runBlocking {
            com.gotcha.data.ChatHistoryRepository(context).saveSession(
                com.gotcha.data.ChatSession(
                    id = id,
                    title = title,
                    lastModified = System.currentTimeMillis(),
                    messages = listOf(
                        com.gotcha.llm.ChatMessage(
                            role = "user",
                            content = kotlinx.serialization.json.JsonPrimitive("hello")
                        )
                    ),
                    displayMessages = listOf(
                        com.gotcha.agent.UiMessage(
                            id = 1,
                            kind = com.gotcha.agent.MessageKind.USER,
                            text = "hello"
                        )
                    )
                ),
                touch = true
            )
        }
    }

    private fun openChatFromDrawer(title: String) {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_input").assertExists()
        composeRule.onNode(hasContentDescription("Open menu")).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(title).performClick()
        composeRule.waitForIdle()
    }
}
