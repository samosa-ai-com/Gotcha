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

/** Chooses the [ShellBackend] the tools talk to. */
object ShellBackends {
    fun active(context: Context): ShellBackend = TermuxTool(context)
}
