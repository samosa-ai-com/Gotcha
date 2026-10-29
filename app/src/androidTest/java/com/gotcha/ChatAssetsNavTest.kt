package com.gotcha

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gotcha.testutil.MockLlm
import com.gotcha.testutil.TestSeed
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Chat ⋮ menu ▸ Chat assets opens the per-chat file list; back returns. */
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
    }

    @Test
    fun chatMenu_assets_opensEmptyAssets_andBackReturns() {
        mockLlm.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TestSeed.seedConfigured(context, baseUrl = mockLlm.baseUrl)
        // The ⋮ menu only renders on an open chat, and home is message-less:
        // seed one exchanged line and open it from the drawer.
        kotlinx.coroutines.runBlocking {
            com.gotcha.data.ChatHistoryRepository(context).saveSession(
                com.gotcha.data.ChatSession(
                    id = "assets-nav-test",
                    title = "Assets nav",
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
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_input").assertExists()
        composeRule.onNode(hasContentDescription("Open menu")).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Assets nav").performClick()
        composeRule.waitForIdle()

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
}
