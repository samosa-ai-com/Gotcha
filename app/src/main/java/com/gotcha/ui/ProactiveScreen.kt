package com.gotcha.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.gotcha.data.Settings

/**
 * The Proactive Assistance page: whether the assistant volunteers help, and
 * which surfaces it may scan for the context to do so.
 *
 * Language and currency used to live here; they moved to
 * [PersonalInfoScreen] — they describe the user rather than this feature.
 */
@Composable
fun ProactiveScreen(
    load: () -> Settings,
    onSave: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit
) {
    val initial = remember { load() }
    var proactiveEnabled by remember { mutableStateOf(initial.proactiveEnabled) }
    var proactiveScanScreen by remember { mutableStateOf(initial.proactiveScanScreen) }
    var proactiveScanClipboard by remember { mutableStateOf(initial.proactiveScanClipboard) }
    var proactiveScanNotifications by remember { mutableStateOf(initial.proactiveScanNotifications) }
    var proactiveOtpEnabled by remember { mutableStateOf(initial.proactiveOtpEnabled) }
    var proactiveAutoCopyOtp by remember { mutableStateOf(initial.proactiveAutoCopyOtp) }

    val overlay = rememberSettingsOverlayState()

    /** This page's fields, copied onto [base]. */
    fun applyProactive(base: Settings) = base.copy(
        proactiveEnabled = proactiveEnabled,
        proactiveScanScreen = proactiveScanScreen,
        proactiveScanClipboard = proactiveScanClipboard,
        proactiveScanNotifications = proactiveScanNotifications,
        proactiveOtpEnabled = proactiveOtpEnabled,
        proactiveAutoCopyOtp = proactiveAutoCopyOtp
    )

    SettingsScaffold(title = SettingsPage.PROACTIVE.title, onBack = onBack, overlay = overlay) {
        SettingsToggleRow(
            label = "Master Proactive Offers",
            checked = proactiveEnabled,
            onCheckedChange = { proactiveEnabled = it },
            isLarge = true
        )
        if (proactiveEnabled) {
            SettingsToggleRow(
                label = "Scan Screen Content",
                checked = proactiveScanScreen,
                onCheckedChange = { proactiveScanScreen = it }
            )
            SettingsToggleRow(
                label = "Scan Clipboard",
                checked = proactiveScanClipboard,
                onCheckedChange = { proactiveScanClipboard = it }
            )
            SettingsToggleRow(
                label = "Scan Notifications",
                checked = proactiveScanNotifications,
                onCheckedChange = { proactiveScanNotifications = it }
            )
            SettingsToggleRow(
                label = "Detect OTP / Codes",
                checked = proactiveOtpEnabled,
                onCheckedChange = { proactiveOtpEnabled = it }
            )
            SettingsToggleRow(
                label = "Auto-Copy OTP to Clipboard",
                checked = proactiveAutoCopyOtp,
                onCheckedChange = { proactiveAutoCopyOtp = it }
            )
        }
        Button(
            onClick = {
                onSave { applyProactive(it) }
                overlay.show("Saved Proactive Settings.")
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save Proactive Settings") }
    }
}
