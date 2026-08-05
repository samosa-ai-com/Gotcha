package com.gotcha

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gotcha.testutil.MOCK_REPLY_OK
import com.gotcha.testutil.MockLlm
import com.gotcha.testutil.TestSeed
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatRoundTripTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val mockLlm = MockLlm()

    @After
    fun tearDown() {
        scenario?.close()
        mockLlm.shutdown()
    }

    /**
     * Type [text], then wait until the send button is actually enabled before
     * clicking. The send button only appears once the composer has text, and it
     * stays disabled while a run is in flight — so this helper inherently waits
     * for the previous run to finish before sending the next message.
     */
    private fun sendText(text: String) {
        composeRule.onNodeWithTag("chat_input").performTextInput(text)
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodes(hasTestTag("send_button") and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("send_button").performClick()
    }

    private fun waitForReply() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodes(hasText(MOCK_REPLY_OK, substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Consumes and returns the request bodies recorded since [previousCount]. */
    private fun drainSince(previousCount: Int): List<String> {
        val bodies = mutableListOf<String>()
        while (mockLlm.server.requestCount > previousCount) {
            mockLlm.server.takeRequest()?.let { bodies.add(it.body.readUtf8()) }
        }
        return bodies
    }

    @Test
    fun sendingPrompt_showsMockReply() {
        mockLlm.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TestSeed.seedConfigured(context, baseUrl = mockLlm.baseUrl, model = "test-model")

        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        sendText("hello")

        waitForReply()

        val request = mockLlm.server.takeRequest()
        assertTrue(request.path?.endsWith("/chat/completions") == true)
        assertTrue(request.body.readUtf8().contains("test-model"))
    }

    @Test
    fun editMessage_truncatesHistoryAndRegenerates() {
        mockLlm.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TestSeed.seedConfigured(context, baseUrl = mockLlm.baseUrl, model = "test-model")

        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        sendText("first prompt")
        waitForReply()
        sendText("second prompt")
        waitForReply()
        // run2's request + the run1 title-generation request must both have landed
        // and the final run cleaned up before we snapshot, so the edit's
        // regeneration is the only new request.
        composeRule.waitUntil(timeoutMillis = 15_000) { mockLlm.server.requestCount >= 3 }
        composeRule.waitForIdle()
        val since = mockLlm.server.requestCount

        composeRule.onNode(hasText("first prompt")).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Edit message").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("chat_input").performTextClearance()
        composeRule.onNodeWithTag("chat_input").performTextInput("EDITED first")
        // Wait for the previous run to finish (the send button stays disabled
        // while busy) so the edit isn't dropped by the busy guard.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodes(hasTestTag("send_button") and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("send_button").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) { mockLlm.server.requestCount > since }

        val bodies = drainSince(since)
        assertTrue(
            "regeneration request must contain the edited prompt",
            bodies.any { it.contains("EDITED first") }
        )
        assertFalse(
            "dropped turn must not be sent to the LLM",
            bodies.any { it.contains("second prompt") }
        )
        assertFalse(
            "old prompt must not be sent to the LLM",
            bodies.any { it.contains("first prompt") }
        )
    }

    @Test
    fun revertToMessage_deletesSubsequentMessages() {
        mockLlm.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TestSeed.seedConfigured(context, baseUrl = mockLlm.baseUrl, model = "test-model")

        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        sendText("first prompt")
        waitForReply()
        sendText("second prompt")
        waitForReply()
        composeRule.waitUntil(timeoutMillis = 15_000) { mockLlm.server.requestCount >= 3 }
        composeRule.waitForIdle()
        val since = mockLlm.server.requestCount

        composeRule.onNode(hasText("first prompt")).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Revert to this message").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Revert").performClick()
        composeRule.waitForIdle()

        assertTrue(
            "reverted bubble must disappear from the transcript",
            composeRule.onAllNodes(hasText("second prompt")).fetchSemanticsNodes().isEmpty()
        )

        sendText("third prompt")
        composeRule.waitUntil(timeoutMillis = 15_000) { mockLlm.server.requestCount > since }

        val bodies = drainSince(since)
        assertTrue(
            "continuing after revert must send the new prompt",
            bodies.any { it.contains("third prompt") }
        )
        assertTrue(
            "the reverted-to turn must be preserved",
            bodies.any { it.contains("first prompt") }
        )
        assertFalse(
            "the dropped turn must never be sent again",
            bodies.any { it.contains("second prompt") }
        )
    }
}
