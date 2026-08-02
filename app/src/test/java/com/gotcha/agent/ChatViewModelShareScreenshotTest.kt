package com.gotcha.agent

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.visionUserMessage
import com.gotcha.testsupport.FakeAndroidKeyStore
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The poster sheet's "Include a screenshot" toggle must track the chat history:
 * present while a vision message is in the session, gone once it is removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelShareScreenshotTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var settingsRepository: SettingsRepository

    // The repository is constructed only to satisfy ChatViewModel's constructor;
    // the tests below reach the engine history directly via reflection and never
    // open a session, so no per-session cleanup is needed.
    private lateinit var historyRepository: ChatHistoryRepository
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        settingsRepository = SettingsRepository(application)
        historyRepository = ChatHistoryRepository(application, "share-screenshot-test-chats")
        settingsRepository.save(Settings(apiKey = "test-key"))
        viewModel = ChatViewModel(application)
        ShadowLooper.idleMainLooper()
    }

    private fun engineHistory(): MutableList<ChatMessage> {
        val engineField = ChatViewModel::class.java.getDeclaredField("agentEngine")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (engineField.get(viewModel) as AgentEngine).history
    }

    @Test
    fun `hasImage is false on an empty or text-only history`() {
        assertFalse(viewModel.activeSessionHasImage())

        engineHistory() += ChatMessage(role = "user", content = JsonPrimitive("hello"))
        assertFalse(viewModel.activeSessionHasImage())
    }

    @Test
    fun `hasImage is true once a vision message lands in history`() {
        engineHistory() += visionUserMessage("what is this", "QUJD", imageFormat = "jpeg")
        assertTrue(viewModel.activeSessionHasImage())
    }

    @Test
    fun `hasImage flips back to false when the image is culled from history`() {
        val history = engineHistory()
        history += visionUserMessage("what is this", "QUJD", imageFormat = "jpeg")
        assertTrue(viewModel.activeSessionHasImage())

        // Simulate the image being retracted / culled from the session.
        history.removeAll { it.hasImage }
        assertFalse(viewModel.activeSessionHasImage())
    }
}
