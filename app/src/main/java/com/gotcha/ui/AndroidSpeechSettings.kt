package com.gotcha.ui

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.gotcha.R
import com.gotcha.audio.AndroidEngine
import com.gotcha.audio.AndroidSpeechStatus
import com.gotcha.audio.AndroidVoice
import com.gotcha.audio.SpeechAvailability
import com.gotcha.i18n.Language
import com.gotcha.ui.theme.SkinExposedDropdownMenu
import android.provider.Settings as AndroidSettings

/**
 * Android Built-in text to speech (#122): the engine and the voice for the voice
 * language, labelled by where they run, and whether [language] can be spoken.
 * Blank choices follow the phone and the engine.
 */
@Composable
internal fun AndroidTtsSettings(
    status: AndroidSpeechStatus,
    language: Language,
    engine: String,
    onEngine: (String) -> Unit,
    voice: String,
    onVoice: (String) -> Unit
) {
    val languageName = stringResource(language.nameRes)
    EnginePicker(
        label = stringResource(R.string.speech_android_tts_engine),
        engines = status.ttsEngines,
        default = status.ttsDefaultEngine,
        selected = engine,
        onSelect = onEngine,
        testTag = "settings_android_tts_engine"
    )
    val voiceLabels = status.ttsVoices.associate { it.name to voiceLabel(it) }
    Picker(
        label = stringResource(R.string.speech_android_voice, languageName),
        // A voice in another language isn't offered here and isn't used for this one.
        value = voiceLabels[voice] ?: stringResource(R.string.speech_android_voice_default),
        options = listOf("" to stringResource(R.string.speech_android_voice_default)) + voiceLabels.toList(),
        onSelect = onVoice,
        testTag = "settings_android_tts_voice"
    )
    status.ttsAvailability?.let { AvailabilityText(ttsAvailabilityRes(it), languageName, status.ttsSupports == false) }
    AndroidSettingsLink(
        R.string.speech_android_tts_settings,
        Intent(ACTION_TTS_SETTINGS),
        R.string.language_tts_settings_failed,
        "settings_android_tts_settings"
    )
}

/**
 * Android Built-in speech recognition (#122): the recognizer, the on-device-only
 * switch, and whether [language] can be recognised by whichever will be used.
 */
@Composable
internal fun AndroidSttSettings(
    status: AndroidSpeechStatus,
    language: Language,
    service: String,
    onService: (String) -> Unit,
    onDeviceOnly: Boolean,
    onOnDeviceOnly: (Boolean) -> Unit
) {
    val languageName = stringResource(language.nameRes)
    EnginePicker(
        label = stringResource(R.string.speech_android_stt_engine),
        engines = status.recognizers,
        default = status.defaultRecognizer,
        selected = service,
        onSelect = onService,
        testTag = "settings_android_stt_service",
        enabled = !onDeviceOnly
    )
    // Switching on needs a phone that can; switching off always works.
    val canBeOnDevice = status.onDeviceRecognition == true
    SettingsToggleRow(
        label = stringResource(R.string.speech_android_on_device_only),
        checked = onDeviceOnly,
        onCheckedChange = onOnDeviceOnly,
        enabled = canBeOnDevice || onDeviceOnly,
        switchTestTag = "settings_android_stt_on_device"
    )
    Text(
        stringResource(
            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> R.string.speech_android_on_device_needs_12
                status.onDeviceRecognition == false -> R.string.speech_android_on_device_none
                else -> R.string.speech_android_on_device_only_description
            }
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    status.recognizerAvailability?.let {
        AvailabilityText(sttAvailabilityRes(it), languageName, status.recognizerSupports == false)
    }
    AndroidSettingsLink(
        R.string.speech_android_voice_input_settings,
        Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS),
        R.string.speech_android_voice_input_settings_failed,
        "settings_android_voice_input_settings"
    )
}

/** An engine choice where blank means "the phone's", named after [default] when known. */
@Composable
private fun EnginePicker(
    label: String,
    engines: List<AndroidEngine>,
    default: AndroidEngine?,
    selected: String,
    onSelect: (String) -> Unit,
    testTag: String,
    enabled: Boolean = true
) {
    val phoneChoice = default?.let { stringResource(R.string.speech_android_phone_default, it.label) }
        ?: stringResource(R.string.speech_android_phone_default_unknown)
    Picker(
        label = label,
        // An engine that's been uninstalled reads as the phone's, which is what Android falls back to.
        value = engines.firstOrNull { it.id == selected }?.label ?: phoneChoice,
        options = listOf("" to phoneChoice) + engines.map { it.id to it.label },
        onSelect = onSelect,
        testTag = testTag,
        enabled = enabled
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    testTag: String,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier = Modifier.fillMaxWidth().menuAnchor().settingsField(testTag)
        )
        SkinExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun voiceLabel(voice: AndroidVoice): String = stringResource(
    when (voice.availability) {
        SpeechAvailability.ON_DEVICE -> R.string.speech_android_voice_on_device
        SpeechAvailability.ONLINE -> R.string.speech_android_voice_online
        else -> R.string.speech_android_voice_downloadable
    },
    voice.name
)

@StringRes
internal fun ttsAvailabilityRes(availability: SpeechAvailability): Int = when (availability) {
    SpeechAvailability.ON_DEVICE -> R.string.speech_android_tts_on_device
    SpeechAvailability.ONLINE -> R.string.speech_android_tts_online
    SpeechAvailability.DOWNLOADABLE -> R.string.speech_android_tts_downloadable
    SpeechAvailability.AVAILABLE -> R.string.speech_android_tts_available
    SpeechAvailability.UNSUPPORTED -> R.string.speech_android_tts_unsupported
}

@StringRes
internal fun sttAvailabilityRes(availability: SpeechAvailability): Int = when (availability) {
    SpeechAvailability.ON_DEVICE -> R.string.speech_android_stt_on_device
    SpeechAvailability.ONLINE -> R.string.speech_android_stt_online
    SpeechAvailability.DOWNLOADABLE -> R.string.speech_android_stt_downloadable
    SpeechAvailability.AVAILABLE -> R.string.speech_android_stt_available
    SpeechAvailability.UNSUPPORTED -> R.string.speech_android_stt_unsupported
}

/** Whether the voice language works here; in the error colour when it won't. */
@Composable
private fun AvailabilityText(@StringRes res: Int, languageName: String, problem: Boolean) {
    Text(
        stringResource(res, languageName),
        style = MaterialTheme.typography.bodyMedium,
        color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("settings_android_speech_availability")
    )
}

/** A button to an Android settings screen, with a toast when the phone has none. */
@Composable
private fun AndroidSettingsLink(@StringRes textRes: Int, intent: Intent, @StringRes failedRes: Int, testTag: String) {
    val context = LocalContext.current
    TextButton(
        onClick = {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(failedRes), Toast.LENGTH_SHORT).show()
            }
        },
        modifier = Modifier.testTag(testTag)
    ) { Text(stringResource(textRes)) }
}

/** Android's text-to-speech settings: no public constant, but every version since 4.0 has it. */
private const val ACTION_TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"
