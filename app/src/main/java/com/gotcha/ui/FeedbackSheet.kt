package com.gotcha.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gotcha.ui.theme.SkinAlertDialog

/**
 * The privacy gate before the feedback form opens. Each toggle maps 1:1 to a
 * pre-filled form field, so the user only shares what they explicitly choose.
 * Nothing leaves the device until the form is submitted.
 */
@Composable
fun FeedbackSheet(
    onDismiss: () -> Unit,
    onSubmit: (
        includeAppInfo: Boolean,
        includeUsageStats: Boolean,
        includeChatLog: Boolean,
        includeUserId: Boolean
    ) -> Unit
) {
    var includeAppInfo by remember { mutableStateOf(true) }
    var includeUsageStats by remember { mutableStateOf(true) }
    var includeChatLog by remember { mutableStateOf(false) }
    var includeUserId by remember { mutableStateOf(true) }

    SkinAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send feedback") },
        text = {
            Column {
                FeedbackToggle("App info (version, device, Android)", includeAppInfo) { includeAppInfo = it }
                FeedbackToggle("Usage stats (chats, tool calls, runs)", includeUsageStats) { includeUsageStats = it }
                FeedbackToggle(
                    "Chat log excerpt (last ~3.5 KB of recent chat)",
                    includeChatLog
                ) { includeChatLog = it }
                FeedbackToggle("User ID (for cross-checking your feedback)", includeUserId) { includeUserId = it }
                Text(
                    text = "This is pre-filled into the form — you can edit or remove anything before " +
                        "submitting. Nothing is sent until you press Submit in the form.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSubmit(includeAppInfo, includeUsageStats, includeChatLog, includeUserId)
                }
            ) {
                Text("Open form")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun FeedbackToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
