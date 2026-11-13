package com.gotcha.tools

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.gotcha.shell.JobStatus
import com.gotcha.shell.RunRequest
import com.gotcha.shell.RunResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/**
 * The Linux user-space provided by Gotcha Shell (`com.gotcha.shell`), Gotcha's own companion app:
 * Ubuntu under PRoot, reached over a bound service that only Gotcha may call.
 *
 * It is a separate app on purpose. It runs under its own Android uid, so nothing a command does
 * can reach Gotcha's settings, chats or memory, and it holds no storage permission, so it cannot
 * see shared storage either. Files therefore cross only as descriptors ([importFile],
 * [exportFile]); [guestPathFor] is always null.
 *
 * The interface is one-way: Gotcha calls the companion, and nothing here lets the companion call
 * back or write Gotcha's data. What comes back — output and files — is untrusted input.
 */
class CompanionShellBackend(
    private val context: Context,
    private val link: CompanionLink = CompanionConnection.get(context),
    private val defaultTimeoutSeconds: Int = 120,
    private val maxOutputBytes: Int = 32 * 1024
) : ShellBackend {

    data class CompanionStatus(
        val identity: CompanionIdentity,
        /** Last known state of the Linux system inside the companion; null when never asked. */
        val rootfsReady: Boolean?
    ) : ShellStatus {
        override val usable: Boolean get() = identity.installed && identity.trusted

        override val envLine: String
            get() = when {
                !identity.installed ->
                    "  Gotcha Shell installed: no (so run_shell is unavailable — no Linux user-space)"
                !identity.trusted ->
                    "  Gotcha Shell installed: an app with its package name is present but is not signed " +
                        "with Gotcha's key, so run_shell will refuse to use it"
                rootfsReady == false ->
                    "  Gotcha Shell installed: yes (version ${identity.versionName ?: "unknown"}) but its " +
                        "Linux system is not installed yet — the user installs it from Settings ▸ Linux shell"
                else ->
                    "  Gotcha Shell installed: yes (version ${identity.versionName ?: "unknown"}, " +
                        "Ubuntu under PRoot; home is /root; it cannot see shared storage)"
            }
    }

    override val id: String get() = ID

    override val home: String get() = GUEST_HOME

    override fun status(): CompanionStatus =
        CompanionStatus(link.identity(), CompanionShellState.rootfsReady(context))

    override fun guestPathFor(hostFile: File): String? = null

    // ---- running commands ----

    override suspend fun runCommand(
        command: String,
        workingDir: String?,
        timeoutSeconds: Int?,
        stdin: String?
    ): ToolResult {
        val trimmed = command.trim()
        when (val rejection = ShellCommandGuard.check(trimmed, stdin, MAX_COMMAND_CHARS)) {
            null -> Unit
            ShellCommandGuard.Rejection.Empty -> return CompanionMessages.emptyCommand()
            is ShellCommandGuard.Rejection.TooLarge ->
                return CompanionMessages.tooLarge(rejection.total, rejection.limitChars)
            ShellCommandGuard.Rejection.Blocked -> return CompanionMessages.blocked(trimmed)
        }
        val packageManager = PackageManagerCommands.isPackageManager(trimmed)
        val adaptiveDefault = if (timeoutSeconds == null && packageManager) {
            PACKAGE_MANAGER_TIMEOUT_SECONDS
        } else {
            defaultTimeoutSeconds
        }
        val timeout = (timeoutSeconds ?: adaptiveDefault).coerceIn(1, MAX_TIMEOUT_SECONDS)
        val request = RunRequest().apply {
            cmd = if (packageManager) PackageManagerCommands.withNonInteractive(trimmed) else trimmed
            cwd = workingDir?.trim()?.takeIf { it.isNotEmpty() }?.let(::expandHome)
            this.timeoutSeconds = timeout
        }
        return guarded {
            ready()?.let { return@guarded it }
            val jobId = startJob(request, stdin)
            awaitJob(jobId, trimmed, timeout)
        }
    }

    /** Starts [request] as a job, feeding [stdin] through a pipe the companion reads to the end. */
    private suspend fun startJob(request: RunRequest, stdin: String?): String {
        if (stdin == null) return link.call { it.startJob(request) }
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        val bytes = stdin.toByteArray()
        val write = {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { it.write(bytes) } }
        }
        // A pipe holds about 64 KB. Anything that fits is written before the call; anything
        // larger would block here for ever, so it is written while the companion reads.
        if (bytes.size <= PIPE_SAFE_BYTES) {
            write()
        } else {
            thread(name = "gotcha-shell-stdin", isDaemon = true) { write() }
        }
        return try {
            request.stdin = readEnd
            link.call { it.startJob(request) }
        } finally {
            runCatching { readEnd.close() }
        }
    }

    /**
     * Polls [jobId] until it ends, keeping up to [maxOutputBytes] of each stream.
     *
     * The companion enforces the timeout itself and reports `TIMED_OUT`, so there is no second
     * clock here. If this coroutine is cancelled — the user stopped the turn — the job is killed
     * rather than left running, which is something the Termux route cannot do.
     */
    private suspend fun awaitJob(jobId: String, command: String, timeout: Int): ToolResult {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var stdoutOffset = 0L
        var stderrOffset = 0L
        var dropped = false
        var pollDelay = FIRST_POLL_MS
        var finished = false
        try {
            while (true) {
                val status = link.call { it.pollJob(jobId, stdoutOffset, stderrOffset) }
                if (status.state == JobStatus.UNKNOWN) {
                    finished = true
                    return CompanionMessages.jobLost()
                }
                stdout.appendCapped(status.stdout)
                stderr.appendCapped(status.stderr)
                stdoutOffset = status.stdoutOffset
                stderrOffset = status.stderrOffset
                dropped = dropped || status.outputDropped
                val drained = status.stdout.isNullOrEmpty() && status.stderr.isNullOrEmpty()
                if (status.state != JobStatus.RUNNING && drained) {
                    finished = true
                    val overflowed = dropped || stdoutOffset > maxOutputBytes || stderrOffset > maxOutputBytes
                    return jobResult(status, command, timeout, stdout.toString(), stderr.toString(), overflowed)
                }
                if (status.state == JobStatus.RUNNING) {
                    delay(pollDelay)
                    pollDelay = (pollDelay * 2).coerceAtMost(MAX_POLL_MS)
                }
            }
        } finally {
            if (!finished) {
                withContext(NonCancellable) { runCatching { link.call { it.killJob(jobId) } } }
            }
        }
    }

    private fun jobResult(
        status: JobStatus,
        command: String,
        timeout: Int,
        stdout: String,
        stderr: String,
        overflowed: Boolean
    ): ToolResult {
        val message = ShellResultFormat.format(
            exitCode = status.exitCode,
            stdout = stdout,
            stderr = stderr,
            notes = if (overflowed) listOf(OUTPUT_CAPPED_NOTE) else emptyList()
        )
        return when (status.state) {
            JobStatus.TIMED_OUT -> CompanionMessages.timedOut(timeout, partialOutput(stdout, stderr))
            JobStatus.KILLED -> CompanionMessages.killed(partialOutput(stdout, stderr))
            else -> when {
                PackageManagerCommands.isPackageManager(command) &&
                    PackageManagerCommands.reportsLockHeld(message) ->
                    CompanionMessages.lockHeld(PackageManagerCommands.lockHolderPid(message))
                else -> ToolResult(success = status.exitCode == 0, message = message)
            }
        }
    }

    private fun partialOutput(stdout: String, stderr: String): String =
        listOf(stdout.trim(), stderr.trim()).filter { it.isNotEmpty() }.joinToString("\n")

    private fun StringBuilder.appendCapped(chunk: String?) {
        if (chunk.isNullOrEmpty()) return
        val room = maxOutputBytes - length
        if (room > 0) append(chunk.take(room))
    }

    // ---- files ----

    /** Copies [hostFile] into the guest at [guestPath], creating parent directories. */
    suspend fun importFile(hostFile: File, guestPath: String): ToolResult = guarded {
        ready()?.let { return@guarded it }
        val result = withContext(Dispatchers.IO) {
            ParcelFileDescriptor.open(hostFile, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                link.call { it.importFile(source, expandHome(guestPath)) }
            }
        }
        if (result.exitCode == 0) {
            ToolResult.ok("Copied '${hostFile.name}' into the shell at '${expandHome(guestPath)}'.")
        } else {
            ToolResult.error(
                "Could not copy '${hostFile.name}' into the shell at '$guestPath': " +
                    result.stderr.orEmpty().trim().ifEmpty { "exit code ${result.exitCode}" }
            )
        }
    }

    override suspend fun exportFile(guestPath: String, destination: File): ToolResult {
        FileResolver(context).checkWritePermission(destination)?.let { return it }
        destination.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                return ToolResult.error("Could not create the destination directory '${parent.canonicalPath}'.")
            }
        }
        val source = expandHome(guestPath)
        return guarded {
            ready()?.let { return@guarded it }
            // The export is a pipe, which ends the same way whether the file is complete or the
            // companion died halfway. Ask for the size first so a short copy is never kept.
            val expected = guestFileSize(source)
            val pipe = link.call { it.exportFile(source) } ?: return@guarded CompanionMessages.exportMissing(source)
            val tmp = File(destination.parentFile, ".${destination.name}.part")
            val received = withContext(Dispatchers.IO) {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(pipe).use { input ->
                        tmp.outputStream().use { input.copyTo(it, COPY_BUFFER_BYTES) }
                    }
                } catch (e: IOException) {
                    tmp.delete()
                    throw e
                }
            }
            if (expected != null && received != expected) {
                tmp.delete()
                return@guarded CompanionMessages.exportTruncated(source, expected, received)
            }
            if (!replace(tmp, destination)) {
                tmp.delete()
                return@guarded ToolResult.error("Could not write '${destination.canonicalPath}'.")
            }
            ToolResult.ok(
                "Copied '$source' out of the shell to '${destination.canonicalPath}' " +
                    "(${FileResolver.formatSizeStatic(received)})."
            )
        }
    }

    private suspend fun guestFileSize(guestPath: String): Long? {
        val request = RunRequest().apply {
            cmd = "stat -c %s -- ${FfmpegCommand.shellQuote(guestPath)}"
            timeoutSeconds = STAT_TIMEOUT_SECONDS
        }
        val result: RunResult = link.call { it.run(request) }
        return result.stdout?.trim()?.toLongOrNull()?.takeIf { result.exitCode == 0 }
    }

    private fun replace(tmp: File, destination: File): Boolean {
        if (tmp.renameTo(destination)) return true
        if (destination.exists() && !destination.delete()) return false
        return tmp.renameTo(destination)
    }

    // ---- shared plumbing ----

    /**
     * Null when the companion can take a command; otherwise the reason it cannot. Cheap — one
     * `getInfo` — and it also refreshes what [status] reports, so the `<env>` line and the
     * backend choice follow the companion's real state.
     */
    private suspend fun ready(): ToolResult? {
        val info = link.call { it.info }
        CompanionShellState.setRootfsReady(context, info.rootfsReady)
        return when {
            info.interfaceVersion != INTERFACE_VERSION ->
                CompanionMessages.versionMismatch(info.interfaceVersion, INTERFACE_VERSION)
            !info.rootfsReady -> CompanionMessages.rootfsMissing(info.installing)
            else -> null
        }
    }

    /** Turns everything that can go wrong on the way to the companion into a message for the model. */
    private suspend fun guarded(block: suspend () -> ToolResult): ToolResult = try {
        block()
    } catch (e: CancellationException) {
        // A CancellationException is an IllegalStateException; it must not be reported as a
        // rejected request, or stopping a turn would look like a shell error.
        throw e
    } catch (e: CompanionUnavailable) {
        CompanionMessages.unavailable(e)
    } catch (e: SecurityException) {
        CompanionMessages.refused(e.message)
    } catch (e: IllegalStateException) {
        when {
            e.message.orEmpty().contains("Too many", ignoreCase = true) -> CompanionMessages.tooManyJobs()
            e.message.orEmpty().contains("No rootfs", ignoreCase = true) -> {
                CompanionShellState.setRootfsReady(context, false)
                CompanionMessages.rootfsMissing(installing = false)
            }
            else -> CompanionMessages.rejected(e.message)
        }
    } catch (e: IllegalArgumentException) {
        CompanionMessages.rejected(e.message)
    } catch (_: RemoteException) {
        // DeadObjectException included: the companion process died, and its jobs with it.
        CompanionMessages.died()
    } catch (e: IOException) {
        ToolResult.error("The file copy failed: ${e.message ?: e.javaClass.simpleName}.")
    }

    /** Expands a leading `~` or `$HOME` to the guest's home; the companion wants absolute paths. */
    internal fun expandHome(path: String): String = when {
        path == "~" || path == "\$HOME" -> GUEST_HOME
        path.startsWith("~/") -> GUEST_HOME + path.removePrefix("~")
        path.startsWith("\$HOME/") -> GUEST_HOME + path.removePrefix("\$HOME")
        else -> path
    }

    companion object {
        const val ID = "companion"

        /** The Binder interface version this build was compiled against; see `shell-interface.lock`. */
        const val INTERFACE_VERSION = 2

        private const val GUEST_HOME = "/root"

        private const val OUTPUT_CAPPED_NOTE = "…(output capped; redirect to a file and read that for the rest)"

        /** The command rides in one Binder transaction (1 MB shared); this leaves ample headroom. */
        private const val MAX_COMMAND_CHARS = 100 * 1024

        private const val PACKAGE_MANAGER_TIMEOUT_SECONDS = 300

        /** The companion allows a job 24 h; one tool call should not hold a turn anywhere near that. */
        private const val MAX_TIMEOUT_SECONDS = 3600

        private const val STAT_TIMEOUT_SECONDS = 15
        private const val PIPE_SAFE_BYTES = 32 * 1024
        private const val FIRST_POLL_MS = 50L
        private const val MAX_POLL_MS = 1000L
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

/** The one shape every backend reports a finished command in; `media_convert` keys on [PREFIX]. */
object ShellResultFormat {
    const val PREFIX = "exit code:"

    fun format(exitCode: Int, stdout: String, stderr: String, notes: List<String> = emptyList()): String =
        buildString {
            append("$PREFIX $exitCode")
            if (stdout.isNotEmpty()) append("\nstdout:\n$stdout")
            if (stderr.isNotEmpty()) append("\nstderr:\n$stderr")
            if (stdout.isEmpty() && stderr.isEmpty()) append("\n(no output)")
            notes.forEach { append("\n$it") }
        }
}

/**
 * What Gotcha last learned about the companion's Linux system, kept across restarts.
 *
 * Whether the rootfs is installed is only knowable by asking the companion, which means binding
 * and a blocking call — not something [ShellBackend.status] may do, since it runs while the tool
 * list and the `<env>` block are built. So every real call records the answer here and the
 * synchronous paths read it back. A stale "ready" corrects itself on the next command.
 */
internal object CompanionShellState {
    private const val PREFS = "companion_shell"
    private const val KEY_ROOTFS_READY = "rootfs_ready"

    fun rootfsReady(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_ROOTFS_READY)) prefs.getBoolean(KEY_ROOTFS_READY, false) else null
    }

    fun setRootfsReady(context: Context, ready: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ROOTFS_READY, ready).apply()
    }
}
