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

    /**
     * Seen on-device as `Not allowed to start service … app is in background`: from Android 12,
     * an app in the background cannot start another app's foreground service, and Termux's
     * RunCommandService is one. Gotcha's assistive ball normally holds a foreground service that
     * exempts us, so this shows up when it is switched off and the chat UI has been backgrounded.
     */
    fun startFailed(cause: Throwable) = ToolResult.error(
        "Could not reach Termux's RUN_COMMAND service: ${cause.message}. If that says Gotcha is in the " +
            "background, Android is refusing the start — from Android 12 a backgrounded app cannot launch " +
            "another app's foreground service. Ask the user to bring Gotcha to the foreground (or switch the " +
            "assistive ball on, which keeps it exempt) and try again. Otherwise Termux may have been disabled " +
            "or force-stopped; use run_command for the app's own sandbox meanwhile. " +
            "For the full background-process rules, call search_skills('termux_background')."
    )

    /**
     * `startService` returning null rather than throwing. Distinct from [startFailed]: nothing
     * refused us, the service simply was not there to start — usually Termux force-stopped, or
     * uninstalled between the availability probe and the call.
     */
    fun serviceDidNotStart() = ToolResult.error(
        "Termux did not start the command: its RUN_COMMAND service could not be reached. Termux has most " +
            "likely been force-stopped (by the user or a battery manager), or was uninstalled just now. Ask " +
            "the user to open Termux once so it is running again, then retry. No command was started, so " +
            "nothing is pending."
    )

    fun tooManyInFlight(limit: Int) = ToolResult.error(
        "$limit Termux commands are already running and none has finished yet. Earlier commands keep running " +
            "even after they time out — a run_termux_command cannot kill them. To stop them, open the " +
            "notifications shade (global_action or press_key 'notifications') and tap the Exit action on the " +
            "Termux notification, which ends every Termux session. If you cannot reach it, ask the user to tap " +
            "Exit on the Termux notification. Otherwise wait for them to finish (use the sleep tool) rather " +
            "than starting another."
    )

    /**
     * A timeout means the command was accepted and is simply taking too long — a *rejected*
     * command comes back promptly with an errno instead. Notably `allow-external-apps` being
     * unset is **not** a cause here, despite what the plugin docs imply: on-device it returns
     * ERRNO_FAILED with an explanatory message within a second. Listing it would send the model
     * off fixing the one thing that is already known not to be wrong.
     */
    fun timedOut(command: String, timeout: Int, hadStdin: Boolean) = ToolResult.error(
        buildString {
            append("No result from Termux after ${timeout}s for: $command. Termux accepted the command, ")
            append("so it is running — it is just not finished. Either:")
            if (!hadStdin) {
                append("\n- it is waiting for typed input that will never come, since there is no terminal ")
                append("here. Retry with a non-interactive flag (e.g. -y) or pass the answer in stdin.")
            }
            append("\n- or it is genuinely slow. Retry with a larger timeout_seconds (the hard ceiling is 600). ")
            append("Note it keeps running either way — a run_termux_command cannot stop it — so check Termux ")
            append("before starting again. To stop a stuck one, open the notifications shade ")
            append("(global_action or press_key 'notifications') and tap the Exit action on the Termux ")
            append("notification, which ends every Termux session. If you cannot reach it, ask the user to tap ")
            append("Exit on the Termux notification.")
            append("\n- or, if the command was `pkg install` or `apt install` and it never moved past the ")
            append("mirror-connect phase, the default Termux mirror is slow or blocked on the user's network. ")
            append("Ask the user to open Termux, run `termux-change-repo` once, pick a closer mirror, and retry.")
            append("\n- or, if this was a long-running process (server, watcher, build) rather than a one-shot ")
            append("command, it should be backgrounded with `nohup ... > log 2>&1 < /dev/null & echo $! > pid; ")
            append("disown` and the call should `exit 0` immediately; `termux-wake-lock` locks Doze off.")
            append("\nFor the full list of pitfalls, call search_skills('termux_operations').")
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
