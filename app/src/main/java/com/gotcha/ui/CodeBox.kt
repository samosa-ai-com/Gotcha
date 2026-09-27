package com.gotcha.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gotcha.ui.theme.GotchaMono
import com.gotcha.ui.theme.LocalSkin
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.Material3RichText

/**
 * Markdown with its fenced code blocks drawn as [CodeBox]es, so a command the
 * user has to run can be copied exactly (issue #109).
 */
@Composable
fun RichMarkdown(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        splitFencedBlocks(markdown).forEach { segment ->
            when (segment) {
                is MarkdownSegment.Prose -> Material3RichText { Markdown(segment.markdown) }
                is MarkdownSegment.Code -> CodeBox(code = segment.code, language = segment.language)
            }
        }
    }
}

/**
 * A code block with a copy button: monospace, never wrapped mid-token (long
 * lines scroll sideways), and set apart from the prose around it.
 */
@Composable
fun CodeBox(code: String, language: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LocalSkin.current.cornerSmall),
        color = colors.surfaceContainerHighest,
        contentColor = colors.onSurface
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { copyCode(context, code) }) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text("Copy", modifier = Modifier.padding(start = 6.dp))
                }
            }
            Text(
                text = code,
                fontFamily = GotchaMono,
                style = MaterialTheme.typography.bodyMedium,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            )
        }
    }
}

private fun copyCode(context: Context, code: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Code", code))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
