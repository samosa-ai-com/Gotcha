package com.gotcha.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gotcha.audio.AudioLanguageLabels
import com.gotcha.audio.AudioModel
import com.gotcha.ui.theme.SkinExposedDropdownMenu

/**
 * The language-versus-model mismatches [com.gotcha.audio.SpeechLanguageCheck]
 * found, one card each (issue #113). Shown on both pages a mismatch can be made
 * from — Language and Speech — so the user sees it next to the choice they just
 * made. Draws nothing when there are none.
 */
@Composable
internal fun SpeechLanguageWarnings(warnings: List<String>, modifier: Modifier = Modifier) {
    if (warnings.isEmpty()) return
    Column(modifier.fillMaxWidth().testTag("settings_speech_language_warnings")) {
        warnings.forEach { warning ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

/**
 * Transcription language override — offers the selected STT model's languages
 * when the server lists them, otherwise [COMMON_STT_LANGUAGES]. Free text too, so
 * any code the server takes can be typed.
 *
 * An override, not the language setting: left empty, transcription follows the
 * voice language. It sat on the Speech page because its codes come from the STT
 * model (issue #74), which left the languages split over two pages; it lives on
 * the Language page now, beside the voice language it overrides (issue #114).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranscriptionLanguagePicker(
    selectedModel: String,
    selectedLanguage: String,
    availableModels: List<AudioModel>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onClearLanguage: () -> Unit
) {
    val selectedModelObj = availableModels.firstOrNull { it.id == selectedModel }
    val languagesList = selectedModelObj?.languages?.takeIf { it.isNotEmpty() }
        ?: COMMON_STT_LANGUAGES
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
        OutlinedTextField(
            value = selectedLanguage,
            onValueChange = onSelect,
            label = { Text("Transcription language override") },
            placeholder = { Text(FOLLOW_VOICE_LANGUAGE) },
            supportingText = AudioLanguageLabels.describe(selectedLanguage)?.let { name -> { Text(name) } },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
                .settingsField("settings_stt_language")
        )
        SkinExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            DropdownMenuItem(
                text = { Text(FOLLOW_VOICE_LANGUAGE) },
                onClick = onClearLanguage
            )
            languagesList.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(AudioLanguageLabels.label(lang)) },
                    onClick = { onSelect(lang) }
                )
            }
        }
    }
}

/** Override entry meaning "no override": transcription follows the voice language. */
internal const val FOLLOW_VOICE_LANGUAGE = "Follow voice language / auto-detect"

private val COMMON_STT_LANGUAGES = listOf(
    "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl", "ar",
    "sv", "it", "id", "hi", "fi", "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu",
    "ta", "no", "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk", "te", "fa",
    "lv", "bn", "sr", "az", "sl", "kn", "et", "mk", "br", "eu", "is", "hy", "ne", "mn",
    "bs", "kk", "sq", "sw", "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc",
    "ka", "be", "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo", "ht", "ps", "tk", "nn",
    "mt", "sa", "lb", "my", "bo", "tl", "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su"
)
