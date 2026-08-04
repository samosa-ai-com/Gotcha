package com.gotcha.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.gotcha.data.Settings

/**
 * The Assistive Ball page: one switch that starts or stops the floating overlay,
 * plus the short version of what the ball does once it is on.
 *
 * The switch is not a stored preference the page owns — it drives the
 * [com.gotcha.service.AssistiveBallService] through the host, which persists
 * `assistiveBallEnabled` itself and may refuse (no overlay permission yet, in
 * which case the host deep-links the user to grant it and the switch stays off).
 * So state is hoisted: [enabled] is the service's real state, and toggling only
 * asks for a change.
 */
@Composable
fun AssistiveBallScreen(
    load: () -> Settings,
    onSave: ((Settings) -> Settings) -> Unit,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val overlay = rememberSettingsOverlayState()
    val initial = remember { load() }
    var wakeWordEnabled by remember { mutableStateOf(initial.wakeWordEnabled) }

    SettingsScaffold(title = SettingsPage.ASSISTIVE_BALL.title, onBack = onBack, overlay = overlay) {
        SettingsToggleRow(
            label = "Show Assistive Ball",
            checked = enabled,
            onCheckedChange = onToggle,
            isLarge = true,
            switchTestTag = "settings_assistive_ball",
            switchContentDescription = if (enabled) {
                "Turn off assistive ball"
            } else {
                "Turn on assistive ball"
            }
        )
        Text(
            "A draggable ball floating over other apps. Long-press it to start a " +
                "hands-free voice call with the assistant; tap it for Start / Pause / " +
                "End and to open the app. Drag it onto the ✕ to hide it again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SettingsToggleRow(
            label = "Wake word: Gotcha",
            checked = wakeWordEnabled,
            onCheckedChange = {
                wakeWordEnabled = it
                onSave { settings -> settings.copy(wakeWordEnabled = it) }
            },
            switchTestTag = "settings_wake_word",
            switchContentDescription = if (wakeWordEnabled) {
                "Turn off Gotcha wake word"
            } else {
                "Turn on Gotcha wake word"
            }
        )
        Text(
            "Uses the bundled Vosk model on-device while the assistive ball is on " +
                "and no call is active. Keep microphone permission enabled.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Needs the \"Display over other apps\" permission — turning this on the " +
                "first time takes you there to grant it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
