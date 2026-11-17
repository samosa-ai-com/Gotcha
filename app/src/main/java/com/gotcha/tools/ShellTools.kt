package com.gotcha.tools

import com.gotcha.llm.ToolDefinition

/**
 * The names and model-facing text of the tools that run in the Linux shell.
 *
 * The tools used to be named after Termux. They now serve whichever [ShellBackend] is active,
 * so the name says what they do and the part of the description that differs per backend comes
 * from [ShellBackend.toolNotes].
 */
object ShellTools {
    const val RUN = "run_shell"
    const val PULL = "pull_from_shell"

    /** The guest path argument of [PULL], and the name it had when the tool was Termux-only. */
    const val PULL_PATH = "path"
    const val PULL_PATH_LEGACY = "termux_path"

    /**
     * Names the tools had before the rename. Saved chats, scheduled tasks and imported skills
     * still contain them, and a model replaying such text calls the old name.
     */
    private val aliases = mapOf(
        "run_termux_command" to RUN,
        "pull_from_termux" to PULL
    )

    /** The current name for [name], which is [name] itself unless it is an old alias. */
    fun canonical(name: String): String = aliases[name] ?: name

    /** What [RUN] does on every backend. */
    const val RUN_DESCRIPTION =
        "Run a shell command in the phone's Linux shell, a real Linux user-space with a package " +
            "manager, python3, git, ssh and compilers. Use this for anything that needs a package " +
            "installed, a Linux tool Android does not ship, or a non-trivial script; it runs " +
            "headlessly, with no terminal opening in front of the user. This is NOT Android's own " +
            "shell: use run_command for Android commands (pm, am, settings, getprop) and for files " +
            "in Gotcha's working directory. Only offered when a Linux shell is installed; the <env> " +
            "block says which one.\n" +
            "Each call is a SEPARATE shell: `cd`, `export`, and activated virtualenvs do NOT carry " +
            "over to your next call, which starts again in the shell's home. Chain dependent steps " +
            "in one command with '&&' (e.g. 'cd proj && ./build.sh') rather than splitting them. " +
            "For scripts use a single heredoc/stdin payload (e.g. `python3 - << 'PY'\\n...\\nPY` or " +
            "`sh -s << 'EOF'\\n...\\nEOF`) via the stdin tool param.\n" +
            "No interactive prompts: there is no terminal here, so any command that waits for typed " +
            "input (an install without -y, ssh password, read, mysql>) blocks until the timeout. " +
            "Always pass -y / --yes / -q where the tool supports them. Output is capped at 32KB; for " +
            "larger output, redirect to a file and read the part you need."

    /** What [PULL] does on every backend. */
    const val PULL_DESCRIPTION =
        "Copy a file that currently lives inside the Linux shell (the one run_shell uses) out to " +
            "a path Gotcha and the user can reach, e.g. '/storage/emulated/0/Download/name.mp4'. " +
            "This is how a file produced there (yt-dlp downloads, script output, server files) gets " +
            "into the user's Downloads/Gotcha folder: run the producing command first with " +
            "run_shell, then pull the result. Needs a Linux shell installed."

    /**
     * [tools] with the active backend's [notes] appended to the description of [RUN], so the
     * model reads limits that are true of the shell it is about to use.
     */
    fun describe(tools: List<ToolDefinition>, notes: String?): List<ToolDefinition> {
        if (notes.isNullOrBlank()) return tools
        return tools.map { tool ->
            if (tool.function.name != RUN) {
                tool
            } else {
                tool.copy(function = tool.function.copy(description = "$RUN_DESCRIPTION\n\n$notes"))
            }
        }
    }
}
