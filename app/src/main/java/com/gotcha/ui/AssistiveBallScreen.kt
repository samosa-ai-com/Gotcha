package com.gotcha.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gotcha.R
import com.gotcha.data.Settings
import com.gotcha.data.WakeWordListeningMode
import com.gotcha.service.DisplayTintGuard
import android.provider.Settings as AndroidSettings

/**
 * The Assistive Ball and Wake Word page: a switch that starts or stops the
 * floating overlay, the short version of what the ball does once it is on, and
 * the "Hey Gotcha" wake word — which lives here because its listener runs inside
 * the ball's service and cannot outlive it.
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
    var pauseNightLight by remember { mutableStateOf(initial.pauseNightLightForScreenshots) }

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

    SettingsScaffold(title = stringResource(SettingsPage.ASSISTIVE_BALL.title), onBack = onBack, overlay = overlay) {
        SettingsToggleRow(
            label = stringResource(R.string.assistive_ball_show_assistive_ball),
            checked = enabled,
            onCheckedChange = onToggle,
            isLarge = true,
            switchTestTag = "settings_assistive_ball",
            switchContentDescription = stringResource(
                if (enabled) {
                    R.string.assistive_ball_turn_off_assistive_ball
                } else {
                    R.string.assistive_ball_turn_on_assistive_ball
                }
            )
        )
        Text(
            stringResource(R.string.assistive_ball_a_draggable_ball_floating_over),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SettingsToggleRow(
            label = stringResource(R.string.assistive_ball_night_light_off_for_screenshots),
            checked = pauseNightLight,
            onCheckedChange = {
                pauseNightLight = it
                onSave { settings -> settings.copy(pauseNightLightForScreenshots = it) }
            },
            switchTestTag = "settings_pause_night_light",
            switchContentDescription = stringResource(
                if (pauseNightLight) {
                    R.string.assistive_ball_stop_turning_night_light_off
                } else {
                    R.string.assistive_ball_turn_night_light_off_for
                }
            )
        )
        NightLightGrantHint()
        SettingsToggleRow(
            label = stringResource(R.string.assistive_ball_wake_word_hey_gotcha),
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
            switchContentDescription = stringResource(
                if (wakeWordEnabled) {
                    R.string.assistive_ball_turn_off_hey_gotcha_wake
                } else {
                    R.string.assistive_ball_turn_on_hey_gotcha_wake
                }
            )
        )
        // Stated whether or not the ball is on: with the ball on, the toggle
        // works and nothing else would explain why it stops later.
        Text(
            stringResource(R.string.assistive_ball_says_hey_gotcha_the_bundled),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!ballEnabled) {
            Text(
                stringResource(R.string.assistive_ball_turn_on_show_assistive_ball),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (wakeWordEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.assistive_ball_when_to_listen),
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
                stringResource(R.string.assistive_ball_the_listener_is_only_active),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.assistive_ball_detection_sensitivity),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(sensitivityLabel(wakeWordSensitivity)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val sensitivityDescription = stringResource(
                R.string.assistive_ball_sensitivity_description,
                (wakeWordSensitivity * 100).toInt()
            )
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
                    .settingsField("settings_wake_word_sensitivity")
                    .semantics {
                        contentDescription = sensitivityDescription
                    }
            )
            Text(
                stringResource(R.string.assistive_ball_lower_values_are_stricter_fewer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val context = LocalContext.current
            BatteryOptimizationRow(context)
        }
        Text(
            stringResource(R.string.assistive_ball_needs_the_display_over_other),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * What "Night Light off for screenshots" does, and how to let it. The grant is
 * an adb command because no Settings screen can hand out WRITE_SECURE_SETTINGS;
 * tapping the command copies it. Root works too, so the switch is never locked.
 */
@Composable
private fun NightLightGrantHint() {
    val context = LocalContext.current
    val granted = remember(context) { DisplayTintGuard.hasSecureSettingsGrant(context) }
    Text(
        stringResource(R.string.assistive_ball_some_phones_keep_the_night),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (granted) {
        Text(
            stringResource(R.string.assistive_ball_gotcha_has_permission_to_switch),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Text(
            stringResource(R.string.assistive_ball_needs_a_one_time_grant),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            DisplayTintGuard.GRANT_COMMAND,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("adb command", DisplayTintGuard.GRANT_COMMAND))
                }
                .padding(vertical = 4.dp)
                .testTag("settings_pause_night_light_command")
        )
    }
}

// Labels mirror the model card's three recommended thresholds (0.65 / 0.50 /
// 0.35). Split points on the slider are the inverse of
// `threshold = 0.70 - 0.27 * sensitivity`:
//   threshold 0.65 → sensitivity ~0.185 (high-precision end)
//   threshold 0.50 → sensitivity ~0.741 (balanced, also the default)
@StringRes
private fun sensitivityLabel(sensitivity: Float): Int = when {
    sensitivity < 0.185f -> R.string.assistive_ball_sensitivity_high_precision
    sensitivity < 0.741f -> R.string.assistive_ball_sensitivity_balanced
    else -> R.string.assistive_ball_sensitivity_high
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
            label = stringResource(R.string.assistive_ball_always)
            summary = stringResource(R.string.assistive_ball_listen_with_the_screen_on)
        }
        WakeWordListeningMode.SCREEN_ON -> {
            label = stringResource(R.string.assistive_ball_only_while_the_screen_is)
            summary = stringResource(R.string.assistive_ball_saves_battery_the_microphone_and)
        }
        WakeWordListeningMode.SCREEN_OFF -> {
            label = stringResource(R.string.assistive_ball_only_while_the_screen_is_2)
            summary = stringResource(R.string.assistive_ball_hands_free_when_you_are)
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
            stringResource(
                if (exempt) R.string.assistive_ball_background_restriction_lifted else R.string.assistive_ball_background_restriction
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            stringResource(
                if (exempt) {
                    R.string.assistive_ball_gotcha_is_whitelisted_from_battery
                } else {
                    R.string.assistive_ball_oem_battery_managers_may_kill
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
