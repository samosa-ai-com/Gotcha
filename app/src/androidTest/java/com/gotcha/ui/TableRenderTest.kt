package com.gotcha.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for the chat crash on tables: the grid must measure and
 * place shared column widths and row heights on a real device, including
 * long wrapping cells that span several lines.
 */
@RunWith(AndroidJUnit4::class)
class TableRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val headers = listOf("Time", "Item", "Est.", "Done when")
    private val aligns = listOf(TableAlign.LEFT, TableAlign.LEFT, TableAlign.LEFT, TableAlign.LEFT)
    private val rows = listOf(
        listOf(
            "08:00",
            "Morning standup with the whole distributed team to share blockers and align on priorities",
            "~15m",
            "Every blocker has a named owner and a next step agreed in the meeting"
        ),
        listOf("09:00", "Deep work", "~2h", "Draft done")
    )

    @Test
    fun wideTableWithWrappingCells_rendersWithoutCrashing() {
        composeRule.setContent {
            MarkdownTable(headers = headers, aligns = aligns, rows = rows)
        }
        composeRule.waitForIdle()
        // Each cell is composed twice (probe + final subcompose slots).
        composeRule.onAllNodesWithText("Deep work").assertCountEquals(2)
        composeRule.onAllNodesWithText("blockers", substring = true).assertCountEquals(2)
    }

    @Test
    fun fullBriefingMarkdown_rendersWithoutCrashing() {
        val markdown = buildString {
            appendLine("Good morning — shipping day.")
            appendLine()
            appendLine("**Morning:**")
            appendLine("| Time | Item | Est. | Done when |")
            appendLine("|------|------|------|-----------|")
            rows.forEach { appendLine("| ${it.joinToString(" | ")} |") }
        }
        composeRule.setContent {
            RichMarkdown(markdown)
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Deep work").assertCountEquals(2)
    }
}
