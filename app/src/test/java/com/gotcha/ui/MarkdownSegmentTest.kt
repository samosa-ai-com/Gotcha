package com.gotcha.ui

import com.gotcha.ui.MarkdownSegment.Code
import com.gotcha.ui.MarkdownSegment.Prose
import org.junit.Assert.assertEquals
import org.junit.Test

/** [splitFencedBlocks] decides which parts of a reply get a copyable code box (issue #109). */
class MarkdownSegmentTest {

    @Test
    fun `text without fences stays one prose segment`() {
        val md = "Run `ls` to list files.\n\n1. One\n2. Two"
        assertEquals(listOf(Prose(md)), splitFencedBlocks(md))
    }

    @Test
    fun `a fenced block is split out with its language`() {
        val md = """
            In Termux, run:

            ```bash
            echo "allow-external-apps=true" >> ~/.termux/termux.properties
            ```

            then restart Termux.
        """.trimIndent()
        assertEquals(
            listOf(
                Prose("In Termux, run:"),
                Code("echo \"allow-external-apps=true\" >> ~/.termux/termux.properties", "bash"),
                Prose("then restart Termux.")
            ),
            splitFencedBlocks(md)
        )
    }

    @Test
    fun `a fence without a language has none`() {
        assertEquals(listOf(Code("pkg update", null)), splitFencedBlocks("```\npkg update\n```"))
    }

    @Test
    fun `tilde fences and longer fences are recognized`() {
        assertEquals(listOf(Code("a\nb", "sh")), splitFencedBlocks("~~~sh\na\nb\n~~~"))
        // A shorter fence inside does not close a longer one.
        assertEquals(
            listOf(Code("```\ninner\n```", "md")),
            splitFencedBlocks("````md\n```\ninner\n```\n````")
        )
    }

    @Test
    fun `backticks do not close a tilde fence`() {
        assertEquals(listOf(Code("x\n```", null)), splitFencedBlocks("~~~\nx\n```\n~~~"))
    }

    @Test
    fun `an unclosed fence runs to the end`() {
        assertEquals(
            listOf(Prose("Streaming:"), Code("line 1\nline 2", "bash")),
            splitFencedBlocks("Streaming:\n```bash\nline 1\nline 2")
        )
    }

    @Test
    fun `an indented fence drops the same indent from its body`() {
        assertEquals(listOf(Code("cd ~\n ls", null)), splitFencedBlocks("  ```\n  cd ~\n   ls\n  ```"))
    }

    @Test
    fun `a fence indented four spaces is left to the renderer`() {
        val md = "    ```\n    code\n    ```"
        assertEquals(listOf(Prose(md)), splitFencedBlocks(md))
    }

    @Test
    fun `inline triple backticks are not a fence`() {
        val md = "Use ```code``` inline."
        assertEquals(listOf(Prose(md)), splitFencedBlocks(md))
    }

    @Test
    fun `consecutive blocks keep their order and blank code is kept`() {
        assertEquals(
            listOf(Code("a", "bash"), Code("", null), Prose("end")),
            splitFencedBlocks("```bash\na\n```\n```\n```\nend")
        )
    }

    @Test
    fun `empty text has no segments`() {
        assertEquals(emptyList<MarkdownSegment>(), splitFencedBlocks(""))
    }
}
