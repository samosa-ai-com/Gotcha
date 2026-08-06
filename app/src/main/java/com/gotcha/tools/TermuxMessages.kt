package com.gotcha.tools

/**
 * What the model is told when a Termux command cannot run, or ran but told us little.
 *
 * Kept apart from [TermuxTool] because these are the product of the work rather than the
 * mechanics of it: every one of them is the model's only clue about a device state it cannot
 * otherwise see, and several describe situations we genuinely cannot distinguish. Composing them
 * next to the intent plumbing made both harder to read.
 */
internal object TermuxMessages {

    fun notInstalled() = ToolResult.error(
        "Termux is not installed, so there is no Linux user-space to run this in. You may ask the user to install " +
            "Termux from F-Droid (the Play Store build is unmaintained), or use run_command for the plain " +
            "unprivileged shell available inside Gotcha's own sandbox."
    )

    /**
     * Termux is installed but has no RunCommandService. In practice this means the Google Play
     * build, which removes the plugin API wholesale to satisfy Play policy — no amount of
     * permission granting or property setting will make it work.
     */
    fun pluginApiMissing(versionName: String?) = ToolResult.error(
        "Termux is installed (version ${versionName ?: "unknown"}) but this build has no RUN_COMMAND " +
            "service, so it cannot run commands for other apps. The Google Play build of Termux removes " +
            "that API. Tell the user to replace it with the F-Droid or GitHub build of Termux if they want " +
            "this; nothing can be granted to fix the Play build. Meanwhile use run_command for the plain " +
            "unprivileged shell. Do not retry."
    )

    fun permissionNeeded() = ToolResult.permissionNeeded(
        ToolResult.TERMUX_ACCESS,
        "Running commands in Termux needs Termux's \"Run commands\" permission, which has not been granted. " +
            "I have asked for it — please allow it, then in Termux add the line `allow-external-apps=true` to " +
            "`~/.termux/termux.properties` and restart Termux, and ask again. (If no permission dialog appeared, " +
            "Termux was installed after Gotcha — reinstall or update Gotcha so Android can grant it.)"
    )

    fun startFailed(cause: Throwable) = ToolResult.error(
        "Could not reach Termux's RUN_COMMAND service: ${cause.message}. Termux may have been disabled or stopped " +
            "by the system. You may open Termux once and ask again, or use run_command for the app's own sandbox."
    )

    fun tooManyInFlight(limit: Int) = ToolResult.error(
        "$limit Termux commands are already running and none has finished yet. Earlier commands keep running " +
            "even after they time out — I cannot kill them. Wait for them to finish (use the sleep tool), or " +
            "check Termux directly, rather than starting another."
    )

    /**
     * The causes are indistinguishable from here — Termux sends nothing until a command finishes
     * — so this lists them rather than picking one and sounding certain.
     */
    fun timedOut(command: String, timeout: Int, hadStdin: Boolean) = ToolResult.error(
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

    fun emptyCommand() = ToolResult.error(
        "Empty command. Provide a shell command to run in Termux (e.g. 'pkg install python -y')."
    )

    fun tooLarge(total: Int, limitChars: Int) = ToolResult.error(
        "Command plus stdin is $total characters, over Termux's ~${limitChars / 1024}KB limit for a single " +
            "request. You may write the script to a file with write_file and run that file instead."
    )

    fun blocked(command: String) = ToolResult.error(
        "Command blocked by safety policy (irreversible/device-destroying): $command. You may use available " +
            "tools (list_files, read_file, grep) instead for safer operations."
    )

    /** Termux's errno is not a plain "0 means fine" code, so spell it out. */
    fun errnoLabel(err: Int): String = when (err) {
        TermuxTool.ERRNO_CANCELLED -> "cancelled"
        TermuxTool.ERRNO_MINOR_FAILURES -> "completed with minor failures"
        TermuxTool.ERRNO_FAILED -> "failed"
        else -> "error code $err"
    }
}
