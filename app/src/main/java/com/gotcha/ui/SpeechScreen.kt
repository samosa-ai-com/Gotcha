package com.gotcha.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import com.gotcha.BuildConfig
import com.gotcha.R
import com.gotcha.audio.AudioLanguageLabels
import com.gotcha.audio.AudioModel
import com.gotcha.audio.AudioProvider
import com.gotcha.audio.SpeechLanguageCheck
import com.gotcha.audio.VoiceInfo
import com.gotcha.data.Settings
import com.gotcha.i18n.Language
import com.gotcha.ui.theme.SkinExposedDropdownMenu
import kotlinx.coroutines.launch

/**
 * The Speech page: which engines synthesise and transcribe, the models and
 * voices they use, and whether replies are read aloud automatically.
 *
 * No language is set here. The transcription override moved to the Language
 * page (issue #114); this page shows it read-only, with a way there
 * ([onOpenLanguage]), and warns when a model picked here doesn't suit the voice
 * language ([SpeechLanguageCheck], issue #113).
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
    /** Fetches the user's full profile (including tier, tags, referral) or null when unavailable. */
    onFetchSamosaProfile: suspend () -> com.gotcha.auth.SamosaUser? = { null },
    /** Claims an invite code via the auth manager. */
    onClaimReferral: suspend (String) -> Result<Unit> = {
        Result.failure(Exception("Not supported"))
    },
    /** Opens Settings → Language, where every language setting lives. */
    onOpenLanguage: () -> Unit = {}
) {
    val initial = remember { load() }
    var ttsProvider by remember { mutableStateOf(initial.ttsProvider) }
    var ttsApiBaseUrl by remember { mutableStateOf(initial.ttsApiBaseUrl) }
    var ttsApiKey by remember { mutableStateOf(initial.ttsApiKey) }
    var ttsApiModel by remember { mutableStateOf(initial.ttsApiModel) }
    var ttsVoice by remember { mutableStateOf(initial.ttsVoice) }
    var podcastHostAVoice by remember { mutableStateOf(initial.podcastHostAVoice) }
    var podcastHostBVoice by remember { mutableStateOf(initial.podcastHostBVoice) }
    var sttProvider by remember { mutableStateOf(initial.sttProvider) }
    var sttApiBaseUrl by remember { mutableStateOf(initial.sttApiBaseUrl) }
    var sttApiKey by remember { mutableStateOf(initial.sttApiKey) }
    var sttApiModel by remember { mutableStateOf(initial.sttApiModel) }
    var autoReadReplies by remember { mutableStateOf(initial.autoReadReplies) }
    // Samosa auth state, kept live as the user signs in / out.
    var samosaToken by remember { mutableStateOf(initial.samosaSessionToken) }
    var samosaEmail by remember { mutableStateOf(initial.samosaEmail) }
    var samosaBusy by remember { mutableStateOf(false) }
    var samosaCredits by remember { mutableStateOf<Double?>(null) }
    var samosaUser by remember { mutableStateOf<com.gotcha.auth.SamosaUser?>(null) }
    var referralBusy by remember { mutableStateOf(false) }
    var referralError by remember { mutableStateOf<String?>(null) }

    var availableTtsModels by remember { mutableStateOf<List<AudioModel>>(emptyList()) }
    var availableSttModels by remember { mutableStateOf<List<AudioModel>>(emptyList()) }
    var refreshingModels by remember { mutableStateOf(false) }
    var showTtsKey by remember { mutableStateOf(false) }
    var showSttKey by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val localContext = LocalContext.current

    var ttsProviderExpanded by remember { mutableStateOf(false) }
    var sttProviderExpanded by remember { mutableStateOf(false) }
    var ttsModelExpanded by remember { mutableStateOf(false) }
    var ttsVoiceExpanded by remember { mutableStateOf(false) }
    var hostAVoiceExpanded by remember { mutableStateOf(false) }
    var hostBVoiceExpanded by remember { mutableStateOf(false) }
    var sttModelExpanded by remember { mutableStateOf(false) }

    val overlay = rememberSettingsOverlayState()
    val scope = rememberCoroutineScope()

    // Fetch the profile & credit balance when signed in, and whenever the token changes
    // (sign-in sets it, sign-out clears it). Keep it light: no polling.
    LaunchedEffect(samosaToken) {
        if (samosaToken.isBlank()) {
            samosaCredits = null
            samosaUser = null
        } else {
            val profile = onFetchSamosaProfile()
            samosaUser = profile
            samosaCredits = profile?.creditsRemaining
        }
    }

    /** This page's fields, copied onto [base]. */
    fun applySpeech(base: Settings) = base.copy(
        ttsProvider = ttsProvider,
        ttsApiBaseUrl = ttsApiBaseUrl.trim(),
        ttsApiKey = ttsApiKey.trim(),
        ttsApiModel = ttsApiModel.trim(),
        ttsVoice = ttsVoice.trim(),
        podcastHostAVoice = podcastHostAVoice.trim(),
        podcastHostBVoice = podcastHostBVoice.trim(),
        sttProvider = sttProvider,
        sttApiBaseUrl = sttApiBaseUrl.trim(),
        sttApiKey = sttApiKey.trim(),
        sttApiModel = sttApiModel.trim(),
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
            status = localContext.getString(R.string.speech_refreshing_audio_models)
            scope.launch {
                val (tts, stt) = onRefreshAudioModels(draftSpeech())
                availableTtsModels = tts
                availableSttModels = stt
                status = localContext.getString(R.string.speech_models_found, tts.size, stt.size)
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

    SettingsScaffold(title = stringResource(SettingsPage.SPEECH.title), onBack = onBack, overlay = overlay) {
        // ---- Voice / speech recommendations ----
        Text(
            stringResource(R.string.speech_for_mixed_language_text_like),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val samosaAudioSelected = ttsProvider == AudioProvider.SAMOSA_AI ||
            sttProvider == AudioProvider.SAMOSA_AI
        if (samosaAudioSelected) {
            SamosaAuthSection(
                email = samosaEmail,
                signedIn = samosaToken.isNotBlank(),
                busy = samosaBusy,
                creditsRemaining = samosaCredits,
                user = samosaUser,
                referralBusy = referralBusy,
                referralError = referralError,
                onClaimReferral = { code ->
                    referralBusy = true
                    referralError = null
                    scope.launch {
                        val res = onClaimReferral(code)
                        res.onSuccess {
                            val profile = onFetchSamosaProfile()
                            samosaUser = (profile ?: samosaUser)?.let { u ->
                                u.copy(
                                    referral = u.referral.copy(canClaim = false),
                                    creditsRemaining = profile?.creditsRemaining ?: samosaCredits
                                )
                            }
                            samosaCredits = samosaUser?.creditsRemaining
                            referralBusy = false
                            status = localContext.getString(R.string.samosa_invite_applied)
                        }.onFailure { e ->
                            referralError = e.message ?: localContext.getString(R.string.samosa_invite_failed)
                            referralBusy = false
                        }
                    }
                },
                onSignIn = {
                    samosaBusy = true
                    status = localContext.getString(R.string.samosa_signing_in_google)
                    scope.launch {
                        val result = onSamosaSignIn()
                        result.onSuccess { (email, token) ->
                            samosaEmail = email
                            samosaToken = token
                            val profile = onFetchSamosaProfile()
                            samosaUser = profile
                            samosaCredits = profile?.creditsRemaining
                            status = localContext.getString(R.string.samosa_signed_in_as, email)
                        }.onFailure { e ->
                            status = e.message ?: localContext.getString(R.string.samosa_sign_in_failed)
                        }
                        samosaBusy = false
                    }
                },
                onSignOut = {
                    samosaBusy = true
                    status = localContext.getString(R.string.samosa_signing_out)
                    scope.launch {
                        onSamosaSignOut()
                        samosaToken = ""
                        samosaEmail = ""
                        samosaCredits = null
                        samosaUser = null
                        status = localContext.getString(R.string.samosa_signed_out)
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
                label = { Text(stringResource(R.string.speech_tts_provider)) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded = ttsProviderExpanded
                    )
                },
                modifier = Modifier.fillMaxWidth().menuAnchor().settingsField("settings_tts_provider")
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
                SpeechDocsLink(modifier = Modifier.testTag("settings_tts_docs_link"))
            }
            AudioProvider.API -> {
                OutlinedTextField(
                    value = ttsApiBaseUrl,
                    onValueChange = { ttsApiBaseUrl = it },
                    label = { Text(stringResource(R.string.speech_tts_api_base_url)) },
                    singleLine = true,
                    placeholder = { Text("http://${BuildConfig.DEV_LAN_HOST}:8969/v1") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ttsApiKey,
                    onValueChange = { ttsApiKey = it },
                    label = { Text(stringResource(R.string.speech_tts_api_key_optional)) },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.speech_leave_blank_to_use_main)) },
                    visualTransformation = if (showTtsKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { showTtsKey = !showTtsKey }) {
                            Text(stringResource(if (showTtsKey) R.string.speech_hide else R.string.speech_show))
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
        // ---- Podcast hosts (synthesize_podcast_dialogue) ----
        // Advanced: only two-host podcast generation reads these, and the
        // defaults (the TTS voice for host A, an automatically chosen second
        // voice for host B) already work.
        if (ttsProvider.isApiBased()) {
            SettingsAdvancedSection(testTag = "settings_speech_advanced") {
                Text(
                    stringResource(R.string.speech_podcast_hosts_the_two_voices),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TtsVoicePicker(
                    selectedModel = ttsApiModel,
                    selectedVoice = podcastHostAVoice,
                    availableModels = availableTtsModels,
                    expanded = hostAVoiceExpanded,
                    onExpandedChange = { hostAVoiceExpanded = it },
                    onSelect = {
                        podcastHostAVoice = it
                        hostAVoiceExpanded = false
                    },
                    onClearVoice = { podcastHostAVoice = "" },
                    label = stringResource(R.string.speech_podcast_host_a_voice_optional)
                )
                TtsVoicePicker(
                    selectedModel = ttsApiModel,
                    selectedVoice = podcastHostBVoice,
                    availableModels = availableTtsModels,
                    expanded = hostBVoiceExpanded,
                    onExpandedChange = { hostBVoiceExpanded = it },
                    onSelect = {
                        podcastHostBVoice = it
                        hostBVoiceExpanded = false
                    },
                    onClearVoice = { podcastHostBVoice = "" },
                    label = stringResource(R.string.speech_podcast_host_b_voice_optional)
                )
            }
        }
        ExposedDropdownMenuBox(
            expanded = sttProviderExpanded,
            onExpandedChange = { sttProviderExpanded = it }
        ) {
            OutlinedTextField(
                value = sttProvider.label,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.speech_stt_provider)) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded = sttProviderExpanded
                    )
                },
                modifier = Modifier.fillMaxWidth().menuAnchor().settingsField("settings_stt_provider")
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
                TranscriptionLanguageSummary(
                    sttLanguage = initial.sttLanguage,
                    voiceLanguage = initial.effectiveVoiceLanguage.label,
                    onOpenLanguage = onOpenLanguage
                )
                SpeechDocsLink(modifier = Modifier.testTag("settings_stt_docs_link"))
            }
            AudioProvider.API -> {
                OutlinedTextField(
                    value = sttApiBaseUrl,
                    onValueChange = { sttApiBaseUrl = it },
                    label = { Text(stringResource(R.string.speech_stt_api_base_url)) },
                    singleLine = true,
                    placeholder = { Text("http://${BuildConfig.DEV_LAN_HOST}:8969/v1") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = sttApiKey,
                    onValueChange = { sttApiKey = it },
                    label = { Text(stringResource(R.string.speech_stt_api_key_optional)) },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.speech_leave_blank_to_use_main)) },
                    visualTransformation = if (showSttKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { showSttKey = !showSttKey }) {
                            Text(stringResource(if (showSttKey) R.string.speech_hide else R.string.speech_show))
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
                TranscriptionLanguageSummary(
                    sttLanguage = initial.sttLanguage,
                    voiceLanguage = initial.effectiveVoiceLanguage.label,
                    onOpenLanguage = onOpenLanguage
                )
            }
            AudioProvider.ANDROID, AudioProvider.NONE -> Unit
        }
        SpeechLanguageWarnings(
            listOfNotNull(
                SpeechLanguageCheck.ttsWarning(
                    ttsProvider,
                    ttsApiModel,
                    ttsVoice,
                    availableTtsModels,
                    initial.effectiveVoiceLanguage
                )
            ) + SpeechLanguageCheck.sttWarnings(
                sttProvider,
                sttApiModel,
                initial.sttLanguage,
                availableSttModels,
                initial.effectiveVoiceLanguage
            )
        )
        SettingsToggleRow(
            label = stringResource(R.string.speech_auto_read_replies_aloud),
            checked = autoReadReplies,
            onCheckedChange = { autoReadReplies = it },
            isLarge = true,
            switchTestTag = "settings_auto_read_replies"
        )
        Button(
            onClick = {
                onSave { applySpeech(it) }
                overlay.show(localContext.getString(R.string.settings_saved))
                status = null
            },
            enabled = speechConfigValid(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.speech_save_speech_settings)) }
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
            value = selectedModel.ifEmpty { stringResource(R.string.speech_select_model) },
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.speech_tts_model)) },
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
                    Text(
                        stringResource(
                            if (refreshing) R.string.speech_refreshing else R.string.speech_refresh_audio_models
                        )
                    )
                },
                onClick = onRefresh
            )
            if (availableModels.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.speech_no_models_found)) },
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
    onClearVoice: () -> Unit,
    label: String = stringResource(R.string.speech_tts_voice_optional)
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
        ?: stringResource(R.string.speech_provider_default_voice)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
        OutlinedTextField(
            value = selectedVoice,
            onValueChange = onSelect,
            label = { Text(label) },
            placeholder = {
                if (hasAnyVoices) {
                    Text(stringResource(R.string.speech_default_voice_or_pick, defaultVoiceLabel))
                } else {
                    Text(stringResource(R.string.speech_type_a_voice_id_picker))
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
                text = { Text(stringResource(R.string.speech_default_clear)) },
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
                    text = { Text(stringResource(R.string.speech_no_voices_suggested_type_a)) },
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
            value = selectedModel.ifEmpty { stringResource(R.string.speech_select_model) },
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.speech_stt_model)) },
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
                    Text(
                        stringResource(
                            if (refreshing) R.string.speech_refreshing else R.string.speech_refresh_audio_models
                        )
                    )
                },
                onClick = onRefresh
            )
            if (availableModels.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.speech_no_models_found)) },
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

/**
 * The transcription language, read-only: it is set on the Language page with the
 * other languages (issue #114), and [onOpenLanguage] goes there.
 */
@Composable
private fun TranscriptionLanguageSummary(
    sttLanguage: String,
    voiceLanguage: String,
    onOpenLanguage: () -> Unit
) {
    val forced = sttLanguage.trim()
    Text(
        if (forced.isEmpty()) {
            stringResource(
                R.string.speech_transcription_follows_voice,
                stringResource(Language.fromLabel(voiceLanguage).nameRes)
            )
        } else {
            stringResource(R.string.speech_transcription_forced, AudioLanguageLabels.label(forced))
        },
        style = MaterialTheme.typography.bodyMedium
    )
    TextButton(
        onClick = onOpenLanguage,
        modifier = Modifier.testTag("settings_speech_open_language")
    ) { Text(stringResource(R.string.speech_change_in_settings_language)) }
}

/** Inline link to the Samosa AI docs on choosing a voice and language. */
@Composable
private fun SpeechDocsLink(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    Text(
        text = stringResource(R.string.speech_how_to_choose_a_voice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                try {
                    uriHandler.openUri(AudioLanguageLabels.SPEECH_DOCS_URL)
                } catch (_: Exception) {
                    Toast.makeText(context, context.getString(R.string.speech_no_app_can_open_the), Toast.LENGTH_SHORT)
                        .show()
                }
            }
    )
}
