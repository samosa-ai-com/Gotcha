@file:Suppress("MatchingDeclarationName")

package com.gotcha.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.Material3RichText

/** Column alignment parsed from a GFM delimiter row (`:---`, `:--:`, `---:`). */
enum class TableAlign { LEFT, CENTER, RIGHT }

/**
 * A GFM table: pipe header, delimiter row, pipe body rows. The bundled
 * Markdown renderer sizes every column equally, which squeezes wide tables
 * into unreadable slivers on a phone, so tables are drawn here instead in a
 * sideways-scrollable grid whose columns size to their content.
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

/** Table-eligible line: visual indent under 4 (GFM allows 3 spaces). deeper indents are code, `>` quotes stay with the bundled renderer. */
private fun tableLine(line: String?): String? {
    if (line == null) return null
    val stripped = line.trimStart()
    if (stripped.startsWith('>')) return null
    if (visualIndent(line) >= 4) return null
    return stripped
}

/** Visual indent in columns: spaces count 1, tabs advance to the next multiple of 4. */
private fun visualIndent(line: String): Int {
    var column = 0
    for (ch in line) {
        when (ch) {
            ' ' -> column++
            '\t' -> column = (column / 4 + 1) * 4
            else -> break
        }
    }
    return column
}

/** True when [line] is a GFM delimiter row: pipes of dashes with optional edge colons. */
private fun isDelimiterRow(line: String): Boolean {
    val cells = splitRow(line)
    if (cells.isEmpty()) return false
    return cells.all { it.matches(Regex(":?-{1,}:?")) }
}

/**
 * Splits one table row on unescaped pipes. Escapes pass through untouched for
 * the cell renderer (a Markdown parser): `\|` renders as `|`, `\\` as `\`.
 * Leading/trailing pipes count as an outer border only when both are really
 * there; otherwise edge empties are real cells (` | second` in a two-column
 * table means an empty first cell).
 */
private fun splitRow(line: String): List<String> {
    val s = line.trim()
    val cells = mutableListOf<String>()
    val cur = StringBuilder()
    s.forEachIndexed { index, ch ->
        if (ch == '|' && !isEscaped(s, index)) {
            cells += cur.toString().trim()
            cur.clear()
        } else {
            cur.append(ch)
        }
    }
    cells += cur.toString().trim()
    if (cells.size >= 2 && s.startsWith('|') && s.endsWith('|') && !isEscaped(s, s.length - 1)) {
        return cells.drop(1).dropLast(1)
    }
    return cells
}

/** True when the char at [index] is escaped by an odd run of backslashes. */
private fun isEscaped(s: String, index: Int): Boolean {
    var backslashes = 0
    var k = index - 1
    while (k >= 0 && s[k] == '\\') {
        backslashes++
        k--
    }
    return backslashes % 2 == 1
}

private fun alignOf(cell: String): TableAlign = when {
    cell.startsWith(':') && cell.endsWith(':') && cell.length > 1 -> TableAlign.CENTER
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

/** Grid lines recorded during measure and drawn afterwards. Plain holder, never state. */
private class TableGeometry {
    val verticals = mutableListOf<Float>()
    val horizontals = mutableListOf<Float>()
    var width = 0f
    var height = 0f
}

/**
 * A grid with shared column widths and shared row heights: every cell of a
 * row sits on the same baseline band, so wrapped text can never pair values
 * with the wrong row. Columns size to their content between [minColumnWidth]
 * and [maxColumnWidth]; the caller scrolls sideways.
 *
 * Each cell is composed twice into two subcompose slots (probe, then final)
 * because one Measurable may be measured only once per pass: the probe pass
 * sizes the columns, the final pass wraps text at the shared width. Tables
 * are small, so the double composition stays cheap.
 */
@Composable
private fun TableGrid(
    columnCount: Int,
    minColumnWidth: Int,
    maxColumnWidth: Int,
    lineColor: Color,
    modifier: Modifier = Modifier,
    cells: List<@Composable () -> Unit>
) {
    val geometry = remember { TableGeometry() }
    SubcomposeLayout(
        modifier = modifier.drawBehind {
            geometry.verticals.forEach { x ->
                drawLine(lineColor, Offset(x, 0f), Offset(x, geometry.height))
            }
            geometry.horizontals.forEach { y ->
                drawLine(lineColor, Offset(0f, y), Offset(geometry.width, y))
            }
        }
    ) { _ ->
        val probe = subcompose("probe") {
            cells.forEach { it() }
        }.map { it.measure(Constraints(maxWidth = maxColumnWidth)) }
        val columnWidths = IntArray(columnCount) { column ->
            probe.filterIndexed { index, _ -> index % columnCount == column }
                .maxOf { it.width }
                .coerceIn(minColumnWidth, maxColumnWidth)
        }
        val rows = subcompose("final") {
            cells.forEach { it() }
        }.chunked(columnCount).filter { it.size == columnCount }
        if (rows.isEmpty()) {
            geometry.verticals.clear()
            geometry.horizontals.clear()
            return@SubcomposeLayout layout(0, 0) {}
        }
        val placed = rows.map { row ->
            row.mapIndexed { column, measurable ->
                measurable.measure(Constraints.fixedWidth(columnWidths[column]))
            }
        }
        val rowHeights = placed.map { row -> row.maxOf { it.height } }
        val columnLefts = IntArray(columnCount + 1)
        for (column in 1..columnCount) {
            columnLefts[column] = columnLefts[column - 1] + columnWidths[column - 1]
        }
        val rowTops = IntArray(rows.size + 1)
        for (row in 1..rows.size) {
            rowTops[row] = rowTops[row - 1] + rowHeights[row - 1]
        }
        geometry.verticals.clear()
        geometry.horizontals.clear()
        columnLefts.forEach { geometry.verticals += it.toFloat() }
        rowTops.forEach { geometry.horizontals += it.toFloat() }
        geometry.width = columnLefts[columnCount].toFloat()
        geometry.height = rowTops[rows.size].toFloat()
        layout(columnLefts[columnCount], rowTops[rows.size]) {
            placed.forEachIndexed { row, rowCells ->
                rowCells.forEachIndexed { column, placeable ->
                    placeable.place(columnLefts[column], rowTops[row])
                }
            }
        }
    }
}

/**
 * A sideways-scrollable table: columns size to their content between a fixed
 * min and max, rows share one height band, cells render inline Markdown so
 * emphasis, code and links keep working.
 */
@Composable
fun MarkdownTable(
    headers: List<String>,
    aligns: List<TableAlign>,
    rows: List<List<String>>,
    modifier: Modifier = Modifier
) {
    if (headers.isEmpty()) return
    val bodyStyle = MaterialTheme.typography.bodySmall
    val headerStyle = bodyStyle.copy(fontWeight = FontWeight.Bold)
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current
    val minColumnWidth = remember(density) { with(density) { 84.dp.roundToPx() } }
    val maxColumnWidth = remember(density) { with(density) { 240.dp.roundToPx() } }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp
    ) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            val cellContents = remember(headers, aligns, rows) {
                buildList<@Composable () -> Unit> {
                    headers.forEachIndexed { index, header ->
                        val align = aligns.getOrElse(index) { TableAlign.LEFT }
                        add {
                            ProvideTextStyle(headerStyle.copy(textAlign = align.toTextAlign())) {
                                // No fillMaxWidth: the grid measures content width, so
                                // filling here would stretch every column to the cap.
                                // Alignment comes from the cell Box instead.
                                Box(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    contentAlignment = align.toAlignment()
                                ) {
                                    Text(header)
                                }
                            }
                        }
                    }
                    rows.forEach { row ->
                        headers.indices.forEach { index ->
                            val align = aligns.getOrElse(index) { TableAlign.LEFT }
                            val text = row.getOrElse(index) { "" }
                            add {
                                ProvideTextStyle(bodyStyle.copy(textAlign = align.toTextAlign())) {
                                    Box(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        contentAlignment = align.toAlignment()
                                    ) {
                                        TableCellContent(text)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            TableGrid(
                columnCount = headers.size,
                minColumnWidth = minColumnWidth,
                maxColumnWidth = maxColumnWidth,
                lineColor = lineColor,
                cells = cellContents
            )
        }
    }
}

/** One table cell's inline Markdown, shrink-wrapped so the grid measures content width. */
@Composable
private fun TableCellContent(markdown: String) {
    Material3RichText {
        Markdown(markdown)
    }
}

private fun TableAlign.toAlignment(): Alignment = when (this) {
    TableAlign.LEFT -> Alignment.TopStart
    TableAlign.CENTER -> Alignment.TopCenter
    TableAlign.RIGHT -> Alignment.TopEnd
}
