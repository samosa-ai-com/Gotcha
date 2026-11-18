package com.gotcha.tools

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.gotcha.shell.IInstallCallback
import com.gotcha.shell.IShellService
import com.gotcha.shell.JobStatus
import com.gotcha.shell.RunRequest
import com.gotcha.shell.RunResult
import com.gotcha.shell.SessionHandle
import com.gotcha.shell.ShellInfo
import com.gotcha.tools.CompanionTerminal.Opened
import com.gotcha.tools.CompanionTerminal.Refusal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** [CompanionTerminal] against a stand-in service: what it asks, and every way an open can be refused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompanionTerminalTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val service = FakeService()
    private val link = FakeLink(service)
    private var access = ShellEntitlement.Access.ALLOWED
    private lateinit var terminal: CompanionTerminal

    @Before
    fun setUp() {
        terminal = CompanionTerminal(context, link) { access }
    }

    private suspend fun refusal(): Refusal = (terminal.open(24, 80) as Opened.Refused).refusal

    @Test
    fun `opens a session by handle at the size asked for`() = runTest {
        val opened = terminal.open(30, 100)
        assertTrue(opened is Opened.Session)
        assertEquals(listOf(30 to 100), service.opened)
        assertEquals(FakeService.ID, (opened as Opened.Session).session.id)
    }

    @Test
    fun `resize and close go to the session by its id`() = runTest {
        val session = (terminal.open(24, 80) as Opened.Session).session
        assertTrue(session.resize(40, 132))
        assertEquals(listOf(Triple(FakeService.ID, 40, 132)), service.resized)
        session.close()
        assertEquals(listOf(FakeService.ID), service.closed)
    }

    @Test
    fun `closing twice asks the companion once`() = runTest {
        val session = (terminal.open(24, 80) as Opened.Session).session
        session.close()
        session.close()
        assertEquals(listOf(FakeService.ID), service.closed)
    }

    @Test
    fun `resize says false rather than throwing when the companion is gone`() = runTest {
        val session = (terminal.open(24, 80) as Opened.Session).session
        service.failCalls = IllegalStateException("gone")
        assertFalse(session.resize(10, 10))
        session.close()
    }

    @Test
    fun `an account without the plan is refused before anything is asked of the companion`() = runTest {
        access = ShellEntitlement.Access.NOT_PRO
        assertEquals(Refusal.NotAllowed(ShellEntitlement.Access.NOT_PRO), refusal())
        assertTrue(service.opened.isEmpty())
        assertEquals(0, link.calls)
    }

    @Test
    fun `a companion without the terminal interface is too old`() = runTest {
        service.shellInfo.interfaceVersion = CompanionShellBackend.INTERFACE_VERSION - 1
        assertEquals(
            Refusal.TooOld(CompanionShellBackend.INTERFACE_VERSION - 1, CompanionShellBackend.INTERFACE_VERSION),
            refusal()
        )
        assertTrue(service.opened.isEmpty())
    }

    @Test
    fun `a newer companion is fine`() = runTest {
        service.shellInfo.interfaceVersion = CompanionShellBackend.INTERFACE_VERSION + 1
        assertTrue(terminal.open(24, 80) is Opened.Session)
    }

    @Test
    fun `no Linux system is reported with whether one is being installed`() = runTest {
        service.shellInfo.rootfsReady = false
        service.shellInfo.installing = true
        assertEquals(Refusal.NoLinuxSystem(installing = true), refusal())
        assertEquals(false, CompanionShellState.rootfsReady(context))
    }

    @Test
    fun `the cap is reported with its limit`() = runTest {
        service.openFailure = IllegalStateException("Too many sessions (limit 4)")
        assertEquals(Refusal.TooManySessions(4), refusal())
    }

    @Test
    fun `a cap message without a number gives limit 0`() = runTest {
        service.openFailure = IllegalStateException("Too many sessions")
        assertEquals(Refusal.TooManySessions(0), refusal())
    }

    @Test
    fun `an unreachable companion carries the reason`() = runTest {
        link.unavailable = CompanionUnavailable(CompanionUnavailable.Reason.NOT_INSTALLED)
        assertEquals(Refusal.Unavailable(CompanionUnavailable.Reason.NOT_INSTALLED), refusal())
    }

    @Test
    fun `a caller the companion refuses is a failure with its message`() = runTest {
        service.openFailure = SecurityException("not allowed")
        assertEquals(Refusal.Failed("not allowed"), refusal())
    }

    @Test
    fun `a companion that dies while answering is a failure, not a crash`() = runTest {
        service.openFailure = android.os.DeadObjectException()
        assertTrue(refusal() is Refusal.Failed)
    }

    @Test
    fun `a session that opened but cannot be used is hung up, not left counting toward the cap`() = runTest {
        service.sendNoSocket = true
        assertTrue(refusal() is Refusal.Failed)
        assertEquals(listOf(FakeService.ID), service.closed)
    }

    @Test
    fun `a session with no id is a failure, not a crash`() = runTest {
        service.sendNoId = true
        assertTrue(refusal() is Refusal.Failed)
        assertTrue(service.closed.isEmpty())
    }

    @Test(timeout = 10_000)
    fun `a session the companion opens after the caller gave up is hung up`() = runBlocking {
        // Like the real link, the call runs on another dispatcher, so the answer has to come back.
        val ioLink = object : CompanionLink {
            override fun identity() = link.identity()
            override suspend fun <T> call(block: (IShellService) -> T): T =
                withContext(Dispatchers.IO) { link.call(block) }
        }
        val opening = CompanionTerminal(context, ioLink) { access }
        val gate = CountDownLatch(1)
        service.openGate = gate
        val job = launch(Dispatchers.Default) { opening.open(24, 80) }
        assertTrue(service.openEntered.await(5, TimeUnit.SECONDS))
        job.cancel()
        gate.countDown()
        job.join()
        assertEquals(listOf(FakeService.ID), service.closed)
    }

    private class FakeLink(private val service: IShellService) : CompanionLink {
        var unavailable: CompanionUnavailable? = null
        var calls = 0
        private val identity = CompanionIdentity(installed = true, trusted = true, versionName = "0.1.2")

        override fun identity() = identity

        override suspend fun <T> call(block: (IShellService) -> T): T {
            calls++
            unavailable?.let { throw it }
            return block(service)
        }
    }

    private class FakeService : IShellService.Stub() {
        val shellInfo = ShellInfo().apply {
            interfaceVersion = CompanionShellBackend.INTERFACE_VERSION
            rootfsReady = true
            maxSessions = 4
        }
        val opened = mutableListOf<Pair<Int, Int>>()
        val resized = mutableListOf<Triple<String, Int, Int>>()
        val closed: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        var openFailure: Exception? = null
        var failCalls: Exception? = null
        var sendNoSocket = false
        var sendNoId = false

        /** When set, openSessionHandle waits for it after counting [openEntered] down. */
        @Volatile var openGate: CountDownLatch? = null
        val openEntered = CountDownLatch(1)

        override fun getInfo(): ShellInfo = shellInfo

        override fun openSessionHandle(rows: Int, cols: Int): SessionHandle {
            openFailure?.let { throw it }
            openEntered.countDown()
            openGate?.await()
            opened += rows to cols
            return SessionHandle().apply {
                sessionId = if (sendNoId) null else ID
                socket = if (sendNoSocket) null else ParcelFileDescriptor.createPipe()[0]
            }
        }

        override fun resizeSession(sessionId: String, rows: Int, cols: Int): Boolean {
            failCalls?.let { throw it }
            resized += Triple(sessionId, rows, cols)
            return true
        }

        override fun closeSession(sessionId: String): Boolean {
            failCalls?.let { throw it }
            closed += sessionId
            return true
        }

        override fun run(request: RunRequest): RunResult = error("not used")
        override fun openSession(rows: Int, cols: Int): ParcelFileDescriptor = error("not used")
        override fun install(callback: IInstallCallback?): String? = error("not used")
        override fun repair(): RunResult = error("not used")
        override fun reset() = error("not used")
        override fun startJob(request: RunRequest): String = error("not used")
        override fun pollJob(jobId: String, stdoutOffset: Long, stderrOffset: Long): JobStatus = error("not used")
        override fun killJob(jobId: String): Boolean = error("not used")
        override fun importFile(source: ParcelFileDescriptor, guestPath: String): RunResult = error("not used")
        override fun exportFile(guestPath: String): ParcelFileDescriptor? = error("not used")

        companion object {
            const val ID = "3f2b8c1e-0000-4000-8000-000000000001"
        }
    }
}
