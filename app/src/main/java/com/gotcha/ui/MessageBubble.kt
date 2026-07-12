package com.gotcha.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.Material3RichText
import com.gotcha.agent.MessageKind
import com.gotcha.agent.UiMessage

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: UiMessage,
    onSpeak: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val isUser = message.kind == MessageKind.USER
    val isAssistant = message.kind == MessageKind.ASSISTANT
    val isTool = message.kind == MessageKind.TOOL
    val colors = MaterialTheme.colorScheme
    val (container, contentColor) = when (message.kind) {
        MessageKind.USER -> colors.primaryContainer to colors.onPrimaryContainer
        MessageKind.ASSISTANT -> colors.surfaceVariant to colors.onSurfaceVariant
        MessageKind.TOOL -> colors.secondaryContainer to colors.onSecondaryContainer
        MessageKind.ERROR -> colors.errorContainer to colors.onErrorContainer
    }
    val expanded = remember { mutableStateOf(!isTool) }
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = container,
            contentColor = contentColor,
            shadowElevation = 2.dp,
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 20.dp
            )
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .then(
                        if (isTool) {
                            Modifier.combinedClickable(
                                onClick = { expanded.value = !expanded.value },
                                onLongClick = { showMenu = true }
                            )
                        } else {
                            Modifier.combinedClickable(
                                onClick = { },
                                onLongClick = { showMenu = true }
                            )
                        }
                    )
                    .padding(16.dp)
            ) {
                message.imageBase64?.let { base64 ->
                    val bitmap = try {
                        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (_: Exception) { null }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Attached image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                if (message.text.isNotEmpty()) {
                    val displayText = if (isTool) "🔧 ${message.text}" else message.text
                    val firstLine = displayText.substringBefore("\n").trimEnd()
                    val hasMore = displayText.contains("\n")

                    if (isTool && !expanded.value) {
                        Text(
                            text = firstLine,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (hasMore) {
                            Text(
                                text = "… tap to expand",
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = 0.6f)
                            )
                        }
                    } else {
                        if (message.kind == MessageKind.TOOL) {
                            Text(
                                text = displayText,
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Material3RichText {
                                Markdown(displayText)
                            }
                        }
                    }
                }
                if (isAssistant && message.text.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = { onSpeak(message.text) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                                contentDescription = "Speak",
                                modifier = Modifier.size(18.dp),
                                tint = contentColor.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }

        // Context menu on long press
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Copy as text") },
                onClick = {
                    copyPlainText(context, message.text, isTool)
                    showMenu = false
                }
            )
            DropdownMenuItem(
                text = { Text("Copy as markdown") },
                onClick = {
                    copyMarkdown(context, message.text, isTool)
                    showMenu = false
                }
            )
        }
    }
}

private fun copyPlainText(context: Context, text: String, isTool: Boolean) {
    val source = if (isTool) text else text
    val plain = stripMarkdown(source)
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Gotcha", plain))
    Toast.makeText(context, "Copied as plain text", Toast.LENGTH_SHORT).show()
}

private fun copyMarkdown(context: Context, text: String, isTool: Boolean) {
    val source = if (isTool) text else text
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Gotcha (Markdown)", source))
    Toast.makeText(context, "Copied as markdown", Toast.LENGTH_SHORT).show()
}

private fun stripMarkdown(md: String): String {
    return md
        .replace(Regex("```[\\s\\S]*?```"), "")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("__(.+?)__"), "$1")
        .replace(Regex("\\*(.+?)\\*"), "$1")
        .replace(Regex("_(.+?)_"), "$1")
        .replace(Regex("~~(.+?)~~"), "$1")
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
        .replace(Regex("!\\[([^]]*)]\\([^)]+\\)"), "$1")
        .replace(Regex("^###?\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("^>\\s+", RegexOption.MULTILINE), "")
        .trim()
}
