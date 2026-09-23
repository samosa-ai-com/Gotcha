package com.gotcha.agent

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.notifications.ChatCompletionNotifier
import com.gotcha.testsupport.FakeAndroidKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Task-finished notifications from [ChatViewModel] (issue #97): one per run that
 * ends while Gotcha is in the background, none in the foreground or when the
 * setting is off. The LLM base URL refuses connections, so every run here ends
 * quickly as a failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelCompletionNotificationTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager = application.getSystemService(NotificationManager::class.java)
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: ChatViewModel

    private fun start(enabled: Boolean = true, granted: Boolean = true) {
        FakeAndroidKeyStore.setUp()
        if (granted) shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        settingsRepository = SettingsRepository(application)
        settingsRepository.save(
            Settings(
                provider = LlmProvider.OPENAI_COMPATIBLE,
                apiKey = "test-key",
                baseUrl = "http://127.0.0.1:1/v1",
                chatCompletionNotificationsEnabled = enabled
            )
        )
        viewModel = ChatViewModel(application)
        ShadowLooper.idleMainLooper()
    }

    @Before
    fun clearTray() {
        manager.cancelAll()
    }

    private fun waitForRunToFinish() {
        val deadline = System.currentTimeMillis() + 5_000
        ShadowLooper.idleMainLooper()
        while (System.currentTimeMillis() < deadline && viewModel.uiState.value.isBusy) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
    }

    private fun posted() = shadowOf(manager).allNotifications

    @Test
    fun `a run that ends in the background posts one notification for that chat`() {
        start()
        viewModel.sendMessage("Hello")
        viewModel.setForeground(false)
        waitForRunToFinish()

        val notification = posted().single()
        assertTrue(notification.extras.getString(NotificationCompat.EXTRA_TITLE)!!.startsWith("Failed: "))
        val tap = shadowOf(notification.contentIntent).savedIntent
        assertEquals(
            viewModel.uiState.value.activeSessionId,
            tap.getStringExtra(ChatCompletionNotifier.EXTRA_OPEN_SESSION_ID)
        )
    }

    @Test
    fun `a run that ends in the foreground posts nothing`() {
        start()
        viewModel.sendMessage("Hello")
        waitForRunToFinish()

        assertTrue(posted().isEmpty())
    }

    @Test
    fun `nothing is posted when the setting is off`() {
        start(enabled = false)
        viewModel.sendMessage("Hello")
        viewModel.setForeground(false)
        waitForRunToFinish()

        assertTrue(posted().isEmpty())
    }

    @Test
    fun `returning to the chat clears its notification`() {
        start()
        viewModel.sendMessage("Hello")
        viewModel.setForeground(false)
        waitForRunToFinish()
        assertEquals(1, posted().size)

        viewModel.setForeground(true)

        assertTrue(posted().isEmpty())
    }

    @Test
    fun `hint promises a notification when one can be posted`() {
        start()
        viewModel.sendMessage("Hello")
        ShadowLooper.idleMainLooper()

        assertEquals(
            backgroundHintText(vibrate = true, chime = false, notify = true),
            viewModel.uiState.value.backgroundHint
        )
        waitForRunToFinish()
    }

    @Test
    fun `permission is asked once, on the first request, and never blocks the run`() {
        start(granted = false)
        viewModel.sendMessage("First")
        ShadowLooper.idleMainLooper()
        assertTrue(viewModel.uiState.value.askNotificationPermission)
        waitForRunToFinish()
        // The run finished while the ask was still unanswered.
        assertFalse(viewModel.uiState.value.isBusy)

        viewModel.onNotificationPermissionResult(false)
        viewModel.sendMessage("Second")
        ShadowLooper.idleMainLooper()

        assertFalse(viewModel.uiState.value.askNotificationPermission)
        waitForRunToFinish()
    }

    @Test
    fun `granting mid-run upgrades the hint`() {
        start(granted = false)
        viewModel.sendMessage("Hello")
        ShadowLooper.idleMainLooper()
        assertEquals(backgroundHintText(vibrate = true, chime = false), viewModel.uiState.value.backgroundHint)

        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        viewModel.onNotificationPermissionResult(true)

        assertEquals(
            backgroundHintText(vibrate = true, chime = false, notify = true),
            viewModel.uiState.value.backgroundHint
        )
        assertFalse(viewModel.uiState.value.askNotificationPermission)
        waitForRunToFinish()
    }
}
