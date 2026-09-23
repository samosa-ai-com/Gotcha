package com.gotcha.agent

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.llm.ChatMessage
import com.gotcha.testsupport.FakeAndroidKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The "you can leave Gotcha" hint (issue #96): shown once per submitted request,
 * gone when the run ends, and never part of the transcript or the LLM history.
 * The LLM base URL refuses connections so each run fails fast and stays hermetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelBackgroundHintTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var viewModel: ChatViewModel
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    /** Every hint value the state published, in order, with repeats collapsed. */
    private val hints = mutableListOf<String?>()

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        SettingsRepository(application).save(
            Settings(
                provider = LlmProvider.OPENAI_COMPATIBLE,
                apiKey = "test-key",
                baseUrl = "http://127.0.0.1:1/v1",
                notifyVibrationEnabled = true,
                notifyChimeEnabled = false
            )
        )
        viewModel = ChatViewModel(application)
        ShadowLooper.idleMainLooper()
        scope.launch {
            viewModel.uiState.collect { state ->
                if (hints.isEmpty() || hints.last() != state.backgroundHint) hints += state.backgroundHint
            }
        }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun waitForRunToFinish() {
        val deadline = System.currentTimeMillis() + 5_000
        ShadowLooper.idleMainLooper()
        while (System.currentTimeMillis() < deadline && viewModel.uiState.value.isBusy) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
    }

    private fun engineHistory(): List<ChatMessage> {
        val field = ChatViewModel::class.java.getDeclaredField("agentEngine")
            .apply { isAccessible = true }
        return (field.get(viewModel) as AgentEngine).history.toList()
    }

    private val expectedHint = backgroundHintText(vibrate = true, chime = false)

    @Test
    fun `hint promises only the signals the user has enabled`() {
        val base = "Gotcha is working in the background. You can use another app while it works"
        assertEquals("$base — your phone will buzz when it's done.", backgroundHintText(vibrate = true, chime = false))
        assertEquals("$base — your phone will chime when it's done.", backgroundHintText(vibrate = false, chime = true))
        assertEquals(
            "$base — your phone will buzz and chime when it's done.",
            backgroundHintText(vibrate = true, chime = true)
        )
        assertEquals("$base.", backgroundHintText(vibrate = false, chime = false))
    }

    @Test
    fun `hint shows while a run works and clears when it ends`() {
        viewModel.sendMessage("Hello")
        waitForRunToFinish()

        assertEquals(listOf(null, expectedHint, null), hints)
        assertNull(viewModel.uiState.value.backgroundHint)
    }

    @Test
    fun `hint appears once per submitted request`() {
        viewModel.sendMessage("First")
        waitForRunToFinish()
        viewModel.sendMessage("Second")
        waitForRunToFinish()

        assertEquals(2, hints.count { it == expectedHint })
    }

    @Test
    fun `hint never enters the transcript or the LLM history`() {
        viewModel.sendMessage("Hello")
        waitForRunToFinish()
        // Leaving and returning to the app must not add anything either.
        viewModel.setForeground(false)
        viewModel.setForeground(true)
        ShadowLooper.idleMainLooper()

        assertFalse(viewModel.uiState.value.messages.any { it.text.contains("working in the background") })
        assertFalse(engineHistory().any { it.textContent.contains("working in the background") })
    }
}
