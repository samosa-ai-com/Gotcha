package com.gotcha.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gotcha.agent.MessageKind
import com.gotcha.agent.UiMessage
import com.gotcha.ui.theme.GotchaMono
import com.gotcha.ui.theme.LocalSkin
import com.gotcha.ui.theme.SkinDropdownMenu
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.Material3RichText

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: UiMessage,
    onSpeak: (String) -> Unit = {},
    isSpeaking: Boolean = false,
    onStopSpeaking: () -> Unit = {},
    onEdit: (UiMessage) -> Unit = {},
    onRevert: (UiMessage) -> Unit = {}
) {
    val context = LocalContext.current
    val isUser = message.kind == MessageKind.USER
    val isAssistant = message.kind == MessageKind.ASSISTANT
    val isTool = message.kind == MessageKind.TOOL
    // A failed tool call arrives as ERROR (see AgentEngine), which used to make
    // it a fat red bubble in the middle of a column of ledger lines — the one
    // row you most want to find, rendered as the one row that breaks the scan.
    // App-level errors are prose and keep their bubble; tool failures do not.
    val isToolFailure = message.kind == MessageKind.ERROR && looksLikeToolOutput(message.text)
    val isSubAgent = message.kind == MessageKind.SUBAGENT
    val colors = MaterialTheme.colorScheme
    // Only the user gets a bubble. An assistant reply can run to several
    // paragraphs, and wrapping that in a container turns reading into scanning a
    // receipt; set on the ground it reads like prose, which is what it is.
    // A tool call is neither — see the ledger rail below.
    val (container, contentColor) = when (message.kind) {
        MessageKind.USER -> colors.primaryContainer to colors.onPrimaryContainer
        MessageKind.ASSISTANT -> Color.Transparent to colors.onSurface
        MessageKind.TOOL -> Color.Transparent to colors.onSurfaceVariant
        MessageKind.ERROR ->
            if (isToolFailure) {
                Color.Transparent to colors.error
            } else {
                colors.errorContainer to colors.onErrorContainer
            }
        // Was a hardcoded navy/lilac pair, which looked deliberate only while
        // every theme happened to be navy.
        MessageKind.SUBAGENT -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    val unbubbled = isAssistant || isTool || isToolFailure
    val skin = LocalSkin.current
    val expanded = remember { mutableStateOf(!isTool && !isSubAgent) }
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
            shadowElevation = if (unbubbled) 0.dp else 2.dp,
            // The skin owns the shape as well as the colour: Orchid is generous,
            // Nocturne is tight, Deep Space is exactly what it always was.
            shape = RoundedCornerShape(
                topStart = skin.corner,
                topEnd = skin.corner,
                bottomStart = if (isUser) skin.corner else 4.dp,
                bottomEnd = if (isUser) 4.dp else skin.corner
            )
        ) {
            Column(
                modifier = Modifier
                    .then(
                        // Prose wants the full column; a bubble wants to stay a bubble.
                        if (isAssistant) Modifier.fillMaxWidth() else Modifier.widthIn(max = 320.dp)
                    )
                    .then(
                        if (isTool || isSubAgent) {
                            Modifier.combinedClickable(
                                onClick = { if (isTool) expanded.value = !expanded.value },
                                onLongClick = { showMenu = true }
                            )
                        } else {
                            Modifier.combinedClickable(
                                onClick = { },
                                onLongClick = { showMenu = true }
                            )
                        }
                    )
                    .padding(
                        horizontal = if (unbubbled) 6.dp else 16.dp,
                        vertical = if (unbubbled) 6.dp else 16.dp
                    )
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
                message.reasoningContent?.let { reasoning ->
                    var reasoningExpanded by remember { mutableStateOf(false) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(skin.cornerSmall))
                            .background(contentColor.copy(alpha = 0.05f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { reasoningExpanded = !reasoningExpanded }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "💭 Reasoning process",
                                style = MaterialTheme.typography.labelMedium,
                                color = contentColor.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Icon(
                                imageVector = if (reasoningExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "Toggle reasoning",
                                modifier = Modifier.size(16.dp),
                                tint = contentColor.copy(alpha = 0.7f)
                            )
                        }
                        if (reasoningExpanded) {
                            HorizontalDivider(color = contentColor.copy(alpha = 0.1f))
                            Text(
                                text = reasoning,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                ),
                                color = contentColor.copy(alpha = 0.8f),
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
                if (message.text.isNotEmpty()) {
                    val displayText = message.text
                    val firstLine = displayText.substringBefore("\n").trimEnd()
                    val hasMore = displayText.contains("\n")

                    if (isTool || isToolFailure) {
                        ToolLedger(
                            text = if (expanded.value) displayText else firstLine,
                            hint = if (!expanded.value && hasMore) "tap to expand" else null,
                            railColor = if (isToolFailure) colors.error else colors.primary,
                            contentColor = contentColor,
                            collapsed = !expanded.value
                        )
                    } else if (isSubAgent) {
                        SubAgentContent(
                            message = message,
                            contentColor = contentColor,
                            expanded = expanded
                        )
                    } else {
                        Material3RichText {
                            Markdown(displayText)
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
                            onClick = {
                                if (isSpeaking) {
                                    onStopSpeaking()
                                } else {
                                    onSpeak(message.text)
                                }
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = if (isSpeaking) {
                                    Icons.AutoMirrored.Rounded.VolumeOff
                                } else {
                                    Icons.AutoMirrored.Rounded.VolumeUp
                                },
                                contentDescription = if (isSpeaking) "Stop reading" else "Speak",
                                modifier = Modifier.size(18.dp),
                                tint = if (isSpeaking) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    contentColor.copy(alpha = 0.7f)
                                }
                            )
                        }
                    }
                }
            }
        }

        SkinDropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            if (isUser) {
                DropdownMenuItem(
                    text = { Text("Edit message") },
                    onClick = {
                        showMenu = false
                        onEdit(message)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Revert to this message") },
                    onClick = {
                        showMenu = false
                        onRevert(message)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Copy as text") },
                onClick = {
                    copyPlainText(context, message.text, isTool || isSubAgent)
                    showMenu = false
                }
            )
            DropdownMenuItem(
                text = { Text("Copy as markdown") },
                onClick = {
                    copyMarkdown(context, message.text, isTool || isSubAgent)
                    showMenu = false
                }
            )
        }
    }
}

/**
 * A tool call, rendered as a ledger line rather than a chat bubble: an accent
 * rail, the call in monospace, one line until tapped. A turn can make twenty of
 * these, and twenty bubbles bury the two sentences of prose that answered the
 * question.
 */
/**
 * Tool output is formatted `name: message` by the engine, so an ERROR that opens
 * with a bare identifier and a colon came from a tool. App errors are sentences
 * — "No API key configured.", "Transcription failed: …" — and never match, since
 * they start with a capital or contain a space before the colon.
 */
private val ToolOutputPrefix = Regex("^[a-z][a-z0-9_.]{1,40}:")

private fun looksLikeToolOutput(text: String): Boolean =
    ToolOutputPrefix.containsMatchIn(text)

@Composable
private fun ToolLedger(
    text: String,
    hint: String?,
    railColor: Color,
    contentColor: Color,
    collapsed: Boolean
) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.dp))
                .background(railColor.copy(alpha = 0.7f))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = GotchaMono,
                color = contentColor,
                maxLines = if (collapsed) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = GotchaMono,
                    color = contentColor.copy(alpha = 0.55f)
                )
            }
        }
    }
}

@Composable
private fun SubAgentContent(
    message: UiMessage,
    contentColor: Color,
    expanded: androidx.compose.runtime.MutableState<Boolean>
) {
    val steps = message.subAgentSteps
    val answer = message.text

    // Header row — always visible, clickable to toggle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded.value = !expanded.value }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "⚡ General Agent",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = contentColor
        )
        Spacer(modifier = Modifier.weight(1f))
        if (steps.isNotEmpty()) {
            Text(
                text = "${steps.size} step${if (steps.size != 1) "s" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Icon(
            imageVector = if (expanded.value) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded.value) "Collapse" else "Expand",
            modifier = Modifier.size(20.dp),
            tint = contentColor.copy(alpha = 0.7f)
        )
    }

    if (expanded.value) {
        // Steps section
        if (steps.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            steps.forEach { step ->
                val icon = when {
                    step.startsWith("reasoning:") -> "💭"
                    step.contains("→ (running)") -> "⋯"
                    step.contains("→ completed") || step.contains("→ completed:") -> "✓"
                    step.contains("→ failed") -> "✗"
                    else -> "·"
                }
                val display = step.removePrefix("reasoning:")
                Row(modifier = Modifier.padding(vertical = 1.dp)) {
                    Text(
                        text = icon,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = display.trimStart(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = contentColor.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Divider
        if (answer.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(
                color = contentColor.copy(alpha = 0.2f),
                thickness = 1.dp
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Final answer with markdown rendering
        if (answer.isNotBlank()) {
            Material3RichText {
                Markdown(answer)
            }
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
