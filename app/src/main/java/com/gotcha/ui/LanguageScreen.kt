package com.gotcha.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.launch
import android.provider.Settings as AndroidSettings

/** Voice-language dropdown entry meaning "no explicit choice — follow the reply language". */
private const val FOLLOW_REPLY_LANGUAGE = "Same as AI reply language"

/**
 * The Language page: the three language choices, told apart.
 *
 * They used to be scattered and ambiguously named — "Preferred Language" sat in
 * Personal Info reading like an app-UI setting when it actually drove the LLM
 * *and* the voice, while "STT Language" sat on the Speech page with no statement
 * of what it overrode (issue #74). Gathering them here is the only place a user
 * can see all three at once, which is what makes the difference between them
 * legible:
 *
 *  1. **App display language** — Android's, not ours. The interface is English
 *     only, so this section is honest about that and hands the user off to the
 *     system screen rather than pretending to a picker that would do nothing.
 *  2. **Voice language** — what TTS speaks and STT listens in
 *     ([Settings.effectiveVoiceLanguage]). Blank follows the reply language.
 *  3. **AI reply language** — what the model writes in
 *     ([Settings.preferredLanguage]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageScreen(
    load: () -> Settings,
    onSave: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
    onTestVoice: suspend (Language) -> Boolean? = { null }
) {
    val initial = remember { load() }
    var preferredLanguage by rememberSaveable { mutableStateOf(initial.preferredLanguage) }
    var voiceLanguage by rememberSaveable { mutableStateOf(initial.voiceLanguage) }

    var replyExpanded by rememberSaveable { mutableStateOf(false) }
    var voiceExpanded by rememberSaveable { mutableStateOf(false) }
    var testingVoice by remember { mutableStateOf(false) }

    /** Last [Language] whose voice data was reported missing, or null when not shown. */
    var voiceDataMissing by remember { mutableStateOf<Language?>(null) }

    val overlay = rememberSettingsOverlayState()
    val scope = rememberCoroutineScope()
    val localContext = LocalContext.current

    /** This page's fields, copied onto [base]. */
    fun applyLanguages(base: Settings) = base.copy(
        preferredLanguage = preferredLanguage,
        voiceLanguage = voiceLanguage
    )

    /** What the voice would actually use right now, without waiting for a save. */
    val resolvedVoiceLanguage = Language.fromLabel(voiceLanguage.ifBlank { preferredLanguage })

    SettingsScaffold(title = SettingsPage.LANGUAGE.title, onBack = onBack, overlay = overlay) {
        Text(
            "Three separate choices: what the app's own screens are written in, " +
                "what Gotcha speaks and hears, and what it writes its answers in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider(thickness = 1.dp)

        // ---- 1. App display language ----
        Text(
            "App display language",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Gotcha's own buttons, labels and settings are in English only for now — " +
                "changing this affects the rest of your phone, not this app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = { openLanguageSettings(localContext) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_open_app_locale")
        ) { Text("Open Android language settings") }

        HorizontalDivider(thickness = 1.dp)

        // ---- 2. Voice language ----
        Text(
            "Voice language",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "What Gotcha speaks aloud and listens for during voice input and calls. " +
                "Leave it following the reply language unless you want answers written " +
                "in one language and spoken in another.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ExposedDropdownMenuBox(
            expanded = voiceExpanded,
            onExpandedChange = { voiceExpanded = it }
        ) {
            OutlinedTextField(
                value = voiceLanguage.ifBlank { FOLLOW_REPLY_LANGUAGE },
                onValueChange = {},
                readOnly = true,
                label = { Text("Voice language") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
                    .testTag("settings_voice_language")
            )
            SkinExposedDropdownMenu(
                expanded = voiceExpanded,
                onDismissRequest = { voiceExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(FOLLOW_REPLY_LANGUAGE) },
                    onClick = {
                        voiceLanguage = ""
                        voiceExpanded = false
                    }
                )
                Language.labels.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(lang) },
                        onClick = {
                            voiceLanguage = lang
                            voiceExpanded = false
                        }
                    )
                }
            }
        }
        Text(
            "Which voice reads it out is picked per model under Settings → AI → " +
                "Speech, and the transcription language override on that page wins " +
                "over this one when it is set.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = {
                testingVoice = true
                scope.launch {
                    // Track which language triggered the missing-data state so
                    // rapid language-switch clicks don't surface a stale dialog.
                    val lang = resolvedVoiceLanguage
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
                                    Intent(
                                        android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (_: Exception) {
                                Toast.makeText(
                                    localContext,
                                    "Could not open text-to-speech settings.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    ) { Text("Install") }
                },
                dismissButton = {
                    TextButton(onClick = { voiceDataMissing = null }) { Text("Cancel") }
                }
            )
        }

        HorizontalDivider(thickness = 1.dp)

        // ---- 3. AI reply language ----
        Text(
            "AI reply language",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "The language the assistant writes its answers in. Tool names, commands " +
                "and file paths stay in English so they keep working.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ExposedDropdownMenuBox(
            expanded = replyExpanded,
            onExpandedChange = { replyExpanded = it }
        ) {
            OutlinedTextField(
                value = preferredLanguage,
                onValueChange = {},
                readOnly = true,
                label = { Text("AI reply language") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = replyExpanded)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
                    .testTag("settings_reply_language")
            )
            SkinExposedDropdownMenu(
                expanded = replyExpanded,
                onDismissRequest = { replyExpanded = false }
            ) {
                Language.labels.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(lang) },
                        onClick = {
                            preferredLanguage = lang
                            replyExpanded = false
                        }
                    )
                }
            }
        }

        Button(
            onClick = {
                onSave { applyLanguages(it) }
                overlay.show("Saved language settings.")
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_save_language")
        ) { Text("Save Language Settings") }
    }
}

/**
 * Open the per-app language screen where the platform has one (API 33+), falling
 * back to the device-wide language list. Both are ordinary system activities that
 * a heavily-skinned OEM build may simply not have; if neither activity resolves,
 * a toast informs the user.
 */
internal fun openLanguageSettings(context: Context) {
    val intents = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                Intent(AndroidSettings.ACTION_APP_LOCALE_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
            )
        }
        add(Intent(AndroidSettings.ACTION_LOCALE_SETTINGS))
    }
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) { }
    }
    Toast.makeText(
        context,
        "Could not open language settings.",
        Toast.LENGTH_SHORT
    ).show()
}
