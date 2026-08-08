package com.gotcha.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gotcha.data.Settings
import com.gotcha.data.WakeWordListeningMode
import android.provider.Settings as AndroidSettings

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
    val ballEnabled = enabled
    var wakeWordEnabled by remember { mutableStateOf(initial.wakeWordEnabled) }
    var wakeWordSensitivity by remember { mutableStateOf(initial.wakeWordSensitivity) }
    var wakeWordListeningMode by remember { mutableStateOf(initial.wakeWordListeningMode) }

    // The wake word runs inside the ball service, so it cannot be on while the
    // ball is off. When the ball is switched off here (or this screen is opened
    // with the ball already off and a leftover ON), reset the wake word to off
    // in both the local state and the saved setting — not merely disable the
    // toggle, which would leave it visually on but inert.
    LaunchedEffect(ballEnabled) {
        if (!ballEnabled && wakeWordEnabled) {
            wakeWordEnabled = false
            onSave { settings -> settings.copy(wakeWordEnabled = false) }
        }
    }

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
            label = "Wake word: Hey Gotcha",
            checked = wakeWordEnabled,
            // The listener runs inside the Assistive Ball service, so the wake
            // word cannot be turned on while the ball is off. Turning the ball
            // off also switches the wake word off (see LaunchedEffect above),
            // so this toggle is simply locked to the ball state.
            enabled = ballEnabled,
            onCheckedChange = {
                wakeWordEnabled = it
                onSave { settings -> settings.copy(wakeWordEnabled = it) }
            },
            switchTestTag = "settings_wake_word",
            switchContentDescription = if (wakeWordEnabled) {
                "Turn off Hey Gotcha wake word"
            } else {
                "Turn on Hey Gotcha wake word"
            }
        )
        if (!ballEnabled) {
            Text(
                "The wake word requires the Assistive Ball to be on — turn on " +
                    "\"Show Assistive Ball\" to use it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "Says \"Hey Gotcha\" — the bundled OpenWakeWord model runs on-device while " +
                "the assistive ball is on and no call is active. Keep microphone " +
                "permission enabled.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (wakeWordEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "When to listen",
                style = MaterialTheme.typography.bodyMedium
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .testTag("settings_wake_word_mode")
            ) {
                WakeWordListeningMode.entries.forEach { mode ->
                    WakeWordModeRow(
                        mode = mode,
                        selected = wakeWordListeningMode == mode,
                        onSelect = {
                            wakeWordListeningMode = mode
                            onSave { settings -> settings.copy(wakeWordListeningMode = mode) }
                        }
                    )
                }
            }
            Text(
                "The listener is only active while the screen matches the chosen " +
                    "state, so the microphone and its indicator are off the rest of " +
                    "the time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Detection sensitivity",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    sensitivityLabel(wakeWordSensitivity),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = wakeWordSensitivity,
                // Update the local state on every drag tick but only persist once the
                // user lifts their finger — avoids a SharedPreferences write per tick.
                onValueChange = { wakeWordSensitivity = it },
                onValueChangeFinished = {
                    onSave { settings -> settings.copy(wakeWordSensitivity = wakeWordSensitivity) }
                },
                valueRange = 0f..1f,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_wake_word_sensitivity")
                    .semantics {
                        contentDescription = "Wake word sensitivity ${(wakeWordSensitivity * 100).toInt()} percent"
                    }
            )
            Text(
                "Lower values are stricter — fewer false activations in TV or music; " +
                    "higher values catch the word from further away or with weaker " +
                    "pronunciation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val context = LocalContext.current
            BatteryOptimizationRow(context)
        }
        Text(
            "Needs the \"Display over other apps\" permission — turning this on the " +
                "first time takes you there to grant it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Labels mirror the model card's three recommended thresholds (0.65 / 0.50 /
// 0.35). Split points on the slider are the inverse of
// `threshold = 0.70 - 0.27 * sensitivity`:
//   threshold 0.65 → sensitivity ~0.185 (high-precision end)
//   threshold 0.50 → sensitivity ~0.741 (balanced, also the default)
private fun sensitivityLabel(sensitivity: Float): String = when {
    sensitivity < 0.185f -> "High precision"
    sensitivity < 0.741f -> "Balanced"
    else -> "High sensitivity"
}

/** One selectable row of the "When to listen" wake-word mode group. */
@Composable
private fun WakeWordModeRow(
    mode: WakeWordListeningMode,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val label: String
    val summary: String
    when (mode) {
        WakeWordListeningMode.ALWAYS -> {
            label = "Always"
            summary = "Listen with the screen on or off. Full hands-free, highest battery use."
        }
        WakeWordListeningMode.SCREEN_ON -> {
            label = "Only while the screen is on"
            summary = "Saves battery — the microphone and its indicator are off while the screen is off."
        }
        WakeWordListeningMode.SCREEN_OFF -> {
            label = "Only while the screen is off"
            summary = "Hands-free when you are not already looking at the phone; the screen being on means the mic is off."
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect
            )
            .testTag("settings_wake_word_mode_${mode.name.lowercase()}")
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

@Composable
private fun BatteryOptimizationRow(context: Context) {
    val exempt = remember(context) {
        val pm = context.getSystemService(PowerManager::class.java)
        runCatching { pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false }
            .getOrDefault(false)
    }
    Spacer(modifier = Modifier.height(8.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !exempt) {
                runCatching {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            .testTag("settings_wake_word_battery")
    ) {
        Text(
            if (exempt) "Background restriction: lifted" else "Background restriction",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            if (exempt) {
                "Gotcha is whitelisted from battery optimization. The wake-word listener " +
                    "will keep running while the ball is on."
            } else {
                "OEM battery managers may kill the wake-word listener. Tap to allow Gotcha " +
                    "to run unrestricted in the background — required for reliable " +
                    "always-on listening."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
