package com.gotcha.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.gotcha.BuildConfig
import com.gotcha.audio.CompletionFeedback
import com.gotcha.data.CompletionPreview
import com.gotcha.data.Settings
import com.gotcha.notifications.ChatCompletionNotifier
import com.gotcha.notifications.DailyTipScheduler
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import android.provider.Settings as AndroidSettings

/**
 * The Notifications page: what the phone does the moment a reply arrives
 * (vibration/chime), the task-finished and daily tip notifications, and
 * server-driven messages from the Samosa team (updates, tips, maintenance).
 *
 * The vibration/chime page has no Save button, and deliberately so —
 * switching one on plays it once, which is the whole point. A preview that
 * had to be saved first would be feedback about a setting the user had
 * already committed to.
 */
@Composable
fun NotificationsScreen(
    load: () -> Settings,
    onSave: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
    onSyncServerMessages: suspend () -> Long? = { null }
) {
    val initial = remember { load() }
    var notifyVibration by remember { mutableStateOf(initial.notifyVibrationEnabled) }
    var notifyChime by remember { mutableStateOf(initial.notifyChimeEnabled) }
    var taskFinished by remember { mutableStateOf(initial.chatCompletionNotificationsEnabled) }
    var taskPreview by remember { mutableStateOf(initial.chatCompletionPreview) }
    var dailyTips by remember { mutableStateOf(initial.dailyTipsEnabled) }
    var dailyTipMinute by remember { mutableStateOf(initial.dailyTipMinuteOfDay) }
    var serverMessagesEnabled by remember { mutableStateOf(initial.serverMessagesEnabled) }
    var lastFetched by remember { mutableStateOf(initial.serverMessagesLastFetchedAt) }
    var isSyncing by remember { mutableStateOf(false) }

    val overlay = rememberSettingsOverlayState()
    val localContext = LocalContext.current
    // Re-read after the permission prompt, so the "blocked" note clears on a grant.
    var canPost by remember { mutableStateOf(ChatCompletionNotifier(localContext).canPost()) }
    val scope = rememberCoroutineScope()

    // Asked for when server messages are switched on, not at first launch —
    // a notification permission is only meaningful once something wants to post.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { canPost = ChatCompletionNotifier(localContext).canPost() }

    // Sync only when the user toggles server messages ON, not on the
    // initial composition — `onResume` already covers the first-arrival case.
    var skipInitialServerMessagesSync by remember { mutableStateOf(true) }
    LaunchedEffect(serverMessagesEnabled) {
        if (skipInitialServerMessagesSync) {
            skipInitialServerMessagesSync = false
            return@LaunchedEffect
        }
        if (serverMessagesEnabled) onSyncServerMessages()
    }

    SettingsScaffold(title = SettingsPage.NOTIFICATIONS.title, onBack = onBack, overlay = overlay) {
        Text(
            "Played as soon as a reply arrives. Turn both off for no alert.",
            style = MaterialTheme.typography.bodySmall
        )
        SettingsToggleRow(
            label = "Vibration",
            checked = notifyVibration,
            onCheckedChange = {
                notifyVibration = it
                onSave { s -> s.copy(notifyVibrationEnabled = it) }
                if (it) CompletionFeedback.replyArrived(localContext, vibrate = true, chime = false)
            },
            isLarge = true,
            switchTestTag = "settings_notify_vibration"
        )
        SettingsToggleRow(
            label = "Chime",
            checked = notifyChime,
            onCheckedChange = {
                notifyChime = it
                onSave { s -> s.copy(notifyChimeEnabled = it) }
                if (it) CompletionFeedback.replyArrived(localContext, vibrate = false, chime = true)
            },
            isLarge = true,
            switchTestTag = "settings_notify_chime"
        )

        Spacer(Modifier.height(16.dp))
        Text(
            "Task finished",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            "A notification when a chat task finishes while you are in another app. " +
                "Tap it to open that chat. On the lock screen it only says a task finished.",
            style = MaterialTheme.typography.bodySmall
        )
        SettingsToggleRow(
            label = "Notify when a task finishes",
            checked = taskFinished,
            onCheckedChange = {
                taskFinished = it
                onSave { s -> s.copy(chatCompletionNotificationsEnabled = it) }
                if (it && !canPost && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            isLarge = true,
            switchTestTag = "settings_task_finished_enabled"
        )
        if (taskFinished) {
            Text("Show the reply", style = MaterialTheme.typography.bodyMedium)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .testTag("settings_task_finished_preview")
            ) {
                CompletionPreview.entries.forEach { preview ->
                    CompletionPreviewRow(
                        preview = preview,
                        selected = taskPreview == preview,
                        onSelect = {
                            taskPreview = preview
                            onSave { s -> s.copy(chatCompletionPreview = preview) }
                        }
                    )
                }
            }
            if (!canPost) {
                Text(
                    "Notifications are blocked for Gotcha, so none will be shown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(
                    onClick = {
                        localContext.startActivity(
                            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, localContext.packageName)
                        )
                    }
                ) { Text("Open notification settings") }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Daily tips",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            "One thing to try with Gotcha each day, picked from what you haven't used yet. " +
                "Tap it to start a chat with the prompt ready to edit or send. " +
                "Skipped on days you've already used Gotcha.",
            style = MaterialTheme.typography.bodySmall
        )
        SettingsToggleRow(
            label = "Send a daily tip",
            checked = dailyTips,
            onCheckedChange = {
                dailyTips = it
                onSave { s -> s.copy(dailyTipsEnabled = it) }
                if (it && !canPost && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            isLarge = true,
            switchTestTag = "settings_daily_tips_enabled"
        )
        if (dailyTips) {
            TextButton(
                onClick = {
                    TimePickerDialog(
                        localContext,
                        { _, hour, minute ->
                            val picked = hour * 60 + minute
                            dailyTipMinute = picked
                            onSave { s -> s.copy(dailyTipMinuteOfDay = picked) }
                            DailyTipScheduler.schedule(localContext, picked)
                        },
                        dailyTipMinute / 60,
                        dailyTipMinute % 60,
                        DateFormat.is24HourFormat(localContext)
                    ).show()
                },
                modifier = Modifier.testTag("settings_daily_tip_time")
            ) {
                Text("Time: ${LocalTime.of(dailyTipMinute / 60, dailyTipMinute % 60).format(TIME_FORMAT)}")
            }
            if (!canPost) {
                Text(
                    "Notifications are blocked for Gotcha, so no tips will be shown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Server messages",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            "Updates, tips and maintenance notices from Gotcha, fetched from " +
                "${BuildConfig.SAMOSA_API_URL.removePrefix("https://")}. " +
                "Each message shows at most the number of times the server asks; re-deliveries are suppressed automatically.",
            style = MaterialTheme.typography.bodySmall
        )
        SettingsToggleRow(
            label = "Enable server messages",
            checked = serverMessagesEnabled,
            onCheckedChange = {
                serverMessagesEnabled = it
                onSave { s -> s.copy(serverMessagesEnabled = it) }
                // The one moment the permission is actually needed: a message
                // that can't be posted is a setting that silently does nothing.
                if (it && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        localContext,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            isLarge = true,
            switchTestTag = "settings_server_messages_enabled"
        )
        Button(
            onClick = {
                isSyncing = true
                scope.launch {
                    try {
                        val ts = onSyncServerMessages()
                        if (ts != null) lastFetched = ts
                    } finally {
                        isSyncing = false
                    }
                }
            },
            enabled = !isSyncing && serverMessagesEnabled,
            modifier = Modifier.fillMaxWidth().testTag("settings_server_messages_sync")
        ) { Text(if (isSyncing) "Syncing…" else "Sync now") }
        Text(
            "Last synced: ${formatRelative(lastFetched)}",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** One selectable row of the "Show the reply" group. */
@Composable
private fun CompletionPreviewRow(
    preview: CompletionPreview,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val (label, summary) = when (preview) {
        CompletionPreview.NONE -> "Don't show it" to "Only the chat's name and whether the task finished."
        CompletionPreview.SHORT -> "A short preview" to "The first line or two of the reply."
        CompletionPreview.FULL -> "The whole reply" to "Expand the notification to read all of it."
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag("settings_task_finished_preview_${preview.name.lowercase()}")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 40.dp, bottom = 4.dp)
        )
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private fun formatRelative(epochMillis: Long): String {
    if (epochMillis <= 0L) return "never"
    val deltaMin = (System.currentTimeMillis() - epochMillis) / 60_000L
    return when {
        deltaMin < 1L -> "just now"
        deltaMin < 60L -> "${deltaMin}m ago"
        deltaMin < 24L * 60L -> "${deltaMin / 60L}h ago"
        else -> "${deltaMin / (24L * 60L)}d ago"
    }
}
