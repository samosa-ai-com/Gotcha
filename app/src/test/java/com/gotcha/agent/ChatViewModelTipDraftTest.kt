package com.gotcha.agent

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.testsupport.FakeAndroidKeyStore
import com.gotcha.tools.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** A tapped daily tip opens a new chat with its prompt drafted, not sent (issue #101). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelTipDraftTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private fun viewModel(configured: Boolean = true): ChatViewModel {
        FakeAndroidKeyStore.setUp()
        SettingsRepository(application).save(
            Settings(
                provider = LlmProvider.OPENAI_COMPATIBLE,
                apiKey = "test-key",
                // An OpenAI-compatible provider counts as configured once it has a base URL.
                baseUrl = if (configured) "http://127.0.0.1:1/v1" else ""
            )
        )
        return ChatViewModel(application).also { ShadowLooper.idleMainLooper() }
    }

    private fun idleUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        ShadowLooper.idleMainLooper()
        while (System.currentTimeMillis() < deadline && !condition()) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
    }

    @Test
    fun `opens a new chat in the tip's mode with the prompt drafted`() {
        val vm = viewModel()
        idleUntil { vm.uiState.value.activeSessionId != null }
        val before = vm.uiState.value.activeSessionId

        vm.startChatFromTip("Turn on Do Not Disturb", AgentMode.OPERATOR)
        idleUntil { vm.uiState.value.composerDraft != null }

        val state = vm.uiState.value
        assertEquals("Turn on Do Not Disturb", state.composerDraft)
        assertEquals(AgentMode.OPERATOR, state.activeAgent)
        assertTrue(state.messages.isEmpty())
        assertNotEquals(before, state.activeSessionId)
        // Drafted, never sent.
        assertEquals(false, state.isBusy)

        vm.consumeComposerDraft()
        assertNull(vm.uiState.value.composerDraft)
    }

    @Test
    fun `unconfigured, the chat opens without a draft`() {
        val vm = viewModel(configured = false)
        idleUntil { vm.uiState.value.activeSessionId != null }
        val before = vm.uiState.value.activeSessionId
        vm.startChatFromTip("What's on my screen?", AgentMode.MONITOR)
        idleUntil { vm.uiState.value.activeSessionId != before }
        assertNotEquals(before, vm.uiState.value.activeSessionId)
        assertNull(vm.uiState.value.composerDraft)
    }
}
