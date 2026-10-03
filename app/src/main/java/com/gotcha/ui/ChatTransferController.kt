package com.gotcha.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gotcha.agent.BackupRequest
import com.gotcha.agent.ChatTransferState
import com.gotcha.agent.ChatViewModel
import com.gotcha.data.DuplicateStrategy
import com.gotcha.data.ImportFormat
import com.gotcha.data.ImportPreview
import com.gotcha.ui.theme.SkinAlertDialog

/**
 * Entry points for chat backup and import (issue #83): the chat menu's "Back up
 * chat", the drawer's "Back up all chats" and "Import chats". Owns the system
 * file pickers; [ChatTransferDialogs] draws everything in between.
 */
class ChatTransferController internal constructor(
    private val viewModel: ChatViewModel,
    private val launchSave: (String) -> Unit,
    private val launchOpen: () -> Unit
) {
    /** The backup whose options dialog is open: a chat id, [ALL_CHATS], or null when closed. */
    internal var backupTarget by mutableStateOf<String?>(null)

    fun startBackup(sessionId: String) {
        backupTarget = sessionId
    }

    fun startBackupAll() {
        backupTarget = ALL_CHATS
    }

    fun startImport() = launchOpen()

    internal fun confirmBackup(includeImages: Boolean) {
        val target = backupTarget ?: return
        backupTarget = null
        val name = viewModel.prepareBackup(
            BackupRequest(sessionId = target.takeUnless { it == ALL_CHATS }, includeImages = includeImages)
        )
        launchSave(name)
    }

    internal companion object {
        const val ALL_CHATS = "\u0000all"
    }
}

@Composable
fun rememberChatTransfer(viewModel: ChatViewModel): ChatTransferController {
    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> viewModel.writeBackup(uri) }
    val open = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> viewModel.readImport(uri) }
    return remember(viewModel) {
        ChatTransferController(
            viewModel,
            launchSave = { name -> save.launch(name) },
            // Markdown files come back as any of these depending on the app that
            // saved them, and a backup often as octet-stream.
            launchOpen = {
                open.launch(
                    arrayOf(
                        "application/json",
                        "text/markdown",
                        "text/x-markdown",
                        "text/plain",
                        "application/octet-stream"
                    )
                )
            }
        )
    }
}

@Composable
fun ChatTransferDialogs(
    controller: ChatTransferController,
    state: ChatTransferState,
    onConfirmImport: (DuplicateStrategy) -> Unit,
    onDismiss: () -> Unit
) {
    controller.backupTarget?.let { target ->
        BackupOptionsDialog(
            allChats = target == ChatTransferController.ALL_CHATS,
            onConfirm = controller::confirmBackup,
            onDismiss = { controller.backupTarget = null }
        )
    }
    when (state) {
        ChatTransferState.Idle -> Unit
        is ChatTransferState.Working -> SkinAlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(state.message)
                }
            }
        )
        is ChatTransferState.Previewing -> ImportPreviewDialog(state.preview, onConfirmImport, onDismiss)
        is ChatTransferState.Report -> SkinAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(state.title) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(state.summary)
                    state.details.forEach { detail ->
                        Text(
                            "• $detail",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
        )
    }
}

/**
 * Says what a backup holds before it is written: everything, including tool
 * results that can carry messages, contacts and screen text. Images are the
 * bulk of a backup and the most revealing part, so they can be left out.
 */
@Composable
private fun BackupOptionsDialog(
    allChats: Boolean,
    onConfirm: (includeImages: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var includeImages by rememberSaveable { mutableStateOf(true) }
    SkinAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (allChats) "Back up all chats" else "Back up this chat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "The backup holds the whole conversation, including what tools returned: " +
                        "messages, contacts or text read from your screen. Anyone with the file " +
                        "can read it, so keep it somewhere private."
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = includeImages, role = Role.Checkbox, onValueChange = { includeImages = it })
                ) {
                    Checkbox(checked = includeImages, onCheckedChange = null)
                    Text("Include images and screenshots", modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(includeImages) }) { Text("Choose where to save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ImportPreviewDialog(
    preview: ImportPreview,
    onConfirm: (DuplicateStrategy) -> Unit,
    onDismiss: () -> Unit
) {
    var strategy by rememberSaveable { mutableStateOf(DuplicateStrategy.KEEP_BOTH) }
    val importable = preview.newCount + preview.conflictCount
    SkinAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import chats") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(importSummary(preview))
                if (preview.conflictCount > 0) {
                    Text(
                        if (preview.conflictCount == 1) {
                            "1 chat is already here in a different version:"
                        } else {
                            "${preview.conflictCount} chats are already here in a different version:"
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                    DuplicateStrategy.entries.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = strategy == option,
                                    role = Role.RadioButton,
                                    onClick = { strategy = option }
                                )
                        ) {
                            RadioButton(selected = strategy == option, onClick = null)
                            Text(option.label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    if (strategy == DuplicateStrategy.REPLACE && preview.format == ImportFormat.MARKDOWN) {
                        Text(
                            "Replacing with a Markdown export drops the images and attachments your copy has.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                (preview.rejected.map { "${it.title}: ${it.reason}" } + preview.warnings).forEach { note ->
                    Text(
                        "• $note",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(strategy) }, enabled = importable > 0) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun importSummary(preview: ImportPreview): String {
    val total = preview.items.size + preview.rejected.size
    val parts = listOfNotNull(
        preview.newCount.takeIf { it > 0 }?.let { "$it new" },
        preview.identicalCount.takeIf { it > 0 }?.let { "$it already here" },
        preview.conflictCount.takeIf { it > 0 }?.let { "$it changed" },
        preview.rejected.size.takeIf { it > 0 }?.let { "$it can't be read" }
    )
    val chats = if (total == 1) "1 chat" else "$total chats"
    return "${preview.format.label}: $chats (${parts.joinToString(", ")})."
}
