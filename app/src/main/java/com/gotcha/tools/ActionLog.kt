package com.gotcha.tools

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only local audit log of every tool execution (PRD §11.2 #3):
 * timestamp, tool name, args, and result. Stored in the app sandbox only.
 */
class ActionLog(context: Context) {

    private val file: File = File(context.filesDir, "action_log.txt")
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun record(tool: String, args: String, result: ToolResult) {
        val status = if (result.success) "OK" else "FAIL"
        val line = "${formatter.format(
            Date()
        )}\t$tool\t$args\t$status\t${result.message.take(500).replace('\n', ' ')}\n"
        try {
            file.appendText(line)
            trimIfNeeded()
        } catch (_: Exception) {
            // Logging must never break tool execution.
        }
    }

    private fun trimIfNeeded(maxBytes: Long = 512 * 1024) {
        if (file.length() > maxBytes) {
            val lines = file.readLines()
            file.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
        }
    }
}
