package com.gotcha.data

import com.gotcha.llm.ChatMessage
import com.gotcha.llm.FunctionCall
import com.gotcha.llm.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * A byte-order mark some editors put at the start of a saved file. Built from its
 * code point: formatters rewrite a "\uFEFF" escape into the invisible character.
 */
internal val BYTE_ORDER_MARK = Char(0xFEFF).toString()

/** Starts each document section in a user message; see [documentPromptText]. */
internal const val ATTACHED_FILE_MARKER = "\n[Attached file:"

/**
 * The user's prompt portion of a document message's text part, or null when
 * [content] is not a document message. Document messages put
 * `[Attached file: …]` on its own line, so everything before that marker is
 * the user's own words.
 */
internal fun documentPromptText(content: String): String? {
    val index = content.indexOf(ATTACHED_FILE_MARKER)
    return if (index >= 0) content.substring(0, index).trim() else null
}

/** How many `[Attached file: …]` sections a user message's text part carries. */
internal fun countAttachedFiles(content: String): Int =
    content.split(ATTACHED_FILE_MARKER).size - 1

/**
 * The Markdown chat export ("Export chat" in the chat menu) and its reader
 * (issue #83). The export is written for people to read, so it is lossy:
 * images and documents become a count, tool-call arguments are cut at
 * [TOOL_ARGS_LIMIT] characters and the on-screen transcript is not included.
 * [parse] rebuilds as much of the conversation as the export carries, so a
 * chat shared as Markdown can be imported and continued, and an imported chat
 * exports back to the same Markdown. For a lossless copy use [ChatArchive].
 */
object ChatMarkdown {

    const val HEADER = "# Gotcha Chat Export"
    const val TOOL_ARGS_LIMIT = 200

    /** Prefix of the synthetic tool-call ids given to calls read back from Markdown. */
    const val IMPORTED_CALL_PREFIX = "imported_call_"

    /** Name of the call invented for a tool result the export shows no call for. */
    const val UNKNOWN_TOOL = "imported_result"

    private const val DATE_PATTERN = "yyyy-MM-dd HH:mm:ss"
    private const val SUBAGENT_PREFIX = "SUBAGENT_STEPS:"
    private const val STEPS_MARKER = "── Steps ──\n"
    private const val RESULT_MARKER = "\n── Result ──\n"
    private const val NO_RESULT = "(no result)"
    private const val NO_SYSTEM_TEXT = "(system message)"
    private const val CALLED_TOOLS = "**Called tools:**"

    private val json = Json { ignoreUnknownKeys = true }
    private val HEADER_FIELD = Regex("""\*\*(\w+):\*\*(.*)""")

    fun export(
        history: List<ChatMessage>,
        sessionId: String,
        title: String?,
        now: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault()
    ): String {
        val sb = StringBuilder()
        sb.appendLine(HEADER)
        sb.appendLine("**Session:** $sessionId")
        if (!title.isNullOrBlank()) sb.appendLine("**Title:** ${title.lineSequence().first()}")
        sb.appendLine("**Date:** ${dateFormat(zone).format(java.util.Date(now))}")
        sb.appendLine("**Messages:** ${history.size}")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        for (msg in history) {
            val text = msg.textContent
            when (msg.role) {
                "user" -> {
                    sb.appendLine("### User")
                    val prompt = documentPromptText(text) ?: text
                    if (prompt.isNotBlank()) sb.appendLine(prompt)
                    attachmentLabel(documents = countAttachedFiles(text), images = msg.imageCount)
                        ?.let { sb.appendLine("*($it)*") }
                    sb.appendLine()
                }
                "assistant" -> {
                    sb.appendLine("### Assistant")
                    if (text.isNotBlank()) sb.appendLine(text)
                    val calls = msg.toolCalls
                    if (!calls.isNullOrEmpty()) {
                        sb.appendLine()
                        sb.appendLine(CALLED_TOOLS)
                        for (call in calls) {
                            sb.appendLine("- `${call.function.name}(${call.function.arguments.take(TOOL_ARGS_LIMIT)})`")
                        }
                    }
                    sb.appendLine()
                }
                "tool" -> {
                    if (text.startsWith(SUBAGENT_PREFIX)) {
                        appendSubAgent(sb, text)
                    } else {
                        sb.appendLine("### Tool Result")
                        sb.appendLine(text.ifEmpty { NO_RESULT })
                    }
                    sb.appendLine()
                }
                "system" -> {
                    sb.appendLine("### System")
                    sb.appendLine(text.ifEmpty { NO_SYSTEM_TEXT })
                    sb.appendLine()
                }
            }
        }
        return sb.toString()
    }

    /** True when [text] looks like a Gotcha Markdown export (its first line is [HEADER]). */
    fun looksLikeExport(text: String): Boolean =
        text.removePrefix(BYTE_ORDER_MARK).trimStart().lineSequence().firstOrNull()?.trim() == HEADER

    /** A Markdown export read back: the header fields and the rebuilt LLM history. */
    data class Parsed(
        val sessionId: String?,
        val title: String?,
        val exportedAt: Long?,
        /** The "**Messages:**" count the export declared, or null when missing. */
        val declaredCount: Int?,
        /** How many message sections were found, to compare with [declaredCount]. */
        val sectionCount: Int,
        val messages: List<ChatMessage>,
        /** Anything that was read with a guess, for the import report. */
        val warnings: List<String>
    )

    /**
     * Reads a [export] back into a history. Only the exact section headings the
     * export writes ("### User", "### Assistant", "### Tool Result",
     * "### System", "### Sub-Agent: …") start a message, so Markdown inside a
     * reply (its own "### Steps" heading, say) stays part of that reply.
     *
     * Tool calls get synthetic ids and are paired with the results that follow
     * them in order, so the rebuilt history is one a provider accepts: a call
     * without a result gets a stub result and a result without a call gets a
     * stub call, each noted in [Parsed.warnings].
     *
     * Throws [IllegalArgumentException] when [text] is not an export at all.
     */
    fun parse(text: String, zone: TimeZone = TimeZone.getDefault()): Parsed {
        require(looksLikeExport(text)) { "Not a Gotcha chat export: the first line should be \"$HEADER\"." }
        val lines = text.removePrefix(BYTE_ORDER_MARK).replace("\r\n", "\n").trimStart().lines()

        val warnings = mutableListOf<String>()
        // Header: everything up to the first section heading.
        val headerEnd = (1 until lines.size).firstOrNull { sectionOf(lines[it]) != null } ?: lines.size
        val header = headerFields(lines.subList(1, headerEnd))
        val sections = sections(lines.subList(headerEnd, lines.size))

        val messages = mutableListOf<ChatMessage>()
        // Calls of the latest assistant message still waiting for their results.
        val openCalls = ArrayDeque<ToolCall>()
        var callCounter = 0

        fun closeOpenCalls() {
            if (openCalls.isEmpty()) return
            warnings += "${openCalls.size} tool call(s) had no result in the export; marked as missing."
            while (openCalls.isNotEmpty()) {
                messages += ChatMessage(
                    role = "tool",
                    content = JsonPrimitive("(result not included in the export)"),
                    toolCallId = openCalls.removeFirst().id
                )
            }
        }

        fun addToolResult(content: String) {
            val call = openCalls.removeFirstOrNull() ?: run {
                // A result with no call before it: give it one so the pair is valid.
                warnings += "A tool result had no matching call in the export; gave it a placeholder call."
                val stub =
                    ToolCall("$IMPORTED_CALL_PREFIX${callCounter++}", function = FunctionCall(UNKNOWN_TOOL, "{}"))
                messages += ChatMessage(role = "assistant", content = JsonPrimitive(""), toolCalls = listOf(stub))
                stub
            }
            messages += ChatMessage(role = "tool", content = JsonPrimitive(content), toolCallId = call.id)
        }

        for ((section, body) in sections) {
            when (section) {
                Section.User -> {
                    closeOpenCalls()
                    messages += ChatMessage(role = "user", content = JsonPrimitive(body))
                }
                Section.Assistant -> {
                    closeOpenCalls()
                    val (reply, calls) = splitCalledTools(body)
                    val toolCalls = calls.map { (name, args) ->
                        ToolCall(
                            "$IMPORTED_CALL_PREFIX${callCounter++}",
                            function = FunctionCall(name, validArguments(args))
                        )
                    }
                    messages += ChatMessage(
                        role = "assistant",
                        content = JsonPrimitive(reply),
                        toolCalls = toolCalls.ifEmpty { null }
                    )
                    openCalls.addAll(toolCalls)
                }
                Section.ToolResult -> addToolResult(if (body == NO_RESULT) "" else body)
                is Section.SubAgent -> addToolResult(subAgentContent(section.description, body))
                Section.System -> {
                    closeOpenCalls()
                    messages += ChatMessage(
                        role = "system",
                        content = JsonPrimitive(if (body == NO_SYSTEM_TEXT) "" else body)
                    )
                }
            }
        }
        closeOpenCalls()

        val declaredCount = header["Messages"]?.toIntOrNull()
        if (declaredCount != null && declaredCount != sections.size) {
            warnings += "The export says $declaredCount messages but ${sections.size} were found; " +
                "the file may have been edited or cut short."
        }
        return Parsed(
            sessionId = header["Session"]?.takeIf { it != "unknown" },
            title = header["Title"],
            exportedAt = header["Date"]?.let { runCatching { dateFormat(zone).parse(it)?.time }.getOrNull() },
            declaredCount = declaredCount,
            sectionCount = sections.size,
            messages = messages,
            warnings = warnings
        )
    }

    /** The header's `**Name:** value` lines as name → value, blank values left out. */
    private fun headerFields(lines: List<String>): Map<String, String> =
        lines.mapNotNull { line ->
            HEADER_FIELD.matchEntire(line.trim())?.destructured?.let { (name, value) -> name to value.trim() }
        }.filter { it.second.isNotEmpty() }.toMap()

    /** Each section heading with the lines up to the next heading, trimmed. [lines] starts at a heading. */
    private fun sections(lines: List<String>): List<Pair<Section, String>> {
        val sections = mutableListOf<Pair<Section, String>>()
        var body = mutableListOf<String>()
        var heading: Section? = null
        fun flush() {
            heading?.let { sections += it to body.joinToString("\n").trim('\n').trimEnd() }
        }
        for (line in lines) {
            val next = sectionOf(line)
            if (next == null) {
                body += line
            } else {
                flush()
                heading = next
                body = mutableListOf()
            }
        }
        flush()
        return sections
    }

    private sealed class Section {
        object User : Section()
        object Assistant : Section()
        object ToolResult : Section()
        object System : Section()
        data class SubAgent(val description: String) : Section()
    }

    private fun sectionOf(line: String): Section? = when {
        line == "### User" -> Section.User
        line == "### Assistant" -> Section.Assistant
        line == "### Tool Result" -> Section.ToolResult
        line == "### System" -> Section.System
        line.startsWith("### Sub-Agent: ") -> Section.SubAgent(line.removePrefix("### Sub-Agent: "))
        else -> null
    }

    /**
     * Splits an assistant section into its reply and the `name(args)` pairs of
     * its trailing "**Called tools:**" list. A "**Called tools:**" line that is
     * not followed only by call entries is ordinary reply text.
     */
    private fun splitCalledTools(body: String): Pair<String, List<Pair<String, String>>> {
        val lines = body.lines()
        val marker = lines.indexOfLast { it == CALLED_TOOLS }
        if (marker < 0) return body to emptyList()
        val entries = lines.drop(marker + 1).filter { it.isNotBlank() }
        val calls = entries.map { entry ->
            val inner = entry.takeIf { it.startsWith("- `") && it.endsWith(")`") }
                ?.removePrefix("- `")?.removeSuffix("`")
                ?: return body to emptyList()
            val open = inner.indexOf('(')
            if (open <= 0) return body to emptyList()
            inner.substring(0, open) to inner.substring(open + 1, inner.length - 1)
        }
        if (calls.isEmpty()) return body to emptyList()
        return lines.take(marker).joinToString("\n").trimEnd() to calls
    }

    /**
     * Arguments as the export shows them, when they are still JSON. Arguments
     * longer than [TOOL_ARGS_LIMIT] were cut and no longer parse; those are kept
     * as a string field so no provider rejects the history over them.
     */
    private fun validArguments(args: String): String {
        val parses = runCatching { json.parseToJsonElement(args) }.isSuccess
        return if (parses) args else buildJsonObject { put("truncated_arguments", args) }.toString()
    }

    /** Rebuilds a sub-agent tool result from its export section; see [appendSubAgent]. */
    private fun subAgentContent(description: String, body: String): String {
        // The section is trimmed, so an empty result leaves "**Result:**" as the last line.
        val resultAt = "$body\n".indexOf("\n**Result:**\n")
        if (!body.startsWith("**Steps:**") || resultAt < 0) {
            return "$SUBAGENT_PREFIX$description\n$body"
        }
        val steps = body.substring("**Steps:**".length, resultAt)
            .lines()
            .filter { it.isNotBlank() }
            .map { it.removePrefix("- ") }
        val answer = "$body\n".substring(resultAt + "\n**Result:**\n".length).removeSuffix("\n")
        return "$SUBAGENT_PREFIX$description\n$STEPS_MARKER${steps.joinToString("\n")}$RESULT_MARKER$answer"
    }

    /** Formats a SUBAGENT_STEPS tool message (description, steps, result). */
    private fun appendSubAgent(sb: StringBuilder, text: String) {
        val descEnd = text.indexOf('\n', SUBAGENT_PREFIX.length)
        val desc = if (descEnd > 0) {
            text.substring(SUBAGENT_PREFIX.length, descEnd)
        } else {
            text.substring(SUBAGENT_PREFIX.length)
        }
        sb.appendLine("### Sub-Agent: $desc")
        val rest = if (descEnd > 0) text.substring(descEnd + 1) else ""
        if (!rest.startsWith(STEPS_MARKER)) {
            sb.appendLine(rest)
            return
        }
        val afterSteps = rest.removePrefix(STEPS_MARKER)
        val resIdx = afterSteps.indexOf(RESULT_MARKER)
        if (resIdx < 0) {
            sb.appendLine(afterSteps)
            return
        }
        val steps = afterSteps.substring(0, resIdx).split("\n").filter { it.isNotBlank() }
        val answer = afterSteps.substring(resIdx + RESULT_MARKER.length)
        sb.appendLine()
        sb.appendLine("**Steps:**")
        for (s in steps) sb.appendLine("- $s")
        sb.appendLine()
        sb.appendLine("**Result:**")
        sb.appendLine(answer)
    }

    /**
     * The export's note for a user message's attachments, e.g. "Image attached"
     * or "3 images, 1 document attached"; null when there are none.
     */
    private fun attachmentLabel(documents: Int, images: Int): String? = when {
        documents == 0 && images == 0 -> null
        documents == 1 && images == 0 -> "Document attached"
        documents == 0 && images == 1 -> "Image attached"
        else -> listOfNotNull(
            images.takeIf { it > 0 }?.let { plural(it, "image") },
            documents.takeIf { it > 0 }?.let { plural(it, "document") }
        ).joinToString(", ") + " attached"
    }

    private fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

    private fun dateFormat(zone: TimeZone) =
        SimpleDateFormat(DATE_PATTERN, Locale.US).apply { timeZone = zone }
}
