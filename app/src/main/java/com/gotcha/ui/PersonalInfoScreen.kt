package com.gotcha.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gotcha.data.Settings
import com.gotcha.i18n.Language
import com.gotcha.ui.theme.SkinAlertDialog
import com.gotcha.ui.theme.SkinExposedDropdownMenu
import com.gotcha.ui.tour.TourAnchor
import com.gotcha.ui.tour.tourAnchor
import kotlinx.coroutines.launch

/** Currencies offered for [Settings.preferredCurrency]. */
private val CURRENCIES = listOf("USD", "EUR", "GBP", "INR", "CAD", "AUD", "JPY", "CNY")

/**
 * The Personal Info page: who the user is and how they want to be answered.
 *
 * Every field here reaches the model's system prompt — the facts as a
 * `<user_profile>` block, the reply-style text as a directive next to the
 * language one (see `AgentEngine`). Nothing on this page changes what the agent
 * is *allowed* to do; it only changes what it knows about the person asking.
 *
 * Language and currency live here rather than under Proactive Assistance, where
 * they started: they describe the user, not whether the assistant volunteers
 * help, and they apply to every reply whether proactive or not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalInfoScreen(
    load: () -> Settings,
    onSave: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
    onTestVoice: suspend (Language) -> Boolean? = { null }
) {
    val initial = remember { load() }
    var userName by remember { mutableStateOf(initial.userName) }
    var userLocation by remember { mutableStateOf(initial.userLocation) }
    var userOccupation by remember { mutableStateOf(initial.userOccupation) }
    var userBackground by remember { mutableStateOf(initial.userBackground) }
    var userResponseStyle by remember { mutableStateOf(initial.userResponseStyle) }
    var preferredLanguage by remember { mutableStateOf(initial.preferredLanguage) }
    var preferredCurrency by remember { mutableStateOf(initial.preferredCurrency) }

    var languageExpanded by remember { mutableStateOf(false) }
    var currencyExpanded by remember { mutableStateOf(false) }
    var testingVoice by remember { mutableStateOf(false) }

    /** Last [Language] whose voice data was reported missing, or null when not shown. */
    var voiceDataMissing by remember { mutableStateOf<Language?>(null) }

    val overlay = rememberSettingsOverlayState()
    val scope = rememberCoroutineScope()
    val localContext = LocalContext.current

    /** This page's fields, copied onto [base]. */
    fun applyPersonalInfo(base: Settings) = base.copy(
        userName = userName.trim(),
        userLocation = userLocation.trim(),
        userOccupation = userOccupation.trim(),
        userBackground = userBackground.trim(),
        userResponseStyle = userResponseStyle.trim(),
        preferredLanguage = preferredLanguage,
        preferredCurrency = preferredCurrency
    )

    SettingsScaffold(title = SettingsPage.PERSONAL_INFO.title, onBack = onBack, overlay = overlay) {
        Text(
            "About you",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Everything here is optional, stays on this device, and is given to the " +
                "assistant at the start of every conversation so it doesn't have to ask.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = userName,
            onValueChange = { userName = it },
            label = { Text("Name") },
            placeholder = { Text("What the assistant should call you") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_user_name")
                .tourAnchor(TourAnchor.PERSONAL_NAME)
        )
        OutlinedTextField(
            value = userLocation,
            onValueChange = { userLocation = it },
            label = { Text("Location") },
            placeholder = { Text("e.g. Munich, Germany") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_user_location")
        )
        OutlinedTextField(
            value = userOccupation,
            onValueChange = { userOccupation = it },
            label = { Text("Occupation") },
            placeholder = { Text("e.g. Backend engineer") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_user_occupation")
        )
        OutlinedTextField(
            value = userBackground,
            onValueChange = { userBackground = it },
            label = { Text("Background") },
            placeholder = {
                Text(
                    "Anything worth knowing by default — tools you use, who you " +
                        "work with, what you usually ask for"
                )
            },
            minLines = 3,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_user_background")
        )

        HorizontalDivider(thickness = 1.dp)

        // ---- Output preferences ----
        Text(
            "How replies should be written",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        OutlinedTextField(
            value = userResponseStyle,
            onValueChange = { userResponseStyle = it },
            label = { Text("Reply style") },
            placeholder = {
                Text(
                    "e.g. Keep it to a few sentences, no bullet lists, show the " +
                        "command you ran"
                )
            },
            minLines = 3,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_user_response_style")
        )

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
            SkinExposedDropdownMenu(
                expanded = languageExpanded,
                onDismissRequest = { languageExpanded = false }
            ) {
                Language.labels.forEach { lang ->
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
            SkinAlertDialog(
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
            SkinExposedDropdownMenu(
                expanded = currencyExpanded,
                onDismissRequest = { currencyExpanded = false }
            ) {
                CURRENCIES.forEach { curr ->
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

        Button(
            onClick = {
                onSave { applyPersonalInfo(it) }
                overlay.show("Saved Personal Info.")
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_save_personal_info")
                .tourAnchor(TourAnchor.PERSONAL_SAVE)
        ) { Text("Save Personal Info") }
    }
}
