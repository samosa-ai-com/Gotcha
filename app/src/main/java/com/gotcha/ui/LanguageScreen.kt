package com.gotcha.ui

import android.app.LocaleConfig
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
import androidx.compose.runtime.LaunchedEffect
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
import com.gotcha.audio.AudioModel
import com.gotcha.audio.AudioProvider
import com.gotcha.audio.SpeechLanguageCheck
import com.gotcha.data.Settings
import com.gotcha.i18n.Language
import com.gotcha.ui.theme.SkinAlertDialog
import com.gotcha.ui.theme.SkinExposedDropdownMenu
import kotlinx.coroutines.launch
import android.provider.Settings as AndroidSettings

/** Voice-language dropdown entry meaning "no explicit choice — follow the reply language". */
private const val FOLLOW_REPLY_LANGUAGE = "Same as AI reply language"

/**
 * The Language page: every language choice, told apart, and the only place any
 * of them is set (issue #114).
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
 *     Under it, the **transcription language override** ([Settings.sttLanguage]),
 *     which wins over the voice language for API speech-to-text. It moved here
 *     from the Speech page so the two can't disagree out of sight.
 *  3. **AI reply language** — what the model writes in
 *     ([Settings.preferredLanguage]).
 *
 * The voice section also warns when the voice language doesn't suit the speech
 * models picked on the Speech page ([SpeechLanguageCheck], issue #113), which
 * is why the page fetches the audio models when a speech provider is API based.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageScreen(
    load: () -> Settings,
    onSave: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
    onTestVoice: suspend (Language) -> Boolean? = { null },
    onRefreshAudioModels: suspend (Settings) -> Pair<List<AudioModel>, List<AudioModel>> = {
        Pair(emptyList(), emptyList())
    }
) {
    val initial = remember { load() }
    var preferredLanguage by rememberSaveable { mutableStateOf(initial.preferredLanguage) }
    var voiceLanguage by rememberSaveable { mutableStateOf(initial.voiceLanguage) }
    var sttLanguage by rememberSaveable { mutableStateOf(initial.sttLanguage) }

    var replyExpanded by rememberSaveable { mutableStateOf(false) }
    var voiceExpanded by rememberSaveable { mutableStateOf(false) }
    var sttLanguageExpanded by rememberSaveable { mutableStateOf(false) }

    // The speech models, for the transcription codes and the mismatch warnings.
    // Only API providers have models to fetch.
    var ttsModels by remember { mutableStateOf<List<AudioModel>>(emptyList()) }
    var sttModels by remember { mutableStateOf<List<AudioModel>>(emptyList()) }
    LaunchedEffect(Unit) {
        if (initial.ttsProvider.isApiBased() || initial.sttProvider.isApiBased()) {
            val (tts, stt) = onRefreshAudioModels(initial)
            ttsModels = tts
            sttModels = stt
        }
    }
    var testingVoice by remember { mutableStateOf(false) }

    /** Last [Language] whose voice data was reported missing, or null when not shown. */
    var voiceDataMissing by remember { mutableStateOf<Language?>(null) }

    val overlay = rememberSettingsOverlayState()
    val scope = rememberCoroutineScope()
    val localContext = LocalContext.current

    /** This page's fields, copied onto [base]. */
    fun applyLanguages(base: Settings) = base.copy(
        preferredLanguage = preferredLanguage,
        voiceLanguage = voiceLanguage,
        sttLanguage = sttLanguage.trim()
    )

    /** What the voice would actually use right now, without waiting for a save. */
    val resolvedVoiceLanguage = Language.fromLabel(voiceLanguage.ifBlank { preferredLanguage })

    val speechWarnings = listOfNotNull(
        SpeechLanguageCheck.ttsWarning(
            initial.ttsProvider,
            initial.ttsApiModel,
            initial.ttsVoice,
            ttsModels,
            resolvedVoiceLanguage
        )
    ) + SpeechLanguageCheck.sttWarnings(
        initial.sttProvider,
        initial.sttApiModel,
        sttLanguage,
        sttModels,
        resolvedVoiceLanguage
    )

    SettingsScaffold(title = SettingsPage.LANGUAGE.title, onBack = onBack, overlay = overlay) {
        Text(
            "Every language Gotcha uses, in one place: what the app's own screens are " +
                "written in, what Gotcha speaks and hears, and what it writes its answers in.",
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
                .settingsField("settings_open_app_locale")
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
                    .settingsField("settings_voice_language")
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
            "Which model and voice read it out are picked under Settings → AI → Speech.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            "Transcription language",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        TranscriptionLanguagePicker(
            selectedModel = initial.sttApiModel,
            selectedLanguage = sttLanguage,
            availableModels = sttModels,
            expanded = sttLanguageExpanded,
            onExpandedChange = { sttLanguageExpanded = it },
            onSelect = {
                sttLanguage = it
                sttLanguageExpanded = false
            },
            onClearLanguage = {
                sttLanguage = ""
                sttLanguageExpanded = false
            }
        )
        Text(
            transcriptionOverrideHint(initial.sttProvider),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SpeechLanguageWarnings(speechWarnings)

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
                    .settingsField("settings_reply_language")
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

/** What the transcription override does with the speech-to-text [provider] in use. */
internal fun transcriptionOverrideHint(provider: AudioProvider): String = when {
    provider.isApiBased() ->
        "Leave this empty and speech is transcribed in the voice language. Set it to " +
            "force one language, which helps accuracy when the model tends to guess wrong."
    provider == AudioProvider.ANDROID ->
        "Only Samosa AI and external speech-to-text use this. Android Built-in " +
            "always transcribes in the voice language."
    else -> "Only Samosa AI and external speech-to-text use this. Speech-to-text is off."
}

/**
 * Open the per-app language screen where it can work, otherwise the device-wide
 * language list. If neither activity can be started, a toast says so.
 *
 * Android 13+ shows the per-app screen only for apps that declare their locales
 * (a `LocaleConfig`). Gotcha declares none, because its UI is English only, so
 * Settings refuses the screen. Stock builds still draw a picker whose choices do
 * nothing, and some OEM builds (Nothing OS) close it at once: a blank flash, and
 * no exception for us to fall back on (issue #112). So the per-app screen is only
 * tried when [appHasLocales] says the app declares more than one locale.
 */
internal fun openLanguageSettings(
    context: Context,
    appHasLocales: Boolean = declaresAppLocales(context)
) {
    val intents = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && appHasLocales) {
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

/** True when the app's `LocaleConfig` lists more than one locale (API 33+). */
private fun declaresAppLocales(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    return try {
        (LocaleConfig(context).supportedLocales?.size() ?: 0) > 1
    } catch (_: Exception) {
        false
    }
}
