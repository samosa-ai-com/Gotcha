package com.gotcha.tools

/**
 * What every [ShellBackend] needs to know about apt/dpkg-style package managers: which commands
 * are one, how to stop them asking questions no terminal can answer, and what their lock
 * contention looks like in output.
 */
object PackageManagerCommands {

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

    fun isPackageManager(command: String): Boolean = PKG_LIKE_REGEX.containsMatchIn(command)

    /** Whether [output] carries apt's "could not take the lock" failure text. */
    fun reportsLockHeld(output: String): Boolean = LOCK_SIGNATURE.containsMatchIn(output)

    /** The PID apt names as holding the lock, when it names one. */
    fun lockHolderPid(output: String): String? = HOLDER_PID.find(output)?.groupValues?.get(1)

    /**
     * Prefixes [command] with `DEBIAN_FRONTEND=noninteractive` unless one is already present.
     * apt/dpkg conffile questions ("Configuration file ... Y/I/N/O/D/Z") have no TTY to read
     * the answer from and would block until the timeout; the non-interactive frontend takes the
     * default action (keep the current version), which is what the user's own edit implies.
     */
    fun withNonInteractive(command: String): String =
        if (DEBIAN_FRONTEND_ASSIGNMENT.containsMatchIn(command)) {
            command
        } else {
            "$DEBIAN_FRONTEND=noninteractive $command"
        }
}
