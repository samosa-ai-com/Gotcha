package com.gotcha.tools

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tier 4 — arbitrary shell inside Termux, via Termux's official RUN_COMMAND plugin API.
 *
 * Stock Android has no usable user-space: no `pkg`/`apt`, no `python3`, few binaries. Termux
 * ships a full one, but it cannot be embedded — it runs under its own uid with its own rootfs
 * at `/data/data/com.termux/files` inside a separate SELinux context, so Gotcha can neither
 * exec its binaries nor read its files. What Termux *does* expose is
 * `com.termux.app.RunCommandService`: we send it a command, it runs it in the background (no
 * terminal pop-up) and returns stdout/stderr/exit code through a [PendingIntent].
 *
 * Two things must be true before that works, and only the first is detectable:
 *  1. `com.termux.permission.RUN_COMMAND` is granted. It is a **`dangerous`** permission that
 *     Termux itself declares, so listing it in the manifest is not enough — it needs a runtime
 *     grant, raised through [ToolResult.TERMUX_ACCESS]. A caveat worth knowing: a custom
 *     dangerous permission whose defining app was installed *after* Gotcha may not be
 *     grantable until Gotcha is updated or reinstalled.
 *  2. `allow-external-apps=true` is set in Termux's `~/.termux/termux.properties`. That file
 *     lives in Termux's private storage, so we cannot read it. When it is false Termux drops
 *     the intent silently and no result ever arrives — indistinguishable from here from a slow
 *     command, which is why the timeout message names both causes.
 *
 * Availability is gated on the Termux package being *installed* ([Capability.TERMUX]), not on
 * the grant — gating on the grant would hide the only tool that can raise the prompt. The
 * unprivileged [TerminalTool] (`run_command`) stays the tool for Gotcha's own sandbox, which is
 * invisible from Termux's uid.
 */
class TermuxTool(
    private val context: Context,
    private val defaultTimeoutSeconds: Int = 60,
    private val maxOutputBytes: Int = 32 * 1024
) {

    /** What the model needs to know about Termux, rendered into the `<env>` block each turn. */
    data class TermuxStatus(
        val installed: Boolean,
        val permissionGranted: Boolean,
        val versionName: String?
    )

    /**
     * Irreversible, device-destroying operations we refuse even here. Deliberately
     * [RootTool]'s narrow list rather than [TerminalTool]'s: `rm -r` inside Termux's own
     * `$HOME` is ordinary housekeeping, and `pkg` needs it.
     */
    private val denyPatterns = listOf(
        Regex("""\bmkfs\b"""), // reformat a filesystem
        Regex("""\bdd\b[^\n]*\bof=/dev/"""), // raw write to a block device
        Regex("""\brm\s+-[a-z]*r[a-z]*f?\s+/(\s|$)"""), // rm -rf /  (wipe the root tree)
        Regex("""\bfastboot\b"""),
        Regex("""\brecovery\b[^\n]*--wipe""")
    )

    fun status(): TermuxStatus {
        val info = runCatching { context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0) }.getOrNull()
        val granted = runCatching {
            ContextCompat.checkSelfPermission(context, PERMISSION_RUN_COMMAND) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return TermuxStatus(installed = info != null, permissionGranted = granted, versionName = info?.versionName)
    }

    /** Run [command] through Termux's `sh`, returning stdout/stderr/exit code. */
    suspend fun runCommand(
        command: String,
        workingDir: String? = null,
        timeoutSeconds: Int? = null
    ): ToolResult {
        val trimmed = command.trim()
        validate(trimmed)?.let { return it }

        val status = status()
        if (!status.installed) return notInstalled()
        if (!status.permissionGranted) return permissionNeeded()

        val timeout = (timeoutSeconds ?: defaultTimeoutSeconds).coerceIn(1, MAX_TIMEOUT_SECONDS)
        val requestCode = nextRequestCode.getAndIncrement()
        val deferred = CompletableDeferred<Bundle>()
        pendingResults[requestCode] = deferred
        try {
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                TermuxResultReceiver.resultIntent(context, requestCode),
                pendingIntentFlags()
            )
            runCatching { context.startService(commandIntent(trimmed, workingDir, pendingIntent)) }
                .exceptionOrNull()
                ?.let { return startFailed(it) }

            val bundle = withTimeoutOrNull(timeout * 1000L) { deferred.await() }
                ?: return timedOut(trimmed, timeout)
            return formatResult(bundle)
        } finally {
            pendingResults.remove(requestCode)
        }
    }

    /** Turn Termux's result bundle into a [ToolResult]. Split out so it is testable without Termux. */
    internal fun formatResult(bundle: Bundle): ToolResult {
        val err = bundle.numeric(RESULT_ERR) ?: 0
        val errmsg = bundle.getString(RESULT_ERRMSG)?.trim().orEmpty()
        if (err != 0) {
            return ToolResult.error(
                "Termux refused or could not run the command (error code $err)" +
                    if (errmsg.isEmpty()) "." else ": ${cap(errmsg)}"
            )
        }
        val exit = bundle.numeric(RESULT_EXIT_CODE) ?: -1
        val stdout = bundle.getString(RESULT_STDOUT).orEmpty()
        val stderr = bundle.getString(RESULT_STDERR).orEmpty()
        val message = buildString {
            append("exit code: $exit")
            if (stdout.isNotEmpty()) append("\nstdout:\n${cap(stdout)}")
            if (stderr.isNotEmpty()) append("\nstderr:\n${cap(stderr)}")
            if (stdout.isEmpty() && stderr.isEmpty()) append("\n(no output)")
            if (wasTruncatedByTermux(bundle, RESULT_STDOUT_ORIGINAL_LENGTH, stdout) ||
                wasTruncatedByTermux(bundle, RESULT_STDERR_ORIGINAL_LENGTH, stderr)
            ) {
                append("\n…(Termux truncated the output at its ~100KB result limit)")
            }
            if (errmsg.isNotEmpty()) append("\nTermux note: ${cap(errmsg)}")
        }
        return ToolResult(success = exit == 0, message = message)
    }

    /** Cheap checks that need neither Termux nor a permission, so they report the real problem first. */
    private fun validate(trimmed: String): ToolResult? {
        if (trimmed.isEmpty()) {
            return ToolResult.error(
                "Empty command. Provide a shell command to run in Termux (e.g. 'pkg install python -y')."
            )
        }
        if (trimmed.length > MAX_COMMAND_CHARS) {
            return ToolResult.error(
                "Command is ${trimmed.length} characters, over Termux's ~${MAX_COMMAND_CHARS / 1024}KB limit for a " +
                    "single command. You may write the script to a file with write_file and run that file instead."
            )
        }
        denyPatterns.firstOrNull { it.containsMatchIn(trimmed) }?.let {
            return ToolResult.error(
                "Command blocked by safety policy (irreversible/device-destroying): $trimmed. You may use available " +
                    "tools (list_files, read_file, grep) instead for safer operations."
            )
        }
        return null
    }

    private fun commandIntent(command: String, workingDir: String?, result: PendingIntent): Intent =
        Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, "$TERMUX_BIN_PREFIX/sh")
            putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
            putExtra(EXTRA_WORKDIR, workingDir?.trim()?.takeIf { it.isNotEmpty() } ?: TERMUX_HOME)
            // Headless: run without opening a terminal session in front of the user.
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_PENDING_INTENT, result)
        }

    /**
     * Termux writes the result bundle into the intent it sends back, so the PendingIntent has
     * to be mutable. It is explicit (it names our own receiver), so this is not the
     * implicit-mutable pattern lint warns about.
     */
    private fun pendingIntentFlags(): Int {
        var flags = PendingIntent.FLAG_ONE_SHOT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        return flags
    }

    private fun notInstalled() = ToolResult.error(
        "Termux is not installed, so there is no Linux user-space to run this in. You may ask the user to install " +
            "Termux from F-Droid (the Play Store build is unmaintained), or use run_command for the plain " +
            "unprivileged shell available inside Gotcha's own sandbox."
    )

    private fun permissionNeeded() = ToolResult.permissionNeeded(
        ToolResult.TERMUX_ACCESS,
        "Running commands in Termux needs Termux's \"Run commands\" permission, which has not been granted. " +
            "I have asked for it — please allow it, then in Termux add the line `allow-external-apps=true` to " +
            "`~/.termux/termux.properties` and restart Termux, and ask again. (If no permission dialog appeared, " +
            "Termux was installed after Gotcha — reinstall or update Gotcha so Android can grant it.)"
    )

    private fun startFailed(cause: Throwable) = ToolResult.error(
        "Could not reach Termux's RUN_COMMAND service: ${cause.message}. Termux may have been disabled or stopped " +
            "by the system. You may open Termux once and ask again, or use run_command for the app's own sandbox."
    )

    private fun timedOut(command: String, timeout: Int) = ToolResult.error(
        "No result from Termux after ${timeout}s for: $command. Two things cause this and I cannot tell them " +
            "apart: the command may still be running in Termux (I cannot kill another app's process, so check " +
            "Termux itself), or `allow-external-apps=true` is missing from `~/.termux/termux.properties`, in which " +
            "case Termux silently ignored the request. You may retry with a larger timeout_seconds."
    )

    private fun cap(text: String): String =
        if (text.length <= maxOutputBytes) text else text.take(maxOutputBytes) + "\n…(output capped)"

    private fun wasTruncatedByTermux(bundle: Bundle, lengthKey: String, received: String): Boolean =
        (bundle.numeric(lengthKey) ?: received.length) > received.length

    /** Termux sends lengths as strings and codes as ints; tolerate either for every numeric field. */
    private fun Bundle.numeric(key: String): Int? {
        if (!containsKey(key)) return null
        runCatching { getString(key) }.getOrNull()?.let { return it.trim().toIntOrNull() }
        return runCatching { getInt(key) }.getOrNull()
    }

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

        private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        private const val TERMUX_FILES = "/data/data/com.termux/files"
        private const val TERMUX_BIN_PREFIX = "$TERMUX_FILES/usr/bin"
        const val TERMUX_HOME = "$TERMUX_FILES/home"

        // Result-bundle keys, from termux-shared's TermuxConstants.
        internal const val RESULT_BUNDLE = "result"
        internal const val RESULT_STDOUT = "stdout"
        internal const val RESULT_STDOUT_ORIGINAL_LENGTH = "stdout_original_length"
        internal const val RESULT_STDERR = "stderr"
        internal const val RESULT_STDERR_ORIGINAL_LENGTH = "stderr_original_length"
        internal const val RESULT_EXIT_CODE = "exitCode"
        internal const val RESULT_ERR = "err"
        internal const val RESULT_ERRMSG = "errmsg"

        /** Termux caps a single command near 128KB; leave headroom for the rest of the intent. */
        private const val MAX_COMMAND_CHARS = 100 * 1024

        /** `pkg install` is slow, so this is far above [TerminalTool]'s 120s ceiling. */
        private const val MAX_TIMEOUT_SECONDS = 600

        private val nextRequestCode = AtomicInteger(1)

        /**
         * In-flight commands, keyed by PendingIntent request code so concurrent calls cannot
         * collide. Held in memory only: if Gotcha's process dies the awaiting coroutine and the
         * whole agent turn die with it, so persisting results would have nothing left to resume.
         */
        private val pendingResults = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()

        /** Hands a result from [TermuxResultReceiver] to the coroutine waiting on it. */
        internal fun completeResult(requestCode: Int, bundle: Bundle) {
            pendingResults.remove(requestCode)?.complete(bundle)
        }

        /** Test seam: awaits nothing, just exposes whether a request is still outstanding. */
        internal fun isPending(requestCode: Int): Boolean = pendingResults.containsKey(requestCode)
    }
}
