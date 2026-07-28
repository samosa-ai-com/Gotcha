package com.gotcha.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.gotcha.data.Settings
import com.gotcha.i18n.Language
import kotlinx.coroutines.launch

/**
 * The Proactive Assistance page: whether the assistant volunteers help, which
 * surfaces it may scan for context, and the language and currency it answers in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProactiveScreen(
    load: () -> Settings,
    onSave: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
    onTestVoice: suspend (Language) -> Boolean? = { null }
) {
    val initial = remember { load() }
    var proactiveEnabled by remember { mutableStateOf(initial.proactiveEnabled) }
    var proactiveScanScreen by remember { mutableStateOf(initial.proactiveScanScreen) }
    var proactiveScanClipboard by remember { mutableStateOf(initial.proactiveScanClipboard) }
    var proactiveScanNotifications by remember { mutableStateOf(initial.proactiveScanNotifications) }
    var proactiveOtpEnabled by remember { mutableStateOf(initial.proactiveOtpEnabled) }
    var proactiveAutoCopyOtp by remember { mutableStateOf(initial.proactiveAutoCopyOtp) }
    var preferredLanguage by remember { mutableStateOf(initial.preferredLanguage) }
    var preferredCurrency by remember { mutableStateOf(initial.preferredCurrency) }
    var testingVoice by remember { mutableStateOf(false) }

    /** Last [Language] whose voice data was reported missing, or null when not shown. */
    var voiceDataMissing by remember { mutableStateOf<Language?>(null) }
    var languageExpanded by remember { mutableStateOf(false) }
    var currencyExpanded by remember { mutableStateOf(false) }

    val overlay = rememberSettingsOverlayState()
    val scope = rememberCoroutineScope()
    val localContext = LocalContext.current

    /** This page's fields, copied onto [base]. */
    fun applyProactive(base: Settings) = base.copy(
        proactiveEnabled = proactiveEnabled,
        proactiveScanScreen = proactiveScanScreen,
        proactiveScanClipboard = proactiveScanClipboard,
        proactiveScanNotifications = proactiveScanNotifications,
        proactiveOtpEnabled = proactiveOtpEnabled,
        proactiveAutoCopyOtp = proactiveAutoCopyOtp,
        preferredLanguage = preferredLanguage,
        preferredCurrency = preferredCurrency
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

            val languages = Language.labels
            ExposedDropdownMenuBox(
                expanded = languageExpanded,
                onExpandedChange = { languageExpanded = it }
            ) {
                OutlinedTextField(
                    value = preferredLanguage,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Preferred Language") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = languageExpanded,
                    onDismissRequest = { languageExpanded = false }
                ) {
                    languages.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(lang) },
                            onClick = {
                                preferredLanguage = lang
                                languageExpanded = false
                            }
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    testingVoice = true
                    scope.launch {
                        val lang = Language.fromLabel(preferredLanguage)
                        // Track which language triggered the missing-data state so
                        // rapid language-switch clicks don't surface a stale dialog.
                        val ok = onTestVoice(lang)
                        voiceDataMissing = if (ok == false) lang else null
                        testingVoice = false
                    }
                },
                enabled = !testingVoice,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (testingVoice) "Playing…" else "Test voice")
            }
            voiceDataMissing?.let { missingLang ->
                AlertDialog(
                    onDismissRequest = { voiceDataMissing = null },
                    title = { Text("Voice data not installed") },
                    text = {
                        Text(
                            "Your device doesn't have Android's built-in voice for " +
                                "${missingLang.label}. It was spoken in English instead. " +
                                "Install the voice data to fix pronunciation."
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                voiceDataMissing = null
                                try {
                                    localContext.startActivity(
                                        android.content.Intent(
                                            android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                } catch (_: Exception) { }
                            }
                        ) { Text("Install") }
                    },
                    dismissButton = {
                        TextButton(onClick = { voiceDataMissing = null }) { Text("Cancel") }
                    }
                )
            }

            val currencies = listOf("USD", "EUR", "GBP", "INR", "CAD", "AUD", "JPY", "CNY")
            ExposedDropdownMenuBox(
                expanded = currencyExpanded,
                onExpandedChange = { currencyExpanded = it }
            ) {
                OutlinedTextField(
                    value = preferredCurrency,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Preferred Currency") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = currencyExpanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = currencyExpanded,
                    onDismissRequest = { currencyExpanded = false }
                ) {
                    currencies.forEach { curr ->
                        DropdownMenuItem(
                            text = { Text(curr) },
                            onClick = {
                                preferredCurrency = curr
                                currencyExpanded = false
                            }
                        )
                    }
                }
            }
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
