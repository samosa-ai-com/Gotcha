package com.gotcha.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Unprivileged shell execution (PRD §4.1): app uid only, hard timeout,
 * capped output, and a deny-list for clearly destructive/dangerous invocations.
 */
class TerminalTool(
    private val timeoutSeconds: Long = 15,
    private val maxOutputBytes: Int = 32 * 1024
) {

    private val denyPatterns = listOf(
        Regex("""\brm\s+-[a-z]*r"""),        // recursive delete
        Regex("""\bmkfs\b"""),
        Regex("""\bdd\s+"""),
        Regex("""\breboot\b"""),
        Regex("""\bsu\b"""),                  // privilege escalation attempts
        Regex(""":\(\)\s*\{"""),              // fork bomb
        Regex("""\bsetprop\b""")
    )

    suspend fun runCommand(command: String): ToolResult = withContext(Dispatchers.IO) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return@withContext ToolResult.error("Empty command.")
        denyPatterns.firstOrNull { it.containsMatchIn(trimmed) }?.let {
            return@withContext ToolResult.error(
                "Command blocked by safety policy (matched deny-list): $trimmed"
            )
        }

        try {
            val process = ProcessBuilder("sh", "-c", trimmed)
                .redirectErrorStream(false)
                .start()

            val stdout = process.inputStream.bufferedReader()
            val stderr = process.errorStream.bufferedReader()

            val outBuilder = StringBuilder()
            val errBuilder = StringBuilder()
            val outThread = Thread { drain(stdout::read, outBuilder) }
            val errThread = Thread { drain(stderr::read, errBuilder) }
            outThread.start(); errThread.start()

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext ToolResult.error(
                    "Command timed out after ${timeoutSeconds}s and was killed: $trimmed"
                )
            }
            outThread.join(2000); errThread.join(2000)

            val exit = process.exitValue()
            val message = buildString {
                append("exit code: $exit")
                if (outBuilder.isNotEmpty()) append("\nstdout:\n$outBuilder")
                if (errBuilder.isNotEmpty()) append("\nstderr:\n$errBuilder")
                if (outBuilder.isEmpty() && errBuilder.isEmpty()) append("\n(no output)")
            }
            ToolResult(success = exit == 0, message = message)
        } catch (e: Exception) {
            ToolResult.error("Failed to run command: ${e.message}")
        }
    }

    private fun drain(read: (CharArray, Int, Int) -> Int, into: StringBuilder) {
        val buf = CharArray(4096)
        try {
            while (true) {
                val n = read(buf, 0, buf.size)
                if (n < 0) break
                if (into.length < maxOutputBytes) {
                    into.append(buf, 0, minOf(n, maxOutputBytes - into.length))
                    if (into.length >= maxOutputBytes) into.append("\n…(output capped)")
                }
            }
        } catch (_: Exception) {
            // Stream closed by process death/timeout.
        }
    }
}
