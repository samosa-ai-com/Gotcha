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
        /**
         * Whether this Termux build actually exposes `RunCommandService`. The Google Play build
         * strips the whole plugin API — Play policy forbids arbitrary command execution — so it
         * declares no `RUN_COMMAND` permission and registers no services at all. Without this
         * check we would ask for a permission the device cannot define, and the user would face
         * a dialog that never appears.
         */
        val pluginApiAvailable: Boolean,
        val permissionGranted: Boolean,
        val versionName: String?
    ) {
        /** Termux can actually run something for us (modulo the grant). */
        val usable: Boolean get() = installed && pluginApiAvailable
    }

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
        return TermuxStatus(
            installed = info != null,
            pluginApiAvailable = info != null && runCatching { runCommandServiceExists() }.getOrDefault(false),
            permissionGranted = granted,
            versionName = info?.versionName
        )
    }

    /**
     * Termux's rootfs, taken from the installed package rather than assumed.
     *
     * `/data/data/com.termux` is only the primary user's path; under a secondary user or a work
     * profile the same package lives at `/data/user/<id>/com.termux`, where the hardcoded path
     * names a `sh` that does not exist and every command would fail with an error pointing
     * nowhere useful.
     */
    internal fun termuxFiles(): String {
        val dataDir = runCatching {
            context.packageManager.getApplicationInfo(TERMUX_PACKAGE, 0).dataDir
        }.getOrNull()
        return if (dataDir.isNullOrBlank()) DEFAULT_TERMUX_FILES else "$dataDir/files"
    }

    /** Termux's `$HOME`, the default working directory for a command. */
    internal fun termuxHome(): String = "${termuxFiles()}/home"

    /** Whether `com.termux.RUN_COMMAND` resolves to a service — false on the Play build. */
    private fun runCommandServiceExists(): Boolean =
        context.packageManager.resolveService(
            Intent(ACTION_RUN_COMMAND).setPackage(TERMUX_PACKAGE),
            0
        ) != null

    /** Run [command] through Termux's `sh`, returning stdout/stderr/exit code. */
    suspend fun runCommand(
        command: String,
        workingDir: String? = null,
        timeoutSeconds: Int? = null,
        stdin: String? = null
    ): ToolResult {
        val trimmed = command.trim()
        validate(trimmed, stdin)?.let { return it }

        val status = status()
        if (!status.installed) return notInstalled()
        if (!status.pluginApiAvailable) return pluginApiMissing(status.versionName)
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
            runCatching { context.startService(commandIntent(trimmed, workingDir, stdin, pendingIntent)) }
                .exceptionOrNull()
                ?.let { return startFailed(it) }

            val bundle = withTimeoutOrNull(timeout * 1000L) { deferred.await() }
                ?: return timedOut(trimmed, timeout, hadStdin = stdin != null)
            return formatResult(bundle)
        } finally {
            pendingResults.remove(requestCode)
        }
    }

    /** Turn Termux's result bundle into a [ToolResult]. Split out so it is testable without Termux. */
    internal fun formatResult(bundle: Bundle): ToolResult {
        val err = bundle.numeric(RESULT_ERR) ?: ERRNO_SUCCESS
        val errmsg = bundle.getString(RESULT_ERRMSG)?.trim().orEmpty()
        if (err != ERRNO_SUCCESS) {
            return ToolResult.error(
                "Termux refused or could not run the command (${errnoLabel(err)})" +
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

    /** Human-readable form of Termux's errno, which is not a plain "0 means fine" code. */
    private fun errnoLabel(err: Int): String = when (err) {
        ERRNO_CANCELLED -> "cancelled"
        ERRNO_MINOR_FAILURES -> "completed with minor failures"
        ERRNO_FAILED -> "failed"
        else -> "error code $err"
    }

    /** Cheap checks that need neither Termux nor a permission, so they report the real problem first. */
    private fun validate(trimmed: String, stdin: String? = null): ToolResult? {
        if (trimmed.isEmpty()) {
            return ToolResult.error(
                "Empty command. Provide a shell command to run in Termux (e.g. 'pkg install python -y')."
            )
        }
        // Command and stdin ride in the same binder transaction, so they share the budget.
        val total = trimmed.length + (stdin?.length ?: 0)
        if (total > MAX_COMMAND_CHARS) {
            return ToolResult.error(
                "Command plus stdin is $total characters, over Termux's ~${MAX_COMMAND_CHARS / 1024}KB limit for a " +
                    "single request. You may write the script to a file with write_file and run that file instead."
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

    /** Builds the intent without sending it, so the extras can be asserted without Termux. */
    internal fun buildCommandIntentForTest(command: String, workingDir: String?, stdin: String?): Intent =
        commandIntent(command, workingDir, stdin, result = null)

    private fun commandIntent(
        command: String,
        workingDir: String?,
        stdin: String?,
        result: PendingIntent?
    ): Intent =
        Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, "${termuxFiles()}/usr/bin/sh")
            putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
            putExtra(EXTRA_WORKDIR, workingDir?.trim()?.takeIf { it.isNotEmpty() } ?: termuxHome())
            // Headless: run without opening a terminal session in front of the user.
            putExtra(EXTRA_BACKGROUND, true)
            // Without this, anything that reads stdin blocks until our timeout and reports a
            // hang rather than the prompt it is actually stuck on. Termux 0.109+.
            if (stdin != null) putExtra(EXTRA_STDIN, stdin)
            if (result != null) putExtra(EXTRA_PENDING_INTENT, result)
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

    /**
     * Termux is installed but has no RunCommandService. In practice this means the Google Play
     * build, which removes the plugin API wholesale to satisfy Play policy — no amount of
     * permission granting or property setting will make it work.
     */
    private fun pluginApiMissing(versionName: String?) = ToolResult.error(
        "Termux is installed (version ${versionName ?: "unknown"}) but this build has no RUN_COMMAND " +
            "service, so it cannot run commands for other apps. The Google Play build of Termux removes " +
            "that API. Tell the user to replace it with the F-Droid or GitHub build of Termux if they want " +
            "this; nothing can be granted to fix the Play build. Meanwhile use run_command for the plain " +
            "unprivileged shell. Do not retry."
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

    /**
     * The causes are indistinguishable from here — Termux gives us nothing until the command
     * finishes — so the message lists them rather than guessing at one.
     */
    private fun timedOut(command: String, timeout: Int, hadStdin: Boolean) = ToolResult.error(
        buildString {
            append("No result from Termux after ${timeout}s for: $command. I cannot tell these apart:")
            if (!hadStdin) {
                append("\n- the command may be waiting for typed input that will never come — there is no ")
                append("terminal here. Retry with a non-interactive flag (e.g. -y) or pass the answer in stdin.")
            }
            append("\n- it may simply still be running. I cannot kill another app's process, so it keeps going; ")
            append("check Termux itself, and retry with a larger timeout_seconds if it was just slow.")
            append("\n- `allow-external-apps=true` may be missing from `~/.termux/termux.properties`, in which ")
            append("case Termux silently ignored the request and no command ever ran.")
        }
    )

    private fun cap(text: String): String =
        if (text.length <= maxOutputBytes) text else text.take(maxOutputBytes) + "\n…(output capped)"

    private fun wasTruncatedByTermux(bundle: Bundle, lengthKey: String, received: String): Boolean =
        (bundle.numeric(lengthKey) ?: received.length) > received.length

    /**
     * Termux sends lengths as strings and codes as ints; tolerate either for every numeric field.
     *
     * Reads the raw value rather than probing with `getString`/`getInt`, because a typed getter
     * on the wrong type logs a `ClassCastException` stack trace to logcat on every single call —
     * noise that made a real bug harder to see. `get` is deprecated precisely because it is
     * untyped, which is exactly what is wanted here.
     */
    @Suppress("DEPRECATION")
    private fun Bundle.numeric(key: String): Int? = when (val value = get(key)) {
        is Int -> value
        is Long -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

        private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        internal const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        internal const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        internal const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        internal const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
        internal const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        /**
         * Only correct for the primary Android user. A secondary user or work profile puts the
         * same package at `/data/user/<id>/com.termux`, so this is a last-resort fallback —
         * [termuxFiles] asks the package manager for the real path.
         */
        private const val DEFAULT_TERMUX_FILES = "/data/data/com.termux/files"

        // Result-bundle keys, from termux-shared's TermuxConstants.
        internal const val RESULT_BUNDLE = "result"
        internal const val RESULT_STDOUT = "stdout"
        internal const val RESULT_STDOUT_ORIGINAL_LENGTH = "stdout_original_length"
        internal const val RESULT_STDERR = "stderr"
        internal const val RESULT_STDERR_ORIGINAL_LENGTH = "stderr_original_length"
        internal const val RESULT_EXIT_CODE = "exitCode"
        internal const val RESULT_ERR = "err"
        internal const val RESULT_ERRMSG = "errmsg"

        /**
         * Termux's `Errno` codes are built on `android.app.Activity`'s result constants, so
         * success is **-1** (`RESULT_OK`) and zero means *cancelled* — the opposite of the usual
         * "0 is fine" shell convention. Verified on-device: a successful `uname -a` comes back
         * with `err = -1`. Reading these as ordinary error codes silently inverts every result.
         */
        internal const val ERRNO_SUCCESS = -1 // Activity.RESULT_OK
        private const val ERRNO_CANCELLED = 0 // Activity.RESULT_CANCELED
        private const val ERRNO_MINOR_FAILURES = 1 // Activity.RESULT_FIRST_USER
        private const val ERRNO_FAILED = 2 // Activity.RESULT_FIRST_USER + 1

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
