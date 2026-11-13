package com.gotcha.tools

import android.content.Context
import java.io.File

/** What a [ShellBackend] reports about itself, for capability gating and the `<env>` block. */
interface ShellStatus {
    /** The backend can run a command, give or take a grant the tool itself can raise. */
    val usable: Boolean

    /** The line describing this backend in the model's `<env>` block, indented to match it. */
    val envLine: String
}

/**
 * A full Linux user-space that lives outside Gotcha's own sandbox, under another Android uid.
 *
 * The seam between the tools that need such a shell (`run_termux_command`, `pull_from_termux`,
 * `media_convert`) and whatever provides it. Termux is the only provider today; the interface
 * holds just what those tools use, so a second provider adds its own operations when it arrives
 * rather than leaving unimplemented stubs here.
 */
interface ShellBackend {
    /** Stable identifier, e.g. `"termux"`. */
    val id: String

    /** The guest's `$HOME`: the default working directory, and what a leading `~` expands to. */
    val home: String

    fun status(): ShellStatus

    /** Runs [command] through the guest's `sh -c` and reports exit code, stdout and stderr. */
    suspend fun runCommand(
        command: String,
        workingDir: String? = null,
        timeoutSeconds: Int? = null,
        stdin: String? = null
    ): ToolResult

    /**
     * The path the guest must use to reach [hostFile], or null when the guest cannot see it at
     * all and the file would have to be copied across instead.
     */
    fun guestPathFor(hostFile: File): String?

    /** Copies the guest file at [guestPath] out to [destination] on Gotcha's side. */
    suspend fun exportFile(guestPath: String, destination: File): ToolResult
}

/**
 * Chooses the [ShellBackend] the tools talk to.
 *
 * Gotcha Shell, Gotcha's own companion app, is preferred: it needs no setup beyond installing
 * it, and a command that times out there is stopped rather than left running. Termux remains the
 * fallback, so a phone that has Termux set up keeps working until the companion is ready.
 */
object ShellBackends {

    fun active(context: Context): ShellBackend {
        val companion = CompanionShellBackend(context)
        val termux = TermuxShellBackend(context)
        return if (prefersCompanion(companion.status(), termux.status())) companion else termux
    }

    /**
     * The companion wins once it is known to have its Linux system installed. Before that it
     * wins only where Termux could not run anything either, so a working Termux is never traded
     * for a companion that would answer "install me first".
     */
    internal fun prefersCompanion(
        companion: CompanionShellBackend.CompanionStatus,
        termux: ShellStatus
    ): Boolean = companion.usable && (companion.rootfsReady == true || !termux.usable)

    /**
     * The `<env>` lines describing the shell: the active backend's, plus the companion's when it
     * is installed but was passed over, so the model can say what would make it usable.
     */
    fun envLines(context: Context): List<String> {
        val active = active(context)
        val lines = mutableListOf(active.status().envLine)
        if (active.id != CompanionShellBackend.ID) {
            val companion = CompanionShellBackend(context).status()
            if (companion.identity.installed) lines += companion.envLine
        }
        return lines
    }

    /**
     * Asks an installed companion whether its Linux system is ready, when Gotcha has never
     * found out. Without this a companion installed with its system already in place would be
     * passed over for Termux until the user happened to open its settings page.
     */
    suspend fun refreshIfUnknown(context: Context) {
        val status = CompanionShellBackend(context).status()
        if (status.usable && status.rootfsReady == null) CompanionShellManager(context).info()
    }
}
