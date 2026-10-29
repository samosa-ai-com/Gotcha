@file:Suppress("MatchingDeclarationName")

package com.gotcha.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Column alignment parsed from a GFM delimiter row (`:---`, `:--:`, `---:`). */
enum class TableAlign { LEFT, CENTER, RIGHT }

/**
 * A GFM table: pipe header, delimiter row, pipe body rows. The bundled
 * Markdown renderer sizes every column equally, which squeezes wide tables
 * into unreadable slivers on a phone, so tables are drawn here instead in a
 * sideways-scrollable grid with capped column widths.
 */
fun splitTables(prose: String): List<MarkdownSegment> {
    val segments = mutableListOf<MarkdownSegment>()
    val text = StringBuilder()
    val lines = prose.lines()
    var i = 0
    fun flush() {
        val t = text.toString().trim('\n')
        if (t.isNotBlank()) segments += MarkdownSegment.Prose(t)
        text.setLength(0)
    }
    while (i < lines.size) {
        val header = tableLine(lines.getOrNull(i))
        val delimiter = tableLine(lines.getOrNull(i + 1))
        if (header != null && delimiter != null && header.contains('|') && isDelimiterRow(delimiter) &&
            splitRow(header).size == splitRow(delimiter).size
        ) {
            val headers = splitRow(header)
            val aligns = splitRow(delimiter).map(::alignOf)
            val width = headers.size
            val rows = mutableListOf<List<String>>()
            var j = i + 2
            while (j < lines.size) {
                val row = tableLine(lines[j]) ?: break
                if (!row.contains('|') || isDelimiterRow(row)) break
                rows += splitRow(row).fitTo(width, "")
                j++
            }
            flush()
            segments += MarkdownSegment.Table(headers, aligns.fitTo(width, TableAlign.LEFT), rows)
            i = j
        } else {
            text.appendLine(lines.getOrNull(i).orEmpty())
            i++
        }
    }
    flush()
    return segments
}

/** Table-eligible line: up to 3 leading spaces (GFM allows 3). deeper indents are code, `>` quotes stay with the bundled renderer. */
private fun tableLine(line: String?): String? {
    if (line == null) return null
    val stripped = line.trimStart()
    if (stripped.startsWith('>')) return null
    val indent = line.length - stripped.length
    if (line.startsWith('\t') || indent >= 4) return null
    return stripped
}

/** True when [line] is a GFM delimiter row: pipes of dashes with optional edge colons. */
private fun isDelimiterRow(line: String): Boolean {
    val cells = splitRow(line)
    if (cells.isEmpty()) return false
    return cells.all { it.matches(Regex(":?-{1,}:?")) }
}

/** Splits one table row on unescaped pipes; trims cells and drops the outer pair. */
private fun splitRow(line: String): List<String> {
    val cells = mutableListOf<String>()
    val cur = StringBuilder()
    var escaped = false
    line.forEach { ch ->
        when {
            escaped -> {
                cur.append(ch)
                escaped = false
            }
            ch == '\\' -> escaped = true
            ch == '|' -> {
                cells += cur.toString()
                cur.clear()
            }
            else -> cur.append(ch)
        }
    }
    cells += cur.toString()
    val trimmed = cells.map { it.trim() }
    val inner = { list: List<String> ->
        var result = list
        if (result.isNotEmpty() && result.first().isEmpty()) result = result.drop(1)
        if (result.isNotEmpty() && result.last().isEmpty()) result = result.dropLast(1)
        result
    }
    return inner(trimmed)
}

private fun alignOf(cell: String): TableAlign = when {
    cell.startsWith(':') && cell.endsWith(':') -> TableAlign.CENTER
    cell.endsWith(':') -> TableAlign.RIGHT
    else -> TableAlign.LEFT
}

private fun <T> List<T>.fitTo(width: Int, pad: T): List<T> {
    val taken = take(width)
    return if (taken.size >= width) taken else taken + List(width - taken.size) { pad }
}

private fun TableAlign.toTextAlign(): TextAlign = when (this) {
    TableAlign.LEFT -> TextAlign.Start
    TableAlign.CENTER -> TextAlign.Center
    TableAlign.RIGHT -> TextAlign.End
}

/**
 * A sideways-scrollable table: columns size to their content between a fixed
 * min and max, so narrow columns stay compact and wide ones wrap instead of
 * squeezing their neighbours.
 */
@Composable
fun MarkdownTable(
    headers: List<String>,
    aligns: List<TableAlign>,
    rows: List<List<String>>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp
    ) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            headers.forEachIndexed { index, header ->
                val align = aligns.getOrElse(index) { TableAlign.LEFT }.toTextAlign()
                val cells = rows.map { it.getOrElse(index) { "" } }
                Column(
                    modifier = Modifier
                        .widthIn(min = 84.dp, max = 240.dp)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    // No fillMaxWidth: in the scrolling row each column sizes
                    // to its content (within the caps) instead of every
                    // column stretching to the 240dp maximum.
                    Text(
                        text = header,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = align
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    cells.forEachIndexed { rowIndex, cell ->
                        Text(
                            text = cell,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = align
                        )
                        if (rowIndex < cells.size - 1) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}
