package com.gotcha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gotcha.R
import com.halilibo.richtext.ui.RichTextStyle

private val LIST_ITEM = Regex("^[-*+] ")
private val BOLD_LEAD = Regex("^[-*+] (\\*\\*.+?\\*\\*)")

/**
 * The short form of a release's notes: every heading, and each top-level list
 * item cut down to its bold lead ("- **Podcast generation.**"), which is how
 * CHANGELOG.md entries are written. Returns null when the notes don't follow
 * that shape (an item with no bold lead, or no items at all) or when the short
 * form would hide nothing, so the caller shows them in full instead.
 */
internal fun releaseNotesHeadlines(markdown: String): String? {
    val kept = mutableListOf<String>()
    var hidden = false
    for (line in markdown.lines()) {
        when {
            line.startsWith("#") -> kept += line
            LIST_ITEM.containsMatchIn(line) -> {
                val lead = BOLD_LEAD.find(line) ?: return null
                kept += line.substring(0, 2) + lead.groupValues[1]
                if (line.length > lead.range.last + 1) hidden = true
            }
            line.isNotBlank() -> hidden = true
        }
    }
    if (kept.none { LIST_ITEM.containsMatchIn(it) } || !hidden) return null
    return kept.joinToString("\n")
}

/**
 * A release's notes as rendered Markdown (issue #70), sized to sit inside a
 * settings section: body text at bodySmall and headings no larger than
 * titleSmall. Long notes open as their headlines with a toggle for the rest.
 */
@Composable
fun ReleaseNotes(markdown: String, modifier: Modifier = Modifier) {
    val headlines = remember(markdown) { releaseNotesHeadlines(markdown) }
    var expanded by remember(markdown) { mutableStateOf(false) }
    val headingStyle = MaterialTheme.typography.titleSmall
    val style = remember(headingStyle) { RichTextStyle(headingStyle = { _, _ -> headingStyle }) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ProvideTextStyle(MaterialTheme.typography.bodySmall) {
            RichMarkdown(if (expanded || headlines == null) markdown else headlines, style = style)
        }
        if (headlines != null) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    stringResource(if (expanded) R.string.release_notes_show_less else R.string.release_notes_show_all)
                )
            }
        }
    }
}
