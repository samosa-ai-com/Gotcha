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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gotcha.data.Settings
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
    var wakeWordEnabled by remember { mutableStateOf(initial.wakeWordEnabled) }
    var wakeWordSensitivity by remember { mutableStateOf(initial.wakeWordSensitivity) }

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
        Text(
            "Says \"Hey Gotcha\" — the bundled OpenWakeWord model runs on-device while " +
                "the assistive ball is on and no call is active. Keep microphone " +
                "permission enabled.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (wakeWordEnabled) {
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
                onValueChange = {
                    wakeWordSensitivity = it
                    onSave { settings -> settings.copy(wakeWordSensitivity = it) }
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
// 0.35). Split points on the slider are derived from the inverse of
// `threshold = 0.70 - 0.27 * sensitivity`:
//   threshold 0.65 → sensitivity ~0.185 (high-precision end)
//   threshold 0.50 → sensitivity ~0.741 (balanced, also the default)
private fun sensitivityLabel(sensitivity: Float): String = when {
    sensitivity < 0.30f -> "High precision"
    sensitivity < 0.70f -> "Balanced"
    else -> "High sensitivity"
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
