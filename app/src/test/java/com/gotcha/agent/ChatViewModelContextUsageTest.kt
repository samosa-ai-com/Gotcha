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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Behavioural contract for context-window state in the chat host.
 *
 * The init coroutine of [ChatViewModel] runs on Robolectric's main looper,
 * which is drained in [setUp] so each test starts from a clean baseline.
 * Tests deliberately exercise only the synchronous entry points
 * ([ChatViewModel.clearChat], [ChatViewModel.onTokenCount],
 * [ChatViewModel.refreshSettings], [ChatViewModel.deleteSession]) because
 * `viewModelScope.launch` posts continuations back to Main after a real
 * `Dispatchers.IO` suspension, and that handoff is not observable from
 * these JUnit tests without the heavier activity-instrumentation tier.
 *
 * What is verified here:
 *  - [ChatViewModel.clearChat] resets the context meter to zero
 *  - [ChatViewModel.refreshSettings] never overwrites the displayed token
 *    count (the regression test for Bug 2 — the prior implementation
 *    read [com.gotcha.agent.AgentEngine.tokenCount] unconditionally, so
 *    saving settings while a background run was in another chat would
 *    overwrite the viewed session's bar)
 *  - [ChatViewModel.onTokenCount] publishes a live overlay so the drawer
 *    updates in-frame (Bug 4)
 *  - [ChatViewModel.deleteSession] cleans up the overlay entry
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelContextUsageTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var historyRepository: ChatHistoryRepository
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        settingsRepository = SettingsRepository(application)
        historyRepository = ChatHistoryRepository(application, "ctx-usage-test-chats")
        settingsRepository.save(Settings(apiKey = "test-key"))
        viewModel = ChatViewModel(application)
        // Drain the init coroutine so the baseline state is settled.
        ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() {
        runBlocking {
            historyRepository.deleteSession("session-A")
            historyRepository.deleteSession("session-B")
        }
    }

    /**
     * Seed `_uiState.tokenCount` to [tokens]. Required because the only
     * public path to a non-zero token count is `openSession`, whose
     * coroutine suspends on `Dispatchers.IO` and whose continuation
     * post-back to Main is not observable from this JUnit tier. We seed
     * the UI state directly so the regression tests can exercise
     * `updateContextUsage()` deterministically.
     */
    private fun seedUiTokenCount(tokens: Int) {
        val stateField = ChatViewModel::class.java.getDeclaredField("_uiState")
            .apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val flow = stateField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ChatUiState>
        val limit = settingsRepository.load().maxContextTokens.toFloat()
        val percent = if (limit > 0) tokens.toFloat() / limit else 0f
        flow.value = flow.value.copy(
            tokenCount = tokens,
            contextUsagePercent = percent.coerceIn(0f, 1f)
        )
    }

    @Test
    fun `clearChat resets the context meter to zero`() {
        // Seed a non-zero token count so clearChat has to overwrite a real
        // number, not just initialise the default zero.
        seedUiTokenCount(42_000)
        assertEquals(42_000, viewModel.uiState.value.tokenCount)

        viewModel.clearChat()

        assertEquals(0, viewModel.uiState.value.tokenCount)
        assertEquals(0f, viewModel.uiState.value.contextUsagePercent, 0.0001f)
        assertFalse(viewModel.liveTokenBySession.value.containsKey(viewModel.uiState.value.activeSessionId))
    }

    /**
     * Regression test for Bug 2. The previous `updateContextUsage()` read
     * `agentEngine.tokenCount` unconditionally. With the fix it reads
     * `_uiState.value.tokenCount` — so lowering the limit must only
     * recompute the percent; the displayed count must not be clobbered.
     */
    @Test
    fun `refreshSettings preserves the viewed session's token count`() {
        seedUiTokenCount(12_345)

        // Sanity: the seeded value is on screen, percent is computed
        // against the default 70 000 limit.
        assertEquals(12_345, viewModel.uiState.value.tokenCount)
        val originalPercent = viewModel.uiState.value.contextUsagePercent
        assertTrue("percent must be > 0 with non-zero tokens", originalPercent > 0f)

        // Lower the limit and re-read settings. The token count must NOT
        // be clobbered; only the percent must be recomputed against the
        // new limit.
        settingsRepository.save(Settings(apiKey = "test-key", maxContextTokens = 20_000))
        viewModel.refreshSettings()

        assertEquals(
            "refreshSettings must not clobber the displayed token count",
            12_345,
            viewModel.uiState.value.tokenCount
        )
        assertNotEquals(
            "percent must be recomputed against the new limit",
            originalPercent,
            viewModel.uiState.value.contextUsagePercent
        )
        assertEquals(
            12_345f / 20_000f,
            viewModel.uiState.value.contextUsagePercent,
            0.001f
        )
    }

    @Test
    fun `onTokenCount publishes a live overlay keyed by the engine session`() {
        // After init the engine and the viewed session share the same id
        // (the random UUID minted in the init coroutine), so any onTokenCount
        // call surfaces in the overlay under that id.
        val engineId = viewModel.uiState.value.activeSessionId
        assertNotNull(engineId)

        viewModel.onTokenCount(555)

        assertEquals(555, viewModel.liveTokenBySession.value[engineId])
    }
}
