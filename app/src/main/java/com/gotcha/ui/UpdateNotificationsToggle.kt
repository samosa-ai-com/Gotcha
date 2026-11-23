package com.gotcha.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.gotcha.R
import com.gotcha.data.Settings
import com.gotcha.notifications.ChatCompletionNotifier

/**
 * "Notify me about new versions", on About beside Check for updates.
 *
 * It used to end the Notifications page, five screens down and away from the
 * version it is about. Turning it on asks for the notification permission
 * (Android 13+) if Gotcha doesn't have it yet, and says so if it is blocked.
 */
@Composable
fun UpdateNotificationsToggle(
    load: () -> Settings,
    onSave: ((Settings) -> Settings) -> Unit
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(load().updateNotificationsEnabled) }
    // Re-read after the permission prompt, so the "blocked" note clears on a grant.
    var canPost by remember { mutableStateOf(ChatCompletionNotifier(context).canPost()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { canPost = ChatCompletionNotifier(context).canPost() }

    Text(
        stringResource(R.string.notifications_app_updates_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SettingsToggleRow(
        label = stringResource(R.string.notifications_notify_about_new_versions),
        checked = enabled,
        onCheckedChange = { on ->
            enabled = on
            onSave { it.copy(updateNotificationsEnabled = on) }
            if (on && !canPost && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        switchTestTag = "settings_update_notifications_enabled"
    )
    if (enabled && !canPost) {
        Text(
            stringResource(R.string.notifications_notifications_are_blocked_for_gotcha),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}
