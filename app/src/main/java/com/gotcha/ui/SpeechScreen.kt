package com.gotcha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.gotcha.BuildConfig
import com.gotcha.audio.AudioModel
import com.gotcha.audio.AudioProvider
import com.gotcha.audio.VoiceInfo
import com.gotcha.data.Settings
import com.gotcha.ui.theme.SkinExposedDropdownMenu
import kotlinx.coroutines.launch

/**
 * The Speech page: which engines synthesise and transcribe, the models and
 * voices they use, and whether replies are read aloud automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechScreen(
    load: () -> Settings,
    onSave: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
    onRefreshAudioModels: suspend (Settings) -> Pair<List<AudioModel>, List<AudioModel>> = {
        Pair(emptyList(), emptyList())
    },
    onSamosaSignIn: suspend () -> Result<Pair<String, String>> = {
        Result.failure(Exception("Not available"))
    },
    onSamosaSignOut: suspend () -> Unit = {},
    /** Fetches the user's remaining credit (raw float) or null when unavailable. */
    onFetchSamosaCredits: suspend () -> Double? = { null }
) {
    val initial = remember { load() }
    var ttsProvider by remember { mutableStateOf(initial.ttsProvider) }
    var ttsApiBaseUrl by remember { mutableStateOf(initial.ttsApiBaseUrl) }
    var ttsApiKey by remember { mutableStateOf(initial.ttsApiKey) }
    var ttsApiModel by remember { mutableStateOf(initial.ttsApiModel) }
    var ttsVoice by remember { mutableStateOf(initial.ttsVoice) }
    var sttProvider by remember { mutableStateOf(initial.sttProvider) }
    var sttApiBaseUrl by remember { mutableStateOf(initial.sttApiBaseUrl) }
    var sttApiKey by remember { mutableStateOf(initial.sttApiKey) }
    var sttApiModel by remember { mutableStateOf(initial.sttApiModel) }
    var sttLanguage by remember { mutableStateOf(initial.sttLanguage) }
    var autoReadReplies by remember { mutableStateOf(initial.autoReadReplies) }
    // Samosa auth state, kept live as the user signs in / out.
    var samosaToken by remember { mutableStateOf(initial.samosaSessionToken) }
    var samosaEmail by remember { mutableStateOf(initial.samosaEmail) }
    var samosaBusy by remember { mutableStateOf(false) }
    var samosaCredits by remember { mutableStateOf<Double?>(null) }

    var availableTtsModels by remember { mutableStateOf<List<AudioModel>>(emptyList()) }
    var availableSttModels by remember { mutableStateOf<List<AudioModel>>(emptyList()) }
    var refreshingModels by remember { mutableStateOf(false) }
    var showTtsKey by remember { mutableStateOf(false) }
    var showSttKey by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    var ttsProviderExpanded by remember { mutableStateOf(false) }
    var sttProviderExpanded by remember { mutableStateOf(false) }
    var ttsModelExpanded by remember { mutableStateOf(false) }
    var ttsVoiceExpanded by remember { mutableStateOf(false) }
    var sttModelExpanded by remember { mutableStateOf(false) }
    var sttLanguageExpanded by remember { mutableStateOf(false) }

    val overlay = rememberSettingsOverlayState()
    val scope = rememberCoroutineScope()

    // Fetch the credit balance when signed in, and whenever the token changes
    // (sign-in sets it, sign-out clears it). Keep it light: no polling.
    LaunchedEffect(samosaToken) {
        samosaCredits = if (samosaToken.isBlank()) {
            null
        } else {
            onFetchSamosaCredits()
        }
    }

    /** This page's fields, copied onto [base]. */
    fun applySpeech(base: Settings) = base.copy(
        ttsProvider = ttsProvider,
        ttsApiBaseUrl = ttsApiBaseUrl.trim(),
        ttsApiKey = ttsApiKey.trim(),
        ttsApiModel = ttsApiModel.trim(),
        ttsVoice = ttsVoice.trim(),
        sttProvider = sttProvider,
        sttApiBaseUrl = sttApiBaseUrl.trim(),
        sttApiKey = sttApiKey.trim(),
        sttApiModel = sttApiModel.trim(),
        sttLanguage = sttLanguage.trim(),
        autoReadReplies = autoReadReplies
    )

    /** As stored, plus the unsaved edits — audio-model discovery needs both. */
    fun draftSpeech(): Settings = applySpeech(load())

    /**
     * True when the chosen TTS/STT providers have what they need. Mirrors
     * [Settings.isSpeechConfigured] without building a full `Settings`
     * object so recomposition doesn't allocate on every read.
     */
    fun speechConfigValid(): Boolean {
        val ttsOk = when (ttsProvider) {
            AudioProvider.SAMOSA_AI -> samosaToken.isNotBlank()
            AudioProvider.API -> ttsApiBaseUrl.trim().isNotBlank()
            else -> true
        }
        val sttOk = when (sttProvider) {
            AudioProvider.SAMOSA_AI -> samosaToken.isNotBlank()
            AudioProvider.API -> sttApiBaseUrl.trim().isNotBlank()
            else -> true
        }
        return ttsOk && sttOk
    }

    val refreshAudioModelsAction = {
        if (!refreshingModels) {
            refreshingModels = true
            status = "Refreshing audio models…"
            scope.launch {
                val (tts, stt) = onRefreshAudioModels(draftSpeech())
                availableTtsModels = tts
                availableSttModels = stt
                status = "Found ${tts.size} TTS, ${stt.size} STT models"
                refreshingModels = false
            }
        }
    }

    LaunchedEffect(ttsProvider, sttProvider) {
        if (ttsProvider == AudioProvider.API || ttsProvider == AudioProvider.SAMOSA_AI ||
            sttProvider == AudioProvider.API || sttProvider == AudioProvider.SAMOSA_AI
        ) {
            refreshAudioModelsAction()
        }
    }

    SettingsScaffold(title = SettingsPage.SPEECH.title, onBack = onBack, overlay = overlay) {
        val samosaAudioSelected = ttsProvider == AudioProvider.SAMOSA_AI ||
            sttProvider == AudioProvider.SAMOSA_AI
        if (samosaAudioSelected) {
            SamosaAuthSection(
                email = samosaEmail,
                signedIn = samosaToken.isNotBlank(),
                busy = samosaBusy,
                creditsRemaining = samosaCredits,
                onSignIn = {
                    samosaBusy = true
                    status = "Signing in with Google…"
                    scope.launch {
                        val result = onSamosaSignIn()
                        result.onSuccess { (email, token) ->
                            samosaEmail = email
                            samosaToken = token
                            status = "Signed in as $email"
                        }.onFailure { e ->
                            status = e.message ?: "Sign-in failed."
                        }
                        samosaBusy = false
                    }
                },
                onSignOut = {
                    samosaBusy = true
                    status = "Signing out…"
                    scope.launch {
                        onSamosaSignOut()
                        samosaToken = ""
                        samosaEmail = ""
                        samosaCredits = null
                        status = "Signed out of Samosa AI."
                        samosaBusy = false
                    }
                }
            )
        }
        ExposedDropdownMenuBox(
            expanded = ttsProviderExpanded,
            onExpandedChange = { ttsProviderExpanded = it }
        ) {
            OutlinedTextField(
                value = ttsProvider.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("TTS Provider") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded = ttsProviderExpanded
                    )
                },
                modifier = Modifier.fillMaxWidth().menuAnchor()
            )
            SkinExposedDropdownMenu(
                expanded = ttsProviderExpanded,
                onDismissRequest = { ttsProviderExpanded = false }
            ) {
                AudioProvider.entries.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider.label) },
                        onClick = {
                            ttsProvider = provider
                            ttsProviderExpanded = false
                        }
                    )
                }
            }
        }
        when (ttsProvider) {
            AudioProvider.SAMOSA_AI -> {
                TtsModelPicker(
                    selectedModel = ttsApiModel,
                    availableModels = availableTtsModels,
                    refreshing = refreshingModels,
                    expanded = ttsModelExpanded,
                    onExpandedChange = { ttsModelExpanded = it },
                    onRefresh = refreshAudioModelsAction,
                    onSelect = {
                        ttsApiModel = it
                        ttsModelExpanded = false
                    }
                )
                TtsVoicePicker(
                    selectedModel = ttsApiModel,
                    selectedVoice = ttsVoice,
                    availableModels = availableTtsModels,
                    expanded = ttsVoiceExpanded,
                    onExpandedChange = { ttsVoiceExpanded = it },
                    onSelect = {
                        ttsVoice = it
                        ttsVoiceExpanded = false
                    },
                    onClearVoice = { ttsVoice = "" }
                )
            }
            AudioProvider.API -> {
                OutlinedTextField(
                    value = ttsApiBaseUrl,
                    onValueChange = { ttsApiBaseUrl = it },
                    label = { Text("TTS API Base URL") },
                    singleLine = true,
                    placeholder = { Text("http://${BuildConfig.DEV_LAN_HOST}:8969/v1") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ttsApiKey,
                    onValueChange = { ttsApiKey = it },
                    label = { Text("TTS API Key (optional)") },
                    singleLine = true,
                    placeholder = { Text("Leave blank to use main API key") },
                    visualTransformation = if (showTtsKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { showTtsKey = !showTtsKey }) {
                            Text(if (showTtsKey) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                TtsModelPicker(
                    selectedModel = ttsApiModel,
                    availableModels = availableTtsModels,
                    refreshing = refreshingModels,
                    expanded = ttsModelExpanded,
                    onExpandedChange = { ttsModelExpanded = it },
                    onRefresh = refreshAudioModelsAction,
                    onSelect = {
                        ttsApiModel = it
                        ttsModelExpanded = false
                    }
                )
                TtsVoicePicker(
                    selectedModel = ttsApiModel,
                    selectedVoice = ttsVoice,
                    availableModels = availableTtsModels,
                    expanded = ttsVoiceExpanded,
                    onExpandedChange = { ttsVoiceExpanded = it },
                    onSelect = {
                        ttsVoice = it
                        ttsVoiceExpanded = false
                    },
                    onClearVoice = { ttsVoice = "" }
                )
            }
            AudioProvider.ANDROID, AudioProvider.NONE -> Unit
        }
        ExposedDropdownMenuBox(
            expanded = sttProviderExpanded,
            onExpandedChange = { sttProviderExpanded = it }
        ) {
            OutlinedTextField(
                value = sttProvider.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("STT Provider") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded = sttProviderExpanded
                    )
                },
                modifier = Modifier.fillMaxWidth().menuAnchor()
            )
            SkinExposedDropdownMenu(
                expanded = sttProviderExpanded,
                onDismissRequest = { sttProviderExpanded = false }
            ) {
                AudioProvider.entries.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider.label) },
                        onClick = {
                            sttProvider = provider
                            sttProviderExpanded = false
                        }
                    )
                }
            }
        }
        when (sttProvider) {
            AudioProvider.SAMOSA_AI -> {
                SttModelPicker(
                    selectedModel = sttApiModel,
                    availableModels = availableSttModels,
                    refreshing = refreshingModels,
                    expanded = sttModelExpanded,
                    onExpandedChange = { sttModelExpanded = it },
                    onRefresh = refreshAudioModelsAction,
                    onSelect = {
                        sttApiModel = it
                        sttModelExpanded = false
                    }
                )
                SttLanguagePicker(
                    selectedModel = sttApiModel,
                    selectedLanguage = sttLanguage,
                    availableModels = availableSttModels,
                    expanded = sttLanguageExpanded,
                    onExpandedChange = { sttLanguageExpanded = it },
                    onSelect = {
                        sttLanguage = it
                        sttLanguageExpanded = false
                    },
                    onClearLanguage = { sttLanguage = "" }
                )
            }
            AudioProvider.API -> {
                OutlinedTextField(
                    value = sttApiBaseUrl,
                    onValueChange = { sttApiBaseUrl = it },
                    label = { Text("STT API Base URL") },
                    singleLine = true,
                    placeholder = { Text("http://${BuildConfig.DEV_LAN_HOST}:8969/v1") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = sttApiKey,
                    onValueChange = { sttApiKey = it },
                    label = { Text("STT API Key (optional)") },
                    singleLine = true,
                    placeholder = { Text("Leave blank to use main API key") },
                    visualTransformation = if (showSttKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { showSttKey = !showSttKey }) {
                            Text(if (showSttKey) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                SttModelPicker(
                    selectedModel = sttApiModel,
                    availableModels = availableSttModels,
                    refreshing = refreshingModels,
                    expanded = sttModelExpanded,
                    onExpandedChange = { sttModelExpanded = it },
                    onRefresh = refreshAudioModelsAction,
                    onSelect = {
                        sttApiModel = it
                        sttModelExpanded = false
                    }
                )
                SttLanguagePicker(
                    selectedModel = sttApiModel,
                    selectedLanguage = sttLanguage,
                    availableModels = availableSttModels,
                    expanded = sttLanguageExpanded,
                    onExpandedChange = { sttLanguageExpanded = it },
                    onSelect = {
                        sttLanguage = it
                        sttLanguageExpanded = false
                    },
                    onClearLanguage = { sttLanguage = "" }
                )
            }
            AudioProvider.ANDROID, AudioProvider.NONE -> Unit
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Auto-read replies aloud", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = autoReadReplies, onCheckedChange = { autoReadReplies = it })
        }
        Button(
            onClick = {
                onSave { applySpeech(it) }
                overlay.show("Saved.")
                status = null
            },
            enabled = speechConfigValid(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save Speech Settings") }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** TTS model picker dropdown — shared by Samosa AI and External API sections. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsModelPicker(
    selectedModel: String,
    availableModels: List<AudioModel>,
    refreshing: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            onExpandedChange(it)
            if (it) onRefresh()
        }
    ) {
        OutlinedTextField(
            value = selectedModel.ifEmpty { "(select model)" },
            onValueChange = {},
            readOnly = true,
            label = { Text("TTS Model") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        SkinExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            DropdownMenuItem(
                text = {
                    Text(if (refreshing) "Refreshing…" else "🔄 Refresh audio models…")
                },
                onClick = onRefresh
            )
            if (availableModels.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No models found") },
                    onClick = { onExpandedChange(false) }
                )
            } else {
                availableModels.forEach { audioModel ->
                    DropdownMenuItem(
                        text = { Text(audioModel.id) },
                        onClick = { onSelect(audioModel.id) }
                    )
                }
            }
        }
    }
}

/** TTS voice picker — always renders a hybrid text+dropdown like [SttLanguagePicker].
 *  Two-tier voice list:
 *  1. The selected model's own [AudioModel.voices] (Kokoro names like `af_heart`,
 *     `am_adam`, … that the server guarantees).
 *  2. If the selected model has no voices (or no model matches [selectedModel]),
 *     fall back to the union of every TTS model's voices in [availableModels] —
 *     covers stale saved ids and provider switches.
 *
 *  No fabricated fallback. If [availableModels] carries no voices at all, the
 *  dropdown still opens but only contains a single disabled hint item telling the
 *  user to type. The [OutlinedTextField] is editable, so the user can always type
 *  any voice ID, regardless of what (or nothing) the server returned. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsVoicePicker(
    selectedModel: String,
    selectedVoice: String,
    availableModels: List<AudioModel>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onClearVoice: () -> Unit
) {
    val selectedModelObj = availableModels.firstOrNull { it.id == selectedModel }
    val voicesList: List<VoiceInfo> = run {
        val fromSelected = selectedModelObj?.voices.orEmpty()
        if (fromSelected.isNotEmpty()) {
            fromSelected
        } else {
            availableModels.flatMap { it.voices }
        }
    }
    val hasAnyVoices = voicesList.isNotEmpty()
    val defaultVoiceLabel = selectedModelObj?.defaultVoice
        ?: voicesList.firstOrNull()?.id
        ?: "the provider's default"
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
        OutlinedTextField(
            value = selectedVoice,
            onValueChange = onSelect,
            label = { Text("TTS Voice (optional)") },
            placeholder = {
                if (hasAnyVoices) {
                    Text("Default ($defaultVoiceLabel) or pick below")
                } else {
                    Text("Type a voice ID — picker has no suggestions")
                }
            },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        SkinExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            DropdownMenuItem(
                text = { Text("Default (clear)") },
                onClick = onClearVoice
            )
            if (hasAnyVoices) {
                voicesList.forEach { voiceInfo ->
                    DropdownMenuItem(
                        text = { Text(voiceInfo.displayLabel) },
                        onClick = { onSelect(voiceInfo.id) }
                    )
                }
            } else {
                DropdownMenuItem(
                    text = { Text("No voices suggested — type a voice ID above") },
                    onClick = { },
                    enabled = false
                )
            }
        }
    }
}

/** STT model picker dropdown — shared by Samosa AI and External API sections. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SttModelPicker(
    selectedModel: String,
    availableModels: List<AudioModel>,
    refreshing: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            onExpandedChange(it)
            if (it) onRefresh()
        }
    ) {
        OutlinedTextField(
            value = selectedModel.ifEmpty { "(select model)" },
            onValueChange = {},
            readOnly = true,
            label = { Text("STT Model") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        SkinExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            DropdownMenuItem(
                text = {
                    Text(if (refreshing) "Refreshing…" else "🔄 Refresh audio models…")
                },
                onClick = onRefresh
            )
            if (availableModels.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No models found") },
                    onClick = { onExpandedChange(false) }
                )
            } else {
                availableModels.forEach { audioModel ->
                    DropdownMenuItem(
                        text = { Text(audioModel.id) },
                        onClick = { onSelect(audioModel.id) }
                    )
                }
            }
        }
    }
}

/** STT language picker — uses the model's languages when available, otherwise [COMMON_STT_LANGUAGES]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SttLanguagePicker(
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
            label = { Text("STT Language (optional)") },
            placeholder = { Text("Auto-detect / Default (or select below)") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        SkinExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            DropdownMenuItem(
                text = { Text("Auto-detect / Default (empty)") },
                onClick = onClearLanguage
            )
            languagesList.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(lang) },
                    onClick = { onSelect(lang) }
                )
            }
        }
    }
}

private val COMMON_STT_LANGUAGES = listOf(
    "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl", "ar",
    "sv", "it", "id", "hi", "fi", "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu",
    "ta", "no", "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk", "te", "fa",
    "lv", "bn", "sr", "az", "sl", "kn", "et", "mk", "br", "eu", "is", "hy", "ne", "mn",
    "bs", "kk", "sq", "sw", "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc",
    "ka", "be", "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo", "ht", "ps", "tk", "nn",
    "mt", "sa", "lb", "my", "bo", "tl", "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su"
)
