package com.gotcha.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gotcha.BuildConfig
import com.gotcha.audio.CompletionFeedback
import com.gotcha.data.Settings
import kotlinx.coroutines.launch

/**
 * The Notifications page: what the phone does the moment a reply arrives
 * (vibration/chime) plus server-driven messages from the Samosa team
 * (updates, tips, maintenance).
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
    var serverMessagesEnabled by remember { mutableStateOf(initial.serverMessagesEnabled) }
    var lastFetched by remember { mutableStateOf(initial.serverMessagesLastFetchedAt) }
    var isSyncing by remember { mutableStateOf(false) }

    val overlay = rememberSettingsOverlayState()
    val localContext = LocalContext.current
    val scope = rememberCoroutineScope()

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
