package com.gotcha.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.gotcha.audio.CompletionFeedback
import com.gotcha.data.Settings

/**
 * The Notifications page: what the phone does the moment a reply arrives.
 *
 * The only page with no Save button, and deliberately so — switching one on
 * plays it once, which is the whole point. A preview that had to be saved first
 * would be feedback about a setting the user had already committed to.
 */
@Composable
fun NotificationsScreen(
    load: () -> Settings,
    onSave: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit
) {
    val initial = remember { load() }
    var notifyVibration by remember { mutableStateOf(initial.notifyVibrationEnabled) }
    var notifyChime by remember { mutableStateOf(initial.notifyChimeEnabled) }

    val overlay = rememberSettingsOverlayState()
    val localContext = LocalContext.current

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
    }
}
