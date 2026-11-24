package com.gotcha.tools

/**
 * The checks every [ShellBackend] applies to a command before sending it anywhere: they need
 * neither the backend nor a permission, so they report the real problem first.
 */
object ShellCommandGuard {

    sealed interface Rejection {
        data object Empty : Rejection
        data class TooLarge(val total: Int, val limitChars: Int) : Rejection
        data object Blocked : Rejection
    }

    /**
     * Irreversible, device-destroying operations we refuse even here. Deliberately
     * [RootTool]'s narrow list rather than [TerminalTool]'s: `rm -r` inside the guest's own
     * `$HOME` is ordinary housekeeping, and `pkg` needs it.
     *
     * **This is a guardrail, not a security boundary.** It matches text, so anything indirect
     * walks straight through it — a variable, an escape, a here-doc, `base64 -d | sh`. It exists
     * to stop a plausible-looking mistake from wiping a device, not to contain an attacker who
     * already controls what the model asks for. The real boundary is the user granting shell
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
        // corrupt the package database. The only remedies are to wait or to end the guest's
        // sessions, so the mistake is refused rather than explained after the fact.
        Regex("""\brm\b[^\n]*(?:dpkg/lock(?:-frontend)?|dpkg/status|apt/lists/lock|apt/archives/lock)"""),
        // SIGKILL on a package manager or a bare PID. The model cannot legitimately kill a
        // process from another call anyway (see termux_background), and `kill -9` on a live
        // dpkg mid-transaction leaves a half-configured package that needs dpkg --configure -a.
        Regex("""\bkill\s+-9\b[^\n]*(?:\b\d+\b|\bdpkg\b|\bapt(?:-get)?\b|\bpkg\b)"""),
        Regex("""\b(?:pkill|killall)\b[^\n]*(?:\bdpkg\b|\bapt(?:-get)?\b|\bpkg\b)""")
    )

    /** Shell separators the deny-list splits on before matching each part. */
    private val SEGMENT_SEPARATORS = Regex("""[;&|\n]+""")

    /**
     * Why [trimmed] must not be sent, or null when it may be.
     *
     * [maxChars] is the backend's transport limit; command and stdin travel together, so they
     * share the budget.
     */
    fun check(trimmed: String, stdin: String?, maxChars: Int): Rejection? {
        if (trimmed.isEmpty()) return Rejection.Empty
        val total = trimmed.length + (stdin?.length ?: 0)
        if (total > maxChars) return Rejection.TooLarge(total, maxChars)
        // Collapse whitespace first, so `rm  -rf   /` is not a way around the patterns.
        val normalised = trimmed.replace(Regex("""\s+"""), " ")
        // Check each shell segment as well as the whole line. The root-tree `rm` pattern is
        // end-anchored — it has to be, or `rm -rf /home/x` would match — and the schema tells
        // the model to chain steps with `&&`, so `rm -rf / && echo done` is the single most
        // likely shape for this mistake to arrive in. Whole-line matching alone would miss it.
        val candidates = normalised.split(SEGMENT_SEPARATORS) + normalised
        if (candidates.any { segment -> denyPatterns.any { it.containsMatchIn(segment.trim()) } }) {
            return Rejection.Blocked
        }
        return null
    }
}
