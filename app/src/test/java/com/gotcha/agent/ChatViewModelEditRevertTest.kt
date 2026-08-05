package com.gotcha.agent

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.testsupport.FakeAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Behavioural contract for the Edit / Revert message gates in [ChatViewModel].
 *
 * Like [ChatViewModelContextUsageTest], this exercises only the synchronous
 * entry points: `viewModelScope.launch` posts continuations back to Main after
 * a real `Dispatchers.IO` suspension, which is not observable from this JUnit
 * tier. The feature's synchronous guards — busy, another chat running, blank
 * input — are exactly what belongs here; the truncation math itself is covered
 * by the pure [ChatHistoryEditTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelEditRevertTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var historyRepository: ChatHistoryRepository
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        settingsRepository = SettingsRepository(application)
        historyRepository = ChatHistoryRepository(application, "edit-revert-test-chats")
        settingsRepository.save(Settings(apiKey = "test-key"))
        viewModel = ChatViewModel(application)
        // Drain the init coroutine so the baseline state is settled.
        ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() {
        runBlocking {
            historyRepository.deleteSession("session-A")
            historyRepository.deleteSession("session-other")
        }
    }

    /** Force the busy / running gate on so the synchronous guards are exercised. */
    private fun setRunning(isBusy: Boolean, runningSessionId: String?) {
        val stateField = ChatViewModel::class.java.getDeclaredField("_uiState")
            .apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val flow = stateField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ChatUiState>
        flow.value = flow.value.copy(isBusy = isBusy, runningSessionId = runningSessionId)
    }

    @Test
    fun `editMessage is a no-op while a run is busy`() {
        setRunning(isBusy = true, runningSessionId = "session-A")
        val before = viewModel.uiState.value

        viewModel.editMessage(targetId = 1L, newText = "Edited", imageBase64 = null)
        ShadowLooper.idleMainLooper()

        assertEquals(before.messages, viewModel.uiState.value.messages)
        assertEquals(before.isBusy, viewModel.uiState.value.isBusy)
    }

    @Test
    fun `editMessage is a no-op while another chat runs`() {
        setRunning(isBusy = false, runningSessionId = "session-other")
        val before = viewModel.uiState.value

        viewModel.editMessage(targetId = 1L, newText = "Edited", imageBase64 = null)
        ShadowLooper.idleMainLooper()

        assertEquals(before.messages, viewModel.uiState.value.messages)
        assertFalse(viewModel.uiState.value.isBusy)
    }

    @Test
    fun `revertTo is a no-op while a run is busy`() {
        setRunning(isBusy = true, runningSessionId = "session-A")
        val before = viewModel.uiState.value

        viewModel.revertTo(targetId = 1L)
        ShadowLooper.idleMainLooper()

        assertEquals(before.messages, viewModel.uiState.value.messages)
        assertEquals(before.isBusy, viewModel.uiState.value.isBusy)
    }

    @Test
    fun `editMessage is a no-op when the input is blank and no image is attached`() {
        val before = viewModel.uiState.value

        viewModel.editMessage(targetId = 1L, newText = "   ", imageBase64 = null)
        ShadowLooper.idleMainLooper()

        assertEquals(before.messages, viewModel.uiState.value.messages)
    }
}
