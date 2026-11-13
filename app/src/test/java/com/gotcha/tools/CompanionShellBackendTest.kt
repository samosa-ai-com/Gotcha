package com.gotcha.tools

import android.content.Context
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.gotcha.shell.IInstallCallback
import com.gotcha.shell.IShellService
import com.gotcha.shell.JobStatus
import com.gotcha.shell.RunRequest
import com.gotcha.shell.RunResult
import com.gotcha.shell.ShellInfo
import com.gotcha.testsupport.ShadowExternalStorageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `run_shell` through the Gotcha Shell companion. The companion itself cannot run on the JVM (or
 * on an x86_64 emulator), so a scripted [IShellService] stands in for it: this covers what Gotcha
 * does with the answers, not what Ubuntu does with the commands. The live round-trip is checked
 * on an arm64 phone; see docs/FEATURE_TEST_COVERAGE.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowExternalStorageManager::class])
class CompanionShellBackendTest {

    @get:Rule val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val service = FakeShellService()
    private val link = FakeLink(service)
    private val backend = CompanionShellBackend(context, link)

    @Before
    fun grantAllFilesAccess() {
        ShadowExternalStorageManager.granted = true
    }

    @After
    fun resetAllFilesAccess() {
        ShadowExternalStorageManager.resetGranted()
    }

    // ---- reaching the companion ----

    @Test
    fun `not installed is reported without binding`() = runTest {
        link.identity = CompanionIdentity(installed = false, trusted = false, versionName = null)
        val result = backend.runCommand("echo hi")
        assertFalse(result.success)
        assertTrue(result.message, "not installed" in result.message)
        assertTrue(service.started.isEmpty())
    }

    @Test
    fun `an impostor with the package name is never sent a command`() = runTest {
        link.identity = CompanionIdentity(installed = true, trusted = false, versionName = "9.9")
        val result = backend.runCommand("echo hi")
        assertFalse(result.success)
        assertTrue(result.message, "not signed with Gotcha's key" in result.message)
        assertTrue(service.started.isEmpty())
        assertFalse(backend.status().usable)
    }

    @Test
    fun `a different interface version is refused before any command`() = runTest {
        service.shellInfo.interfaceVersion = 3
        val result = backend.runCommand("echo hi")
        assertFalse(result.success)
        assertTrue(result.message, "interface version 3" in result.message)
        assertTrue(service.started.isEmpty())
    }

    @Test
    fun `a missing rootfs is reported and remembered`() = runTest {
        service.shellInfo.rootfsReady = false
        val result = backend.runCommand("echo hi")
        assertFalse(result.success)
        assertTrue(result.message, "tap Install" in result.message)
        assertEquals(false, backend.status().rootfsReady)
        assertTrue(service.started.isEmpty())
    }

    @Test
    fun `a caller the companion refuses is reported, not retried`() = runTest {
        service.infoFailure = SecurityException("caller not allowed")
        val result = backend.runCommand("echo hi")
        assertFalse(result.success)
        assertTrue(result.message, "refused this Gotcha" in result.message)
    }

    // ---- running ----

    @Test
    fun `a finished command is reported in the shared shape`() = runTest {
        service.script(exited(0, stdout = "hi\n"))
        val result = backend.runCommand("echo hi", workingDir = "~/work", timeoutSeconds = 30)
        assertTrue(result.success)
        assertEquals("exit code: 0\nstdout:\nhi\n", result.message)
        val request = service.started.single()
        assertEquals("echo hi", request.cmd)
        assertEquals("/root/work", request.cwd)
        assertEquals(30, request.timeoutSeconds)
        assertEquals(true, backend.status().rootfsReady)
    }

    @Test
    fun `a failing command keeps its exit code and stderr`() = runTest {
        service.script(exited(2, stderr = "nope"))
        val result = backend.runCommand("false")
        assertFalse(result.success)
        assertEquals("exit code: 2\nstderr:\nnope", result.message)
    }

    @Test
    fun `output arriving over several polls is joined in order`() = runTest {
        service.script(running(stdout = "one "), running(stdout = "two "), exited(0, stdout = "three"))
        val result = backend.runCommand("slow")
        assertEquals("exit code: 0\nstdout:\none two three", result.message)
        // Each poll passes on the offset the previous one returned.
        assertEquals(listOf(0L, 4L, 8L, 13L), service.polledStdoutOffsets)
    }

    @Test
    fun `output beyond the cap is dropped and said to be`() = runTest {
        val small = CompanionShellBackend(context, link, maxOutputBytes = 8)
        service.script(running(stdout = "0123456789"), exited(0, stdout = "abc"))
        val result = small.runCommand("noisy")
        assertTrue(result.message, result.message.startsWith("exit code: 0\nstdout:\n01234567\n"))
        assertTrue(result.message, "output capped" in result.message)
    }

    @Test
    fun `package manager commands run non-interactively with a longer default`() = runTest {
        service.script(exited(0))
        backend.runCommand("apt-get install -y python3")
        val request = service.started.single()
        assertEquals("DEBIAN_FRONTEND=noninteractive apt-get install -y python3", request.cmd)
        assertEquals(300, request.timeoutSeconds)
    }

    @Test
    fun `a held apt lock gets the recovery advice`() = runTest {
        service.script(
            exited(100, stderr = "E: Could not get lock /var/lib/dpkg/lock-frontend. It is held by process 4242 (dpkg)")
        )
        val result = backend.runCommand("apt-get install -y ffmpeg")
        assertFalse(result.success)
        assertTrue(result.message, "process 4242" in result.message)
        assertTrue(result.message, "never delete the lock files" in result.message)
    }

    @Test
    fun `a timed out command is reported as stopped with what it printed`() = runTest {
        service.script(running(stdout = "working"), status(JobStatus.TIMED_OUT, 137))
        val result = backend.runCommand("sleep 999", timeoutSeconds = 5)
        assertFalse(result.success)
        assertTrue(result.message, "did not finish within 5s and was stopped" in result.message)
        assertTrue(result.message, "working" in result.message)
        assertTrue(service.killed.isEmpty())
    }

    @Test
    fun `cancelling the turn kills the job`() = runTest {
        service.repeatLast = true
        service.script(running())
        val job = launch { backend.runCommand("sleep 999") }
        advanceTimeBy(5_000)
        runCurrent()
        job.cancelAndJoin()
        assertEquals(listOf("job-1"), service.killed)
    }

    @Test
    fun `cancellation is not mistaken for a shell error`() = runTest {
        service.pollFailure = CancellationException("stopped")
        var thrown: Throwable? = null
        try {
            backend.runCommand("echo hi")
        } catch (e: CancellationException) {
            thrown = e
        }
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun `a companion that dies mid-command is reported as such`() = runTest {
        service.pollFailure = DeadObjectException()
        val result = backend.runCommand("echo hi")
        assertFalse(result.success)
        assertTrue(result.message, "stopped while the command was running" in result.message)
    }

    @Test
    fun `a job the companion no longer knows is reported as lost`() = runTest {
        service.script(status(JobStatus.UNKNOWN, 0))
        val result = backend.runCommand("echo hi")
        assertFalse(result.success)
        assertTrue(result.message, "no longer knows this command" in result.message)
        assertTrue(service.killed.isEmpty())
    }

    @Test
    fun `too many running jobs is reported without a retry loop`() = runTest {
        service.startFailure = IllegalStateException("Too many running jobs")
        val result = backend.runCommand("echo hi")
        assertFalse(result.success)
        assertTrue(result.message, "maximum number of commands" in result.message)
    }

    @Test
    fun `the deny-list applies before anything is sent`() = runTest {
        val result = backend.runCommand("rm -rf / && echo done")
        assertFalse(result.success)
        assertTrue(result.message, "blocked by safety policy" in result.message)
        assertEquals(0, link.calls)
    }

    @Test
    fun `stdin reaches the companion through a pipe`() = runTest {
        service.script(exited(0))
        backend.runCommand("cat", stdin = "hello from gotcha")
        assertEquals("hello from gotcha", service.stdinSeen.single())
    }

    // ---- files ----

    @Test
    fun `the guest never sees a host path`() {
        assertNull(backend.guestPathFor(File("/storage/emulated/0/Download/a.mp3")))
    }

    @Test
    fun `export writes the file only when every byte arrived`() = runTest {
        val destination = File(folder.root, "out.txt")
        service.files["/root/out.txt"] = "twelve bytes"
        val ok = backend.exportFile("~/out.txt", destination)
        assertTrue(ok.message, ok.success)
        assertEquals("twelve bytes", destination.readText())

        val short = File(folder.root, "short.txt")
        service.files["/root/short.txt"] = "cut"
        service.reportedSizes["/root/short.txt"] = 99
        val truncated = backend.exportFile("/root/short.txt", short)
        assertFalse(truncated.success)
        assertTrue(truncated.message, "3 of 99 bytes" in truncated.message)
        assertFalse(short.exists())
    }

    @Test
    fun `export of a missing guest file says so`() = runTest {
        val result = backend.exportFile("/root/nothing", File(folder.root, "x"))
        assertFalse(result.success)
        assertTrue(result.message, "not a readable regular file" in result.message)
    }

    @Test
    fun `import hands the companion a read-only descriptor`() = runTest {
        val source = folder.newFile("in.txt").apply { writeText("payload") }
        val result = backend.importFile(source, "~/in/in.txt")
        assertTrue(result.message, result.success)
        assertEquals("payload", service.files["/root/in/in.txt"])
    }

    // ---- choosing a backend ----

    @Test
    fun `the companion is preferred only once it can actually run something`() {
        fun companion(installed: Boolean, trusted: Boolean, ready: Boolean?) =
            CompanionShellBackend.CompanionStatus(CompanionIdentity(installed, trusted, "1.0"), ready)
        val termuxWorks = object : ShellStatus {
            override val usable = true
            override val envLine = ""
        }
        val noTermux = object : ShellStatus {
            override val usable = false
            override val envLine = ""
        }
        assertTrue(ShellBackends.prefersCompanion(companion(true, true, true), termuxWorks))
        // Installed but its Linux system is missing or unknown: a working Termux is kept.
        assertFalse(ShellBackends.prefersCompanion(companion(true, true, false), termuxWorks))
        assertFalse(ShellBackends.prefersCompanion(companion(true, true, null), termuxWorks))
        // With no Termux, the companion is the only way forward even before it is ready.
        assertTrue(ShellBackends.prefersCompanion(companion(true, true, false), noTermux))
        assertFalse(ShellBackends.prefersCompanion(companion(true, false, true), noTermux))
        assertFalse(ShellBackends.prefersCompanion(companion(false, false, null), noTermux))
    }

    // ---- fakes ----

    private fun status(state: Int, exit: Int, stdout: String = "", stderr: String = "") =
        JobStatus().apply {
            this.state = state
            exitCode = exit
            this.stdout = stdout
            this.stderr = stderr
        }

    private fun running(stdout: String = "") = status(JobStatus.RUNNING, 0, stdout)

    private fun exited(exit: Int, stdout: String = "", stderr: String = "") =
        status(JobStatus.EXITED, exit, stdout, stderr)

    private class FakeLink(private val service: IShellService) : CompanionLink {
        var identity = CompanionIdentity(installed = true, trusted = true, versionName = "0.0.1")
        var calls = 0

        override fun identity() = identity

        override suspend fun <T> call(block: (IShellService) -> T): T {
            calls++
            if (!identity.installed) throw CompanionUnavailable(CompanionUnavailable.Reason.NOT_INSTALLED)
            if (!identity.trusted) throw CompanionUnavailable(CompanionUnavailable.Reason.UNTRUSTED)
            return block(service)
        }
    }

    /** A companion that answers from a script. A finished job is followed by an empty final poll. */
    private class FakeShellService : IShellService.Stub() {
        val shellInfo = ShellInfo().apply {
            interfaceVersion = CompanionShellBackend.INTERFACE_VERSION
            rootfsReady = true
        }
        var infoFailure: RuntimeException? = null
        var startFailure: RuntimeException? = null
        var pollFailure: Exception? = null
        var repeatLast = false

        val started = mutableListOf<RunRequest>()
        val stdinSeen = mutableListOf<String>()
        val killed = mutableListOf<String>()
        val polledStdoutOffsets = mutableListOf<Long>()
        val files = mutableMapOf<String, String>()
        val reportedSizes = mutableMapOf<String, Long>()

        private val polls = ArrayDeque<JobStatus>()
        private var last: JobStatus? = null

        fun script(vararg statuses: JobStatus) {
            polls.addAll(statuses)
        }

        override fun getInfo(): ShellInfo {
            infoFailure?.let { throw it }
            return shellInfo
        }

        override fun startJob(request: RunRequest): String {
            startFailure?.let { throw it }
            started += request
            request.stdin?.let { pipe ->
                stdinSeen += ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes().decodeToString() }
            }
            return "job-${started.size}"
        }

        override fun pollJob(jobId: String, stdoutOffset: Long, stderrOffset: Long): JobStatus {
            pollFailure?.let { throw it }
            polledStdoutOffsets += stdoutOffset
            val next = polls.removeFirstOrNull()
                ?: last?.takeIf { repeatLast }
                // The real companion answers a drained, finished job with its state and no output.
                ?: last!!.let { drained(it) }
            last = next
            return next.apply {
                this.stdoutOffset = stdoutOffset + stdout.length
                this.stderrOffset = stderrOffset + stderr.length
            }
        }

        private fun drained(status: JobStatus) = JobStatus().apply {
            state = status.state
            exitCode = status.exitCode
            stdout = ""
            stderr = ""
        }

        private fun result(exit: Int, out: String = "") = RunResult().apply {
            exitCode = exit
            stdout = out
            stderr = ""
        }

        override fun killJob(jobId: String): Boolean {
            killed += jobId
            return true
        }

        override fun run(request: RunRequest): RunResult {
            val path = request.cmd.substringAfter("-- '").substringBeforeLast("'")
            val content = files[path]
            val size = content?.let { (reportedSizes[path] ?: it.length.toLong()).toString() }
            return result(if (content == null) 1 else 0, size.orEmpty())
        }

        override fun exportFile(guestPath: String): ParcelFileDescriptor? {
            val content = files[guestPath] ?: return null
            val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
            ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { it.write(content.toByteArray()) }
            return readEnd
        }

        override fun importFile(source: ParcelFileDescriptor, guestPath: String): RunResult {
            files[guestPath] = ParcelFileDescriptor.AutoCloseInputStream(source).use { it.readBytes().decodeToString() }
            return result(0)
        }

        override fun openSession(rows: Int, cols: Int): ParcelFileDescriptor = error("not used")
        override fun install(callback: IInstallCallback?): String? = null
        override fun repair(): RunResult = result(0)
        override fun reset() = Unit
    }
}
