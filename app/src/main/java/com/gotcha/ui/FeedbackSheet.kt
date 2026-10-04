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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gotcha.R
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
        title = { Text(stringResource(R.string.feedback_send_feedback)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.feedback_if_you_provide_successful_feedback),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                FeedbackToggle(stringResource(R.string.feedback_app_info_version_device_android), includeAppInfo) {
                    includeAppInfo = it
                }
                FeedbackToggle(stringResource(R.string.feedback_usage_stats_chats_tool_calls), includeUsageStats) {
                    includeUsageStats = it
                }
                FeedbackToggle(
                    stringResource(R.string.feedback_chat_log_excerpt_recent_chat),
                    includeChatLog
                ) { includeChatLog = it }
                FeedbackToggle(
                    stringResource(R.string.feedback_user_id_for_cross_checking),
                    includeUserId
                ) { includeUserId = it }
                Text(
                    text = stringResource(R.string.feedback_this_is_pre_filled_into),
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
                Text(stringResource(R.string.feedback_open_form))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.feedback_cancel)) }
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
