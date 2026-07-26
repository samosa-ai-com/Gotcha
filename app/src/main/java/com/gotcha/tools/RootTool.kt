package com.gotcha.tools

import java.util.concurrent.TimeUnit

/**
 * Tier 4 — privileged execution via a root shell (`su`).
 *
 * Everything here runs `su -c <command>`, so it only works on a rooted device (Magisk et al.).
 * When no `su` binary is present, [ProcessBuilder] throws and the tool returns a plain error
 * explaining the device is not rooted — there is no Settings screen to hand the user to, so
 * (unlike the Tier 2/3 special-access tools) it does **not** emit a permission marker.
 *
 * A root shell subsumes the remaining Tier 4 wishes that a normal app can't reach:
 * `WRITE_SECURE_SETTINGS` (`settings put secure …`, exposed as [writeSecureSetting]), silent
 * install (`pm install`), reading other apps' data, etc. — all via [runRootCommand].
 *
 * These commands are marked sensitive (they pass through the confirmation dialog). A small
 * deny-list still blocks the few irreversible, device-bricking operations, mirroring the
 * project's stance of never exposing outright device destruction (cf. Device-Admin wipe).
 */
class RootTool(
    private val timeoutSeconds: Long = 20,
    private val maxOutputBytes: Int = 32 * 1024
) {

    /** Irreversible, device-destroying operations we refuse even with root. */
    private val denyPatterns = listOf(
        Regex("""\bmkfs\b"""), // reformat a filesystem
        Regex("""\bdd\b[^\n]*\bof=/dev/"""), // raw write to a block device
        Regex("""\brm\s+-[a-z]*r[a-z]*f?\s+/(\s|$)"""), // rm -rf /  (wipe the root tree)
        Regex("""\bfastboot\b"""),
        Regex("""\brecovery\b[^\n]*--wipe""")
    )

    /** Probe whether a working root shell is available. Read-only. */
    fun checkRoot(): ToolResult {
        val result = exec("id")
        return when {
            result == null ->
                ToolResult.ok("Root is NOT available — this device has no `su` binary (it isn't rooted).")
            result.exit == 0 && result.output.contains("uid=0") ->
                ToolResult.ok("Root IS available — `su` granted a root shell (${result.output.trim().take(120)}).")
            else ->
                ToolResult.ok(
                    "Root appears unavailable — `su` did not return a root uid " +
                        "(exit ${result.exit}: ${result.output.trim().take(120)})."
                )
        }
    }

    /** Run an arbitrary command as root and return stdout/stderr/exit code. */
    fun runRootCommand(command: String): ToolResult {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return ToolResult.error("Empty command. Provide the shell command to run as root.")
        denyPatterns.firstOrNull { it.containsMatchIn(trimmed) }?.let {
            return ToolResult.error(
                "Command blocked by safety policy (irreversible/device-destroying): $trimmed. You may use available tools (list_files," +
                    "read_file, grep) instead for safer operations."
            )
        }
        val result = exec(trimmed) ?: return notRooted()
        val message = buildString {
            append("exit code: ${result.exit}")
            if (result.output.isNotEmpty()) append("\noutput:\n${result.output}") else append("\n(no output)")
        }
        return ToolResult(success = result.exit == 0, message = message)
    }

    /**
     * Write a secure/system/global setting via `settings put` — the `WRITE_SECURE_SETTINGS`
     * capability an ordinary app can't hold. Needs root.
     */
    fun writeSecureSetting(namespace: String, key: String, value: String): ToolResult {
        val ns = namespace.trim().lowercase()
        if (ns !in setOf("system", "secure", "global")) {
            return ToolResult.error("namespace must be one of: system, secure, global (got '$namespace').")
        }
        if (key.isBlank()) return ToolResult.error("Provide the setting key to write.")
        // Guard against argument injection through the key/value.
        if (unsafeArg(key) || unsafeArg(value)) {
            return ToolResult.error("The key/value contain shell metacharacters that aren't allowed here.")
        }
        val result = exec("settings put $ns $key $value") ?: return notRooted()
        return if (result.exit == 0) {
            ToolResult.ok("Set $ns setting '$key' to '$value'.")
        } else {
            ToolResult.error("Failed to set $ns/$key (exit ${result.exit}): ${result.output.trim()}")
        }
    }

    private fun unsafeArg(s: String): Boolean =
        s.any { it in "\n\r;&|`$()<>\"'\\ " }

    private fun notRooted() = ToolResult.error(
        "This action needs root, but no `su` binary is available — the device isn't rooted, so I can't do it. You may try non-root " +
            "alternatives such " +
            "as list_files, read_file, or grep for filesystem operations."
    )

    private data class ExecResult(val exit: Int, val output: String)

    /** Execute [command] via `su -c`; returns null when the device has no `su` binary. */
    private fun exec(command: String): ExecResult? {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            val reader = process.inputStream.bufferedReader()
            val builder = StringBuilder()
            val pump = Thread {
                val buf = CharArray(4096)
                try {
                    while (true) {
                        val n = reader.read(buf, 0, buf.size)
                        if (n < 0) break
                        if (builder.length < maxOutputBytes) {
                            builder.append(buf, 0, minOf(n, maxOutputBytes - builder.length))
                            if (builder.length >= maxOutputBytes) builder.append("\n…(output capped)")
                        }
                    }
                } catch (_: Exception) {
                    // Stream closed on process death/timeout.
                }
            }.apply { start() }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                pump.join(2000)
                return ExecResult(-1, builder.toString().ifEmpty { "(timed out after ${timeoutSeconds}s)" })
            }
            pump.join(2000)
            ExecResult(process.exitValue(), builder.toString())
        } catch (_: Exception) {
            // No `su` on PATH → IOException. Treat as "not rooted".
            null
        }
    }
}
