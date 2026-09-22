package com.gotcha.agent

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.testsupport.FakeAndroidKeyStore
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chat host's half of asking for a permission when it is needed (issue #79):
 * the engine blocks on [ChatViewModel.awaitPermissionGrant] while the Activity
 * puts the reason on screen and raises the system dialog.
 *
 * Two things must hold or the agent hangs. The ask has to be visible in the UI
 * state — that is what brings the dialog back after a rotation — and a host with
 * no screen to show it on has to answer immediately rather than sit on the gate
 * for its full timeout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelPermissionGateTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var viewModel: ChatViewModel
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        SettingsRepository(application).save(Settings(apiKey = "test-key"))
        viewModel = ChatViewModel(application)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `the ask is published to the UI state until it is answered`() = runTest(dispatcher) {
        val gate = async { viewModel.awaitPermissionGrant(android.Manifest.permission.CAMERA) }
        runCurrent()

        assertEquals(
            "the dialog has nothing to render from",
            android.Manifest.permission.CAMERA,
            viewModel.uiState.value.pendingPermission
        )

        viewModel.onPermissionResult(true)
        runCurrent()

        assertTrue("a grant must reach the engine so it can retry the call", gate.await())
        assertNull("the ask outlived its answer", viewModel.uiState.value.pendingPermission)
    }

    @Test
    fun `declining answers the engine without leaving the ask on screen`() = runTest(dispatcher) {
        val gate = async { viewModel.awaitPermissionGrant(android.Manifest.permission.SEND_SMS) }
        runCurrent()

        viewModel.onPermissionResult(false)
        runCurrent()

        assertFalse(gate.await())
        assertNull(viewModel.uiState.value.pendingPermission)
    }

    @Test
    fun `a backgrounded app answers straight away instead of stalling the run`() = runTest(dispatcher) {
        // Nobody can tap a dialog that isn't on screen, and the runtime prompt
        // needs a foreground Activity — so this must not wait for the gate
        // timeout with the agent frozen behind it.
        viewModel.setForeground(false)

        val granted = viewModel.awaitPermissionGrant(android.Manifest.permission.READ_CONTACTS)

        assertFalse(granted)
        assertNull("nothing should be queued for a screen that isn't there", viewModel.uiState.value.pendingPermission)
    }
}
