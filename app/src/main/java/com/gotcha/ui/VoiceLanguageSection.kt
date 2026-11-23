package com.gotcha.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.gotcha.R
import com.gotcha.audio.AudioModel
import com.gotcha.audio.AudioProvider
import com.gotcha.i18n.Language
import com.gotcha.ui.theme.SkinAlertDialog
import com.gotcha.ui.theme.SkinExposedDropdownMenu
import kotlinx.coroutines.launch

/**
 * The languages Gotcha speaks and listens in, on the Voice and speech page.
 *
 * They used to sit on the Language page beside the app's display language and
 * the AI reply language, which put them a page away from the voices and
 * recognizers they have to suit — each page told the user to go to the other.
 * Here they sit beside the speech models, so a model that can't speak the voice
 * language is warned about on the same screen it is picked on.
 *
 * [voiceLanguage] blank follows [replyLanguage]; [sttLanguage] blank follows the
 * voice language. The page saves each one as soon as it is picked, apart from
 * its Save button, which waits on the provider setup. Test voice speaks the
 * language picked here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceLanguageSection(
    voiceLanguage: String,
    onVoiceLanguage: (String) -> Unit,
    replyLanguage: String,
    sttLanguage: String,
    onSttLanguage: (String) -> Unit,
    sttProvider: AudioProvider,
    sttModel: String,
    sttModels: List<AudioModel>,
    onTestVoice: suspend (Language) -> Boolean?
) {
    var voiceExpanded by rememberSaveable { mutableStateOf(false) }
    var sttLanguageExpanded by rememberSaveable { mutableStateOf(false) }
    var testingVoice by remember { mutableStateOf(false) }

    /** Last [Language] whose voice data was reported missing, or null when not shown. */
    var voiceDataMissing by remember { mutableStateOf<Language?>(null) }
    val scope = rememberCoroutineScope()
    val localContext = LocalContext.current
    val resolvedVoiceLanguage = Language.fromLabel(voiceLanguage.ifBlank { replyLanguage })

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
                    onVoiceLanguage("")
                    voiceExpanded = false
                }
            )
            Language.entries.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(stringResource(lang.nameRes)) },
                    onClick = {
                        onVoiceLanguage(lang.label)
                        voiceExpanded = false
                    }
                )
            }
        }
    }

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
        selectedModel = sttModel,
        selectedLanguage = sttLanguage,
        availableModels = sttModels,
        expanded = sttLanguageExpanded,
        onExpandedChange = { sttLanguageExpanded = it },
        onSelect = {
            onSttLanguage(it)
            sttLanguageExpanded = false
        },
        onClearLanguage = {
            onSttLanguage("")
            sttLanguageExpanded = false
        }
    )
    Text(
        stringResource(transcriptionOverrideHint(sttProvider)),
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
}
