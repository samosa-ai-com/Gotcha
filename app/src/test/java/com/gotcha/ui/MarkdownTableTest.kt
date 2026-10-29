package com.gotcha.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [splitTables]: GFM table parsing for the scrollable table renderer. */
class MarkdownTableTest {

    private val table = listOf(
        "| Time | Item | Est. | Done when |",
        "|------|------|------|-----------|",
        "| 08:00 | Standup | ~15m | Blockers shared |",
        "| 09:00 | Deep work | ~2h | Draft done |",
        ""
    ).joinToString("\n")

    @Test
    fun `a table splits out of prose`() {
        val segments = splitTables("Intro.\n\n$table\nOutro.")
        assertEquals(3, segments.size)
        assertTrue(segments[0] is MarkdownSegment.Prose)
        val parsed = segments[1] as MarkdownSegment.Table
        assertEquals(listOf("Time", "Item", "Est.", "Done when"), parsed.headers)
        assertEquals(2, parsed.rows.size)
        assertEquals(listOf("08:00", "Standup", "~15m", "Blockers shared"), parsed.rows[0])
        assertTrue(segments[2] is MarkdownSegment.Prose)
    }

    @Test
    fun `alignments parse from the delimiter row`() {
        val segments = splitTables("| A | B | C |\n|:---|:--:|---:|\n| x | y | z |")
        val parsed = segments.single() as MarkdownSegment.Table
        assertEquals(listOf(TableAlign.LEFT, TableAlign.CENTER, TableAlign.RIGHT), parsed.aligns)
    }

    @Test
    fun `escaped pipes stay inside cells`() {
        val segments = splitTables("| A |\n|---|\n| x \\| y |")
        val parsed = segments.single() as MarkdownSegment.Table
        assertEquals(listOf(listOf("x | y")), parsed.rows)
    }

    @Test
    fun `ragged rows fit the header width`() {
        val segments = splitTables("| A | B |\n|---|---|\n| only |\n| one | two | three |")
        val parsed = segments.single() as MarkdownSegment.Table
        assertEquals(listOf(listOf("only", "")), parsed.rows.take(1))
        assertEquals(listOf("one", "two"), parsed.rows[1])
    }

    @Test
    fun `plain pipe text without a delimiter stays prose`() {
        val text = "Fish \\| chips\n\nNo table here."
        val segments = splitTables(text)
        assertEquals(1, segments.size)
        assertTrue(segments.single() is MarkdownSegment.Prose)
    }

    @Test
    fun `tables without outer pipes parse`() {
        val segments = splitTables("Time | Item\n--- | ---\n08:00 | Standup")
        val parsed = segments.single() as MarkdownSegment.Table
        assertEquals(listOf("Time", "Item"), parsed.headers)
        assertEquals(listOf(listOf("08:00", "Standup")), parsed.rows)
    }

    @Test
    fun `a header without a matching delimiter stays prose`() {
        val segments = splitTables("Fish | chips\n---\nNot a table.")
        assertEquals(1, segments.size)
        assertTrue(segments.single() is MarkdownSegment.Prose)
    }

    @Test
    fun `indented code and quotes stay with the bundled renderer`() {
        val code = "    | A |\n    |---|\n    | x |"
        assertTrue(splitTables(code).single() is MarkdownSegment.Prose)
        val quote = "> | A |\n> |---|\n> | x |"
        assertTrue(splitTables(quote).single() is MarkdownSegment.Prose)
    }
}
