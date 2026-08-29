package com.gotcha.tools

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

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
 * Two things must be true before that works:
 *  1. `com.termux.permission.RUN_COMMAND` is granted. It is a **`dangerous`** permission that
 *     Termux itself declares, so listing it in the manifest is not enough — it needs a runtime
 *     grant, raised through [ToolResult.TERMUX_ACCESS]. A caveat worth knowing: a custom
 *     dangerous permission whose defining app was installed *after* Gotcha may not be
 *     grantable until Gotcha is updated or reinstalled.
 *  2. `allow-external-apps=true` is set in Termux's `~/.termux/termux.properties`. That file is
 *     in Termux's private storage so we cannot read it ahead of time, but we do not need to:
 *     verified on-device against Termux 0.118.3, running with it unset comes back promptly as
 *     `err = ERRNO_FAILED` with an `errmsg` naming the property, which [formatResult] passes
 *     through verbatim. It is a clear failure, not the silent hang the API docs imply.
 *
 * Availability is gated on the Termux package being *installed* ([Capability.TERMUX]), not on
 * the grant — gating on the grant would hide the only tool that can raise the prompt. The
 * unprivileged [TerminalTool] (`run_command`) stays the tool for Gotcha's own sandbox, which is
 * invisible from Termux's uid.
 */
class TermuxTool(
    private val context: Context,
    private val defaultTimeoutSeconds: Int = 120,
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

    /** How far Termux's `allow-external-apps` setup has been verified. */
    enum class TermuxConfigProbe {
        CONFIGURED,
        NOT_CONFIGURED,
        UNKNOWN
    }

    /** The guided-setup view of Termux: everything the Settings page needs to show. */
    data class TermuxSetupState(
        val installed: Boolean,
        val pluginApiAvailable: Boolean,
        val permissionGranted: Boolean,
        val versionName: String?,
        val externalAppsEnabled: TermuxConfigProbe = TermuxConfigProbe.UNKNOWN
    ) {
        /** Every step of the setup is done. */
        val ready: Boolean
            get() = installed && pluginApiAvailable && permissionGranted &&
                externalAppsEnabled == TermuxConfigProbe.CONFIGURED

        companion object {
            fun from(status: TermuxStatus): TermuxSetupState = TermuxSetupState(
                installed = status.installed,
                pluginApiAvailable = status.pluginApiAvailable,
                permissionGranted = status.permissionGranted,
                versionName = status.versionName
            )
        }
    }

    /**
     * Probes whether Termux accepts external commands — i.e. `allow-external-apps=true`.
     *
     * That property lives in Termux's private storage, so it cannot be read from here. It can
     * only be discovered by running a command: with the property unset, Termux answers
     * immediately with an errno whose message names the property; with it set, the command
     * succeeds. Anything that prevents probing at all (Termux absent, Play build, permission
     * not granted, Termux stopped, timeout) is [TermuxConfigProbe.UNKNOWN] rather than a guess.
     */
    suspend fun probeExternalApps(): TermuxConfigProbe =
        classifyProbe(runCommand(PROBE_COMMAND, timeoutSeconds = PROBE_TIMEOUT_SECONDS))

    /**
     * The decision half of [probeExternalApps], split out so it is testable without Termux.
     *
     * The `needsPermission` guard matters: [TermuxMessages.permissionNeeded] also mentions
     * `allow-external-apps`, but it is a guard path — we never got a real answer — so it must
     * read as [TermuxConfigProbe.UNKNOWN], not [TermuxConfigProbe.NOT_CONFIGURED].
     */
    internal fun classifyProbe(result: ToolResult): TermuxConfigProbe = when {
        result.success -> TermuxConfigProbe.CONFIGURED
        result.needsPermission == null && result.message.contains("allow-external-apps") ->
            TermuxConfigProbe.NOT_CONFIGURED
        else -> TermuxConfigProbe.UNKNOWN
    }

    /**
     * Irreversible, device-destroying operations we refuse even here. Deliberately
     * [RootTool]'s narrow list rather than [TerminalTool]'s: `rm -r` inside Termux's own
     * `$HOME` is ordinary housekeeping, and `pkg` needs it.
     *
     * **This is a guardrail, not a security boundary.** It matches text, so anything indirect
     * walks straight through it — a variable, an escape, a here-doc, `base64 -d | sh`. It exists
     * to stop a plausible-looking mistake from wiping a device, not to contain an attacker who
     * already controls what the model asks for. The real boundary is the user granting Termux
     * access at all. Do not add checks here that only make sense against a determined evader:
     * they would suggest a guarantee this cannot make.
     */
    private val denyPatterns = listOf(
        Regex("""\bmkfs\b"""), // reformat a filesystem
        Regex("""\bdd\b[^\n]*\bof=/dev/"""), // raw write to a block device
        // `rm` whose target is the root tree itself: `rm -rf /`, `rm -rf /*`, `rm -r -f /`.
        Regex("""\brm\s+(?:-\S+\s+)*/\s*\*?\s*$"""),
        // Defeats coreutils' own refusal to remove `/`, so its presence is intent enough.
        Regex("""--no-preserve-root"""),
        Regex("""\bfastboot\b"""),
        Regex("""\brecovery\b[^\n]*--wipe"""),
        // Deleting a live package manager's lock files or DB while a dpkg holds the fd does not
        // release the lock — it lets a second apt write /var/lib/dpkg/status concurrently and
        // corrupt the package database. The only remedies are to wait or to tap Exit on the
        // Termux notification, so the mistake is refused rather than explained after the fact.
        Regex("""\brm\b[^\n]*(?:dpkg/lock(?:-frontend)?|dpkg/status|apt/lists/lock|apt/archives/lock)"""),
        // SIGKILL on a package manager or a bare PID. The model cannot legitimately kill a
        // process from another call anyway (see termux_background), and `kill -9` on a live
        // dpkg mid-transaction leaves a half-configured package that needs dpkg --configure -a.
        Regex("""\bkill\s+-9\b[^\n]*(?:\b\d+\b|\bdpkg\b|\bapt(?:-get)?\b|\bpkg\b)"""),
        Regex("""\b(?:pkill|killall)\b[^\n]*(?:\bdpkg\b|\bapt(?:-get)?\b|\bpkg\b)""")
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
        if (!status.installed) return TermuxMessages.notInstalled()
        if (!status.pluginApiAvailable) return TermuxMessages.pluginApiMissing(status.versionName)
        if (!status.permissionGranted) return TermuxMessages.permissionNeeded()

        val adaptiveDefault = if (timeoutSeconds == null && PKG_LIKE_REGEX.containsMatchIn(trimmed)) {
            300
        } else {
            defaultTimeoutSeconds
        }
        val timeout = (timeoutSeconds ?: adaptiveDefault).coerceIn(1, MAX_TIMEOUT_SECONDS)
        // A timed-out command is not cancelled — it keeps running under Termux's uid, out of our
        // reach. Without a ceiling, a model that retries a slow `pkg` a few times would leave a
        // growing pile of live processes behind it, each still holding Termux's package lock.
        if (!inFlight.tryAcquire()) return TermuxMessages.tooManyInFlight(MAX_CONCURRENT_COMMANDS)
        val requestCode = nextRequestCode()
        val deferred = CompletableDeferred<Bundle>()
        pendingResults[requestCode] = deferred
        try {
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                TermuxResultReceiver.resultIntent(context, requestCode),
                pendingIntentFlags()
            )
            val started = runCatching {
                context.startService(commandIntent(commandToRun(trimmed), workingDir, stdin, pendingIntent))
            }
            started.exceptionOrNull()?.let { return TermuxMessages.startFailed(it) }
            // startService reports "no such service" by returning null, not by throwing. Android
            // also excludes force-stopped packages from intent resolution, so a Termux the user
            // (or an OEM battery manager) has stopped lands here. Waiting on the deferred would
            // burn the whole timeout and then claim the command was accepted and still running.
            if (started.getOrNull() == null) return TermuxMessages.serviceDidNotStart()

            val bundle = withTimeoutOrNull(timeout * 1000L) { deferred.await() }
                ?: return TermuxMessages.timedOut(trimmed, timeout, hadStdin = stdin != null)
            return formatResult(bundle, trimmed)
        } finally {
            pendingResults.remove(requestCode)
            inFlight.release()
        }
    }

    /** Turn Termux's result bundle into a [ToolResult]. Split out so it is testable without Termux. */
    internal fun formatResult(bundle: Bundle, command: String = ""): ToolResult {
        val err = bundle.numeric(RESULT_ERR) ?: ERRNO_SUCCESS
        val errmsg = bundle.getString(RESULT_ERRMSG)?.trim().orEmpty()
        if (err != ERRNO_SUCCESS) {
            return ToolResult.error(
                "Termux refused or could not run the command (${TermuxMessages.errnoLabel(err)})" +
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
        // apt/dpkg lock contention ("Could not get lock ... lock-frontend held by process N") needs
        // a different recovery than a plain failed command: wait, or tap Exit on the Termux
        // notification — never delete the lock files or kill the holder. Recognised here so the
        // model is told what actually fixes it instead of reading a raw apt error.
        //
        // Deliberately NOT gated on `exit != 0`: a pipeline like `apt-get ... | tail` or
        // `apt list --upgradable` masks apt's failure behind a successful tail/echo, so the lock
        // error appears in the output while the reported exit code is 0. It IS gated on the command
        // being package-manager-shaped ([PKG_LIKE_REGEX]): a successful diagnostic that merely
        // *contains* the phrase (`grep "Could not get lock" build.log`) is a successful command, not
        // a held lock. An empty [command] — the pure test seam — keeps the defensive scan.
        if ((command.isEmpty() || PKG_LIKE_REGEX.containsMatchIn(command)) &&
            LOCK_SIGNATURE.containsMatchIn(message)
        ) {
            return TermuxMessages.lockHeld(HOLDER_PID.find(message)?.groupValues?.get(1))
        }
        return ToolResult(success = exit == 0, message = message)
    }

    /** Cheap checks that need neither Termux nor a permission, so they report the real problem first. */
    private fun validate(trimmed: String, stdin: String? = null): ToolResult? {
        if (trimmed.isEmpty()) return TermuxMessages.emptyCommand()
        // Command and stdin ride in the same binder transaction, so they share the budget.
        val total = trimmed.length + (stdin?.length ?: 0)
        if (total > MAX_COMMAND_CHARS) return TermuxMessages.tooLarge(total, MAX_COMMAND_CHARS)
        // Collapse whitespace first, so `rm  -rf   /` is not a way around the patterns.
        val normalised = trimmed.replace(Regex("""\s+"""), " ")
        // Check each shell segment as well as the whole line. The root-tree `rm` pattern is
        // end-anchored — it has to be, or `rm -rf /home/x` would match — and the schema tells
        // the model to chain steps with `&&`, so `rm -rf / && echo done` is the single most
        // likely shape for this mistake to arrive in. Whole-line matching alone would miss it.
        val candidates = normalised.split(SEGMENT_SEPARATORS) + normalised
        if (candidates.any { segment -> denyPatterns.any { it.containsMatchIn(segment.trim()) } }) {
            return TermuxMessages.blocked(trimmed)
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

        /**
         * Play-store-free source for Termux. The Play build is unmaintained and strips the
         * RUN_COMMAND plugin API wholesale, so it can never be made to work with Gotcha.
         */
        const val TERMUX_FDROID_URL = "https://f-droid.org/en/packages/com.termux/"

        /** An `allow-external-apps` failure returns within a second; this is a generous ceiling. */
        private const val PROBE_TIMEOUT_SECONDS = 5

        /** Trivial command used to probe whether Termux accepts external commands. */
        private const val PROBE_COMMAND = "echo gotcha-probe"

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
        internal const val ERRNO_CANCELLED = 0 // Activity.RESULT_CANCELED
        internal const val ERRNO_MINOR_FAILURES = 1 // Activity.RESULT_FIRST_USER
        internal const val ERRNO_FAILED = 2 // Activity.RESULT_FIRST_USER + 1

        /** Package-manager commands that are inherently slow and benefit from a larger default timeout. */
        private val PKG_LIKE_REGEX = Regex("""\b(pkg|apt|apt-get|dpkg|pip3?|npm|cargo|proot-distro)\b""")

        /**
         * The apt/dpkg lock-contention signature: the *failure* phrases apt prints when it cannot
         * take the lock. Kept to failure text on purpose — bare "lock-frontend" or "cache lock"
         * would match `ls $PREFIX/var/lib/dpkg/lock*` output and mislead the model. Matched against
         * the whole result (stdout+stderr), not just the exit code, because pipelines such as
         * `apt-get ... | tail` report exit 0 while the lock failure is sitting in the output.
         */
        private val LOCK_SIGNATURE = Regex(
            """(?:Could not get lock|Unable to acquire the dpkg frontend lock|dpkg frontend lock was locked by another process)""",
            RegexOption.IGNORE_CASE
        )

        /** The holder named by apt's wait message: `It is held by process 24247 (dpkg)`. */
        private val HOLDER_PID = Regex("""held by process (\d+)""")

        /** Termux's wake-lock helpers; required for any task longer than ~30s that Doze would throttle. */
        private const val WAKE_LOCK = "termux-wake-lock"
        private const val WAKE_UNLOCK = "termux-wake-unlock"

        /**
         * An actual `DEBIAN_FRONTEND=<value>` assignment. A bare substring would match a package
         * name or a path containing the literal (e.g. `apt-get install lib-DEBIAN_FRONTEND-1`),
         * silently skipping the non-interactive injection and letting a conffile prompt hang.
         */
        private val DEBIAN_FRONTEND_ASSIGNMENT = Regex("""(^|\s)DEBIAN_FRONTEND=\S+""")

        /** The Debian frontend env var that tells dpkg/apt to take default actions instead of asking
         * conffile questions. It is safe for every package-manager command and skipped when the user
         * already set it explicitly.
         */
        private const val DEBIAN_FRONTEND = "DEBIAN_FRONTEND"

        /** Shell separators the deny-list splits on before matching each part. */
        private val SEGMENT_SEPARATORS = Regex("""[;&|\n]+""")

        /** Termux caps a single command near 128KB; leave headroom for the rest of the intent. */
        private const val MAX_COMMAND_CHARS = 100 * 1024

        /** `pkg install` is slow, so this is far above [TerminalTool]'s 120s ceiling. */
        private const val MAX_TIMEOUT_SECONDS = 600

        /**
         * Ceiling on commands running at once. Deliberately process-wide rather than per
         * instance: sub-agents build their own [ToolExecutor], and Termux is a single shared
         * resource regardless of who asked.
         */
        private const val MAX_CONCURRENT_COMMANDS = 4

        /** Test seam: lets the cap be saturated without hardcoding the number twice. */
        internal const val MAX_CONCURRENT_FOR_TEST = MAX_CONCURRENT_COMMANDS

        /**
         * The command actually sent to Termux: the user's text, made non-interactive for
         * package-manager operations (dpkg/apt conffile prompts would otherwise block on a TTY that
         * does not exist) and wrapped in a wake-lock so Doze cannot throttle the download. The
         * exact shape of the ffmpeg failure this guard targets: a `pkg upgrade` silently re-asking
         * `openssl.cnf` and a 30-second install turning into a 10-minute hang.
         */
        internal fun commandToRun(trimmed: String): String {
            if (!PKG_LIKE_REGEX.containsMatchIn(trimmed)) return trimmed
            return withWakeLock(withNonInteractive(trimmed))
        }

        /**
         * Prefixes [command] with `DEBIAN_FRONTEND=noninteractive` unless one is already present.
         * apt/dpkg conffile questions ("Configuration file ... Y/I/N/O/D/Z") have no TTY to read
         * the answer from and would block until the timeout; the non-interactive frontend takes the
         * default action (keep the current version), which is what the user's own edit implies.
         */
        internal fun withNonInteractive(command: String): String =
            if (DEBIAN_FRONTEND_ASSIGNMENT.containsMatchIn(command)) {
                command
            } else {
                "$DEBIAN_FRONTEND=noninteractive $command"
            }

        /**
         * Wraps [command] in `termux-wake-lock`/`termux-wake-unlock`, preserving its exit code (a naive
         * `cmd; termux-wake-unlock` would swallow failures) and skipping the wrap entirely when the
         * lock tools are not installed. Split out so the shape can be asserted without Termux.
         *
         * Best-effort for a single long command: Termux's wake-lock is one global marker, so two
         * concurrent pkg calls each lock and the first to finish unlocks while the other still runs.
         * The skill notes this; it is not a reason to leave slow installs unwrapped.
         */
        internal fun withWakeLock(command: String): String {
            if (WAKE_LOCK in command) return command
            // The EXIT trap (rather than a trailing `termux-wake-unlock`) also releases the lock when
            // the inner command itself exits early — e.g. the skill's persistence pattern ends with a
            // bare `exit 0`, which would skip a trailing sequence and leak the wake-lock.
            val wrapped = "$WAKE_LOCK; trap '$WAKE_UNLOCK' EXIT; { $command; rc=\$?; exit \$rc; }"
            return "if command -v $WAKE_LOCK >/dev/null 2>&1; then $wrapped; else $command; fi"
        }

        /**
         * Test seams for the concurrency cap. Occupying slots directly beats racing real
         * commands: `runTest` runs on virtual time, so a command that "blocks" for its timeout
         * actually returns instantly and hands its slot straight back.
         */
        internal fun acquireSlotForTest(): Boolean = inFlight.tryAcquire()

        internal fun releaseSlotForTest() = inFlight.release()

        private val inFlight = Semaphore(MAX_CONCURRENT_COMMANDS)

        /**
         * Seeded per process, not from 1.
         *
         * A PendingIntent outlives us: if Gotcha dies while Termux still holds an unfired one,
         * the next process starting from 1 would ask for the same request code, and
         * `getBroadcast` matches on request code and intent — extras are not part of the
         * identity — so it would hand back the *stale* PendingIntent. Termux would then fire the
         * old command's result into the new command's deferred, and the agent would act on
         * output belonging to a command it never ran. A random start makes the two code spaces
         * disjoint in practice. Masked to stay non-negative, which the receiver relies on.
         */
        private val nextRequestCode = AtomicInteger(Random.nextInt(1, 1 shl 20))

        private fun nextRequestCode(): Int = nextRequestCode.getAndIncrement() and Int.MAX_VALUE

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
