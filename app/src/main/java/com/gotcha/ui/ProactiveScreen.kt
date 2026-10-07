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
import androidx.compose.ui.res.stringResource
import com.gotcha.R
import com.gotcha.data.Settings

/**
 * The Proactive Assistance page: whether the assistant volunteers help, and
 * which surfaces it may scan for the context to do so.
 *
 * Language and currency used to live here; they describe the user rather than
 * this feature, so currency moved to [PersonalInfoScreen] and language on to
 * [LanguageScreen].
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

    SettingsScaffold(title = stringResource(SettingsPage.PROACTIVE.title), onBack = onBack, overlay = overlay) {
        SettingsToggleRow(
            label = stringResource(R.string.proactive_master_proactive_offers),
            checked = proactiveEnabled,
            onCheckedChange = { proactiveEnabled = it },
            isLarge = true,
            switchTestTag = "settings_proactive_enabled"
        )
        if (proactiveEnabled) {
            SettingsToggleRow(
                label = stringResource(R.string.proactive_scan_screen_content),
                checked = proactiveScanScreen,
                onCheckedChange = { proactiveScanScreen = it },
                switchTestTag = "settings_proactive_scan_screen"
            )
            SettingsToggleRow(
                label = stringResource(R.string.proactive_scan_clipboard),
                checked = proactiveScanClipboard,
                onCheckedChange = { proactiveScanClipboard = it },
                switchTestTag = "settings_proactive_scan_clipboard"
            )
            SettingsToggleRow(
                label = stringResource(R.string.proactive_scan_notifications),
                checked = proactiveScanNotifications,
                onCheckedChange = { proactiveScanNotifications = it }
            )
            SettingsToggleRow(
                label = stringResource(R.string.proactive_detect_otp_codes),
                checked = proactiveOtpEnabled,
                onCheckedChange = { proactiveOtpEnabled = it },
                switchTestTag = "settings_proactive_otp"
            )
            SettingsToggleRow(
                label = stringResource(R.string.proactive_auto_copy_otp_to_clipboard),
                checked = proactiveAutoCopyOtp,
                onCheckedChange = { proactiveAutoCopyOtp = it }
            )
        }
        val saved = stringResource(R.string.proactive_saved)
        Button(
            onClick = {
                onSave { applyProactive(it) }
                overlay.show(saved)
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.proactive_save_proactive_settings)) }
    }
}
