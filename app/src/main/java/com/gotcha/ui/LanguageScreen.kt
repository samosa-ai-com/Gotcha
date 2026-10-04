package com.gotcha.ui

import android.app.LocaleConfig
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gotcha.R
import com.gotcha.audio.AudioModel
import com.gotcha.audio.AudioProvider
import com.gotcha.audio.SpeechLanguageCheck
import com.gotcha.data.Settings
import com.gotcha.i18n.Language
import com.gotcha.i18n.stringLookup
import com.gotcha.ui.theme.SkinAlertDialog
import com.gotcha.ui.theme.SkinExposedDropdownMenu
import kotlinx.coroutines.launch
import android.provider.Settings as AndroidSettings

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
            resolvedVoiceLanguage,
            LocalContext.current.stringLookup()
        )
    ) + SpeechLanguageCheck.sttWarnings(
        initial.sttProvider,
        initial.sttApiModel,
        sttLanguage,
        sttModels,
        resolvedVoiceLanguage,
        LocalContext.current.stringLookup()
    )

    SettingsScaffold(title = stringResource(SettingsPage.LANGUAGE.title), onBack = onBack, overlay = overlay) {
        Text(
            stringResource(R.string.language_page_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider(thickness = 1.dp)

        // ---- 1. App display language ----
        Text(
            stringResource(R.string.language_display_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            stringResource(R.string.language_display_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = { openLanguageSettings(localContext) },
            modifier = Modifier
                .fillMaxWidth()
                .settingsField("settings_open_app_locale")
        ) { Text(stringResource(R.string.language_display_open_settings)) }

        HorizontalDivider(thickness = 1.dp)

        // ---- 2. Voice language ----
        Text(
            stringResource(R.string.language_voice_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            stringResource(R.string.language_voice_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ExposedDropdownMenuBox(
            expanded = voiceExpanded,
            onExpandedChange = { voiceExpanded = it }
        ) {
            OutlinedTextField(
                value = if (voiceLanguage.isBlank()) {
                    stringResource(R.string.language_voice_follow_reply)
                } else {
                    stringResource(Language.fromLabel(voiceLanguage).nameRes)
                },
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.language_voice_title)) },
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
                    text = { Text(stringResource(R.string.language_voice_follow_reply)) },
                    onClick = {
                        voiceLanguage = ""
                        voiceExpanded = false
                    }
                )
                Language.entries.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(stringResource(lang.nameRes)) },
                        onClick = {
                            voiceLanguage = lang.label
                            voiceExpanded = false
                        }
                    )
                }
            }
        }
        Text(
            stringResource(R.string.language_voice_speech_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            stringResource(R.string.language_transcription_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            stringResource(R.string.language_transcription_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
            stringResource(transcriptionOverrideHint(initial.sttProvider)),
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
            Text(
                stringResource(
                    if (testingVoice) R.string.language_test_voice_playing else R.string.language_test_voice
                )
            )
        }
        voiceDataMissing?.let { missingLang ->
            SkinAlertDialog(
                onDismissRequest = { voiceDataMissing = null },
                title = { Text(stringResource(R.string.language_voice_data_missing_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.language_voice_data_missing_body,
                            stringResource(missingLang.nameRes)
                        )
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
                                    localContext.getString(R.string.language_tts_settings_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    ) { Text(stringResource(R.string.language_voice_data_install)) }
                },
                dismissButton = {
                    TextButton(onClick = { voiceDataMissing = null }) { Text(stringResource(R.string.action_cancel)) }
                }
            )
        }

        HorizontalDivider(thickness = 1.dp)

        // ---- 3. AI reply language ----
        Text(
            stringResource(R.string.language_reply_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            stringResource(R.string.language_reply_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ExposedDropdownMenuBox(
            expanded = replyExpanded,
            onExpandedChange = { replyExpanded = it }
        ) {
            OutlinedTextField(
                value = stringResource(Language.fromLabel(preferredLanguage).nameRes),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.language_reply_title)) },
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
                Language.entries.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(stringResource(lang.nameRes)) },
                        onClick = {
                            preferredLanguage = lang.label
                            replyExpanded = false
                        }
                    )
                }
            }
        }

        Button(
            onClick = {
                onSave { applyLanguages(it) }
                overlay.show(localContext.getString(R.string.language_saved))
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_save_language")
        ) { Text(stringResource(R.string.language_save)) }
    }
}

/** What the transcription override does with the speech-to-text [provider] in use. */
@StringRes
internal fun transcriptionOverrideHint(provider: AudioProvider): Int = when {
    provider.isApiBased() -> R.string.language_transcription_hint_api
    provider == AudioProvider.ANDROID -> R.string.language_transcription_hint_android
    else -> R.string.language_transcription_hint_off
}

/**
 * The language's name in the app's display language. [Language.label] stays the
 * stored value and the name the model and speech engines are given.
 */
@get:StringRes
internal val Language.nameRes: Int
    get() = when (this) {
        Language.ENGLISH -> R.string.language_english
        Language.SPANISH -> R.string.language_spanish
        Language.FRENCH -> R.string.language_french
        Language.GERMAN -> R.string.language_german
        Language.HINDI -> R.string.language_hindi
        Language.JAPANESE -> R.string.language_japanese
        Language.CHINESE -> R.string.language_chinese
        Language.ITALIAN -> R.string.language_italian
        Language.PORTUGUESE -> R.string.language_portuguese
    }

/**
 * Open the per-app language screen where it can work, otherwise the device-wide
 * language list. If neither activity can be started, a toast says so.
 *
 * Android 13+ shows the per-app screen only for apps that declare their locales
 * (a `LocaleConfig`). The build generates Gotcha's from its `values-<lang>/`
 * folders, so until a translation ships it lists English alone. With nothing to
 * choose, stock builds draw a picker whose choices do nothing, and some OEM builds
 * (Nothing OS) close it at once: a blank flash, and no exception for us to fall
 * back on (issue #112). So the per-app screen is only tried when [appHasLocales]
 * says the app declares more than one locale, which turns it on by itself once
 * the first translation is added. Android 11 and 12 have no per-app screen and
 * always get the device-wide one.
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
        context.getString(R.string.language_display_settings_failed),
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
