package com.gotcha.tools

/** What the model is told when the Gotcha Shell companion cannot run something, and what to do next. */
internal object CompanionMessages {

    private const val SETTINGS_PAGE = "Settings ▸ Linux shell"

    /** What is true of `run_shell` only when Gotcha Shell is the shell. See [ShellBackend.toolNotes]. */
    const val TOOL_NOTES =
        "The Linux shell on this phone is Gotcha Shell: Ubuntu, running as root, home is /root. " +
            "Packages install with `apt-get install -y <name>` (run `apt-get update` first on a fresh " +
            "system); pip and npm work once python3-pip or nodejs is installed. Do not use `pkg` or " +
            "any termux-* command, they do not exist here.\n" +
            "It is a separate app with its own storage. It CANNOT see shared storage (/sdcard, " +
            "/storage/emulated/0), Gotcha's working directory or any other app's files, and nothing " +
            "will make those paths appear, so never retry one. To get a file out, write it under " +
            "/root and call pull_from_shell. To put text in, pass it through the stdin param.\n" +
            "\nRealistic limits to factor in:\n" +
            "- A command that reaches its timeout is stopped, not left running. Ceiling: " +
            "timeout_seconds <= 3600. Large installs (ffmpeg, rust, golang, texlive) take minutes, " +
            "and everything runs slower than on a normal Linux machine: raise timeout_seconds and " +
            "warn the user before starting one.\n" +
            "- 4 commands in flight at once, shared with sub-agents.\n" +
            "- Android may kill a long command when the phone is short of memory; the result says so. " +
            "If that interrupted a package install, the user can repair it from $SETTINGS_PAGE.\n" +
            "- Lock contention: an `exit 100` naming `lock-frontend` / `Could not get lock` means " +
            "another apt/dpkg is still running. Wait and retry; never delete the lock files.\n" +
            "- Do not count on a process started with `&` or nohup surviving after the command " +
            "returns. Start a server and test it within one command."

    fun unavailable(e: CompanionUnavailable): ToolResult = when (e.reason) {
        CompanionUnavailable.Reason.NOT_INSTALLED -> notInstalled()
        CompanionUnavailable.Reason.UNTRUSTED -> untrusted()
        CompanionUnavailable.Reason.BIND_REFUSED -> bindFailed(timedOut = false)
        CompanionUnavailable.Reason.BIND_TIMED_OUT -> bindFailed(timedOut = true)
    }

    fun notInstalled() = ToolResult.error(
        "Gotcha Shell is not installed, so there is no Linux user-space to run this in. Tell the user to " +
            "install it from $SETTINGS_PAGE, or use run_command for the plain unprivileged shell inside " +
            "Gotcha's own sandbox."
    )

    fun untrusted() = ToolResult.error(
        "An app with Gotcha Shell's package name is installed, but it is not signed with Gotcha's key, so " +
            "Gotcha will not send it commands or files. Tell the user to uninstall that app and install " +
            "Gotcha Shell from $SETTINGS_PAGE. Do not retry."
    )

    fun bindFailed(timedOut: Boolean) = ToolResult.error(
        "Gotcha Shell is installed but " +
            (if (timedOut) "did not answer in time" else "refused the connection") +
            ". The phone may be stopping it from starting in the background: ask the user to open " +
            "$SETTINGS_PAGE, and on Xiaomi/MIUI phones to allow Autostart for Gotcha Shell. Then try again."
    )

    fun refused(detail: String?) = ToolResult.error(
        "Gotcha Shell refused this Gotcha as a caller" + (detail?.let { " ($it)" } ?: "") +
            ". The two apps must come from the same release. Tell the user to update both from " +
            "$SETTINGS_PAGE. Do not retry."
    )

    fun versionMismatch(found: Int, expected: Int) = ToolResult.error(
        "Gotcha Shell speaks interface version $found but this Gotcha needs version $expected. Tell the user " +
            "to update " + (if (found < expected) "Gotcha Shell" else "Gotcha") +
            " from $SETTINGS_PAGE. Do not retry."
    )

    fun rootfsMissing(installing: Boolean) = ToolResult.error(
        if (installing) {
            "Gotcha Shell is still installing its Linux system. Wait for it to finish (progress is shown in " +
                "$SETTINGS_PAGE), then try again."
        } else {
            "Gotcha Shell is installed but its Linux system is not: this is a first run, or its data was " +
                "cleared. Tell the user to open $SETTINGS_PAGE and tap Install (about a 30 MB download). " +
                "Packages installed before a wipe have to be installed again."
        }
    )

    fun tooManyJobs() = ToolResult.error(
        "Gotcha Shell is already running its maximum number of commands. Wait for one to finish before " +
            "starting another; do not retry in a loop."
    )

    fun died() = ToolResult.error(
        "Gotcha Shell stopped while the command was running, so the command is gone and its output is lost. " +
            "Android may have killed it under memory pressure. If a package install was interrupted, the " +
            "user can repair it from $SETTINGS_PAGE ▸ Repair. Otherwise run the command again."
    )

    fun jobLost() = ToolResult.error(
        "Gotcha Shell restarted and no longer knows this command, so its result is lost. Run it again."
    )

    fun rejected(detail: String?) = ToolResult.error(
        "Gotcha Shell rejected the request" + (detail?.let { ": $it" } ?: ".")
    )

    fun timedOut(timeout: Int, partial: String) = ToolResult.error(
        "The command did not finish within ${timeout}s and was stopped. Unlike a Termux command it is not " +
            "still running. Raise timeout_seconds if it legitimately needs longer." +
            (if (partial.isBlank()) "" else "\nOutput before it was stopped:\n$partial")
    )

    fun killed(partial: String) = ToolResult.error(
        "The command was killed before it finished." +
            (if (partial.isBlank()) "" else "\nOutput before it was killed:\n$partial")
    )

    fun lockHeld(holderPid: String?) = ToolResult.error(
        "Another package operation is still running in the shell" +
            (holderPid?.let { " (process $it)" } ?: "") +
            " and holds the apt/dpkg lock. This is not a network or mirror problem. Wait for it to finish " +
            "and try again; never delete the lock files or kill -9 the process, which corrupts the package " +
            "database. If nothing is running and the lock persists, the user can use $SETTINGS_PAGE ▸ Repair."
    )

    fun emptyCommand() = ToolResult.error(
        "Empty command. Provide a shell command to run (e.g. 'apt-get install -y python3')."
    )

    fun tooLarge(total: Int, limitChars: Int) = ToolResult.error(
        "Command plus stdin is $total characters, over the ${limitChars / 1024}KB limit for a single request. " +
            "You may write the script to a file with write_file and run that file instead."
    )

    fun blocked(command: String) = ToolResult.error(
        "Command blocked by safety policy (irreversible/device-destroying): $command. You may use available " +
            "tools (list_files, read_file, grep) instead for safer operations."
    )

    fun exportMissing(guestPath: String) = ToolResult.error(
        "'$guestPath' is not a readable regular file inside the shell. Check the path with " +
            "run_shell (e.g. 'ls -l'); a leading ~ means /root."
    )

    fun exportTruncated(guestPath: String, expected: Long, received: Long) = ToolResult.error(
        "Copying '$guestPath' out of the shell stopped early: $received of $expected bytes arrived. " +
            "Nothing was written to the destination. Try again."
    )
}
