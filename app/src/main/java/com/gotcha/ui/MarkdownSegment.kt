package com.gotcha.ui

/**
 * A piece of a Markdown message: prose for the Markdown renderer, or a fenced
 * code block drawn as a [CodeBox] with its own copy button (issue #109).
 */
sealed interface MarkdownSegment {
    data class Prose(val markdown: String) : MarkdownSegment
    data class Code(val code: String, val language: String?) : MarkdownSegment
}

private val OPENING_FENCE = Regex("^( {0,3})(`{3,}|~{3,})\\s*([^\\s`]*)[^`]*$")

/**
 * Splits [markdown] at its top-level fenced code blocks. The renderer draws
 * those itself with no way to add a copy button, so they are taken out and
 * drawn by [CodeBox] instead; everything else stays Markdown.
 *
 * Follows CommonMark's fence rules: up to three spaces of indent, three or more
 * backticks or tildes, closed by a fence of the same character at least as
 * long. An unclosed fence runs to the end of the text, which is also how a
 * reply still being streamed looks. Fences nested deeper (in a list item or a
 * quote) are left to the renderer.
 */
fun splitFencedBlocks(markdown: String): List<MarkdownSegment> {
    val segments = mutableListOf<MarkdownSegment>()
    val prose = StringBuilder()
    val lines = markdown.lines()
    var i = 0
    while (i < lines.size) {
        val open = OPENING_FENCE.matchEntire(lines[i])
        if (open == null) {
            prose.appendLine(lines[i])
            i++
            continue
        }
        val indent = open.groupValues[1].length
        val fence = open.groupValues[2]
        val closing = Regex("^ {0,3}[${fence[0]}]{${fence.length},}\\s*$")
        val body = mutableListOf<String>()
        i++
        while (i < lines.size && !closing.matches(lines[i])) {
            body += lines[i].removeIndent(indent)
            i++
        }
        i++ // The closing fence, or past the end.
        prose.flushInto(segments)
        segments += MarkdownSegment.Code(
            code = body.joinToString("\n").trimEnd(),
            language = open.groupValues[3].ifBlank { null }
        )
    }
    prose.flushInto(segments)
    return segments
}

private fun StringBuilder.flushInto(segments: MutableList<MarkdownSegment>) {
    val text = toString().trim('\n')
    if (text.isNotBlank()) segments += MarkdownSegment.Prose(text)
    setLength(0)
}

/** Drops up to [indent] leading spaces, as CommonMark does for an indented fence's body. */
private fun String.removeIndent(indent: Int): String {
    val spaces = takeWhile { it == ' ' }.length.coerceAtMost(indent)
    return substring(spaces)
}
