package com.gotcha.terminal

import android.app.Application
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.gotcha.shell.IShellService
import com.gotcha.tools.CompanionIdentity
import com.gotcha.tools.CompanionLink
import com.gotcha.tools.CompanionTerminal.Opened
import com.gotcha.tools.CompanionTerminal.Refusal
import com.gotcha.tools.ShellSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** The terminal's life: opening, being refused, ending, restarting, and being closed by the user. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalViewModelTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()

    /** Records what reaches the companion, in place of Binder. */
    private class Link : CompanionLink {
        val closed = mutableListOf<String>()
        override fun identity() = CompanionIdentity(installed = true, trusted = true, versionName = "0.1.2")

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> call(block: (IShellService) -> T): T = block(
            object : IShellService.Stub() {
                override fun closeSession(sessionId: String): Boolean {
                    closed += sessionId
                    return true
                }
                override fun getInfo() = error("not used")
                override fun run(request: com.gotcha.shell.RunRequest) = error("not used")
                override fun openSession(rows: Int, cols: Int) = error("not used")
                override fun openSessionHandle(rows: Int, cols: Int) = error("not used")
                override fun resizeSession(sessionId: String, rows: Int, cols: Int) = true
                override fun install(callback: com.gotcha.shell.IInstallCallback?) = error("not used")
                override fun repair() = error("not used")
                override fun reset() = error("not used")
                override fun startJob(request: com.gotcha.shell.RunRequest) = error("not used")
                override fun pollJob(jobId: String, stdoutOffset: Long, stderrOffset: Long) = error("not used")
                override fun killJob(jobId: String) = error("not used")
                override fun importFile(source: ParcelFileDescriptor, guestPath: String) = error("not used")
                override fun exportFile(guestPath: String) = error("not used")
            } as IShellService
        ) as T
    }

    private val link = Link()
    private val opens = AtomicInteger()
    private var next: () -> Opened = { error("no answer set") }

    /** The write end of the pipe the "shell" prints into; closing it is the shell exiting. */
    private var shellOutput: ParcelFileDescriptor? = null

    private fun session(id: String): Opened.Session {
        val pipe = ParcelFileDescriptor.createPipe()
        shellOutput = pipe[1]
        return Opened.Session(ShellSession(id, pipe[0], link))
    }

    private lateinit var viewModel: TerminalViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = TerminalViewModel(app, { _, _ -> opens.incrementAndGet().let { next() } }, dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun live() = viewModel.state.value as TerminalUiState.Live

    private fun awaitMain(done: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `starts with nothing open`() {
        assertEquals(TerminalUiState.Idle, viewModel.state.value)
    }

    @Test
    fun `opening gives a live session`() {
        next = { session("s1") }
        viewModel.open()
        assertFalse(live().ended)
        assertEquals(1, opens.get())
    }

    @Test
    fun `a refusal is kept for the screen to explain`() {
        next = { Opened.Refused(Refusal.TooManySessions(4)) }
        viewModel.open()
        assertEquals(TerminalUiState.Refused(Refusal.TooManySessions(4)), viewModel.state.value)
    }

    @Test
    fun `asking again while a session is open does not open another`() {
        next = { session("s1") }
        viewModel.open()
        viewModel.open()
        assertEquals(1, opens.get())
    }

    @Test
    fun `after a refusal the user can try again`() {
        next = { Opened.Refused(Refusal.NoLinuxSystem(installing = false)) }
        viewModel.open()
        next = { session("s2") }
        viewModel.open()
        assertEquals(2, opens.get())
        assertFalse(live().ended)
    }

    @Test
    fun `ending the session hangs up the shell and goes back to nothing open`() {
        next = { session("s1") }
        viewModel.open()
        live().session.updateSize(80, 24, 10, 20)
        viewModel.close()
        assertEquals(TerminalUiState.Idle, viewModel.state.value)
        awaitMain { "s1" in link.closed }
    }

    @Test
    fun `a shell that exits by itself ends the session but keeps its screen`() {
        next = { session("s1") }
        viewModel.open()
        val session = live().session
        session.updateSize(80, 24, 10, 20)
        shellOutput!!.close()
        awaitMain { (viewModel.state.value as? TerminalUiState.Live)?.ended == true }
        assertTrue(live().session === session)
        awaitMain { "s1" in link.closed }
    }

    @Test
    fun `after the shell exits a new session can be started`() {
        next = { session("s1") }
        viewModel.open()
        live().session.updateSize(80, 24, 10, 20)
        shellOutput!!.close()
        awaitMain { (viewModel.state.value as? TerminalUiState.Live)?.ended == true }
        next = { session("s2") }
        viewModel.open()
        assertEquals(2, opens.get())
        assertFalse(live().ended)
    }

    @Test
    fun `leaving the screen's owner for good ends the session`() {
        next = { session("s1") }
        viewModel.open()
        live().session.updateSize(80, 24, 10, 20)
        val onCleared = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        onCleared.isAccessible = true
        onCleared.invoke(viewModel)
        assertTrue("s1" in link.closed)
        assertFalse(live().session.isRunning)
    }
}
