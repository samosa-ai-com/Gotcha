package com.gotcha.agent

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.gotcha.audio.AudioProvider
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.testsupport.FakeAndroidKeyStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelAutoReadTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        settingsRepository = SettingsRepository(application)
        settingsRepository.save(
            Settings(
                autoReadReplies = false,
                ttsProvider = AudioProvider.ANDROID,
                apiKey = "test-api-key"
            )
        )
        viewModel = ChatViewModel(application)
    }

    @Test
    fun `typed message when autoReadReplies is false does not speak reply`() {
        viewModel.sendMessage("Hello", isVoiceInput = false)
        viewModel.onAssistantReply("Hello back!")
        assertFalse(viewModel.uiState.value.isSpeaking)
    }

    @Test
    fun `voice message when autoReadReplies is false speaks reply for that turn`() {
        viewModel.sendMessage("Hello", isVoiceInput = true)
        viewModel.onAssistantReply("Hello back!")
        assertTrue(viewModel.uiState.value.isSpeaking)
    }

    @Test
    fun `subsequent typed message after voice message does not speak reply when autoReadReplies is false`() {
        viewModel.sendMessage("Hello", isVoiceInput = true)
        viewModel.onAssistantReply("Hello back!")
        viewModel.stopSpeaking()

        viewModel.sendMessage("Second message", isVoiceInput = false)
        viewModel.onAssistantReply("Second reply")
        assertFalse(viewModel.uiState.value.isSpeaking)
    }

    @Test
    fun `opening session clears any voice input flag`() {
        viewModel.sendMessage("Hello", isVoiceInput = true)
        viewModel.openSession("new-session-id")

        viewModel.sendMessage("Session message", isVoiceInput = false)
        viewModel.onAssistantReply("Session reply")
        assertFalse(viewModel.uiState.value.isSpeaking)
    }
}
