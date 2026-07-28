package com.gotcha.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.gotcha.agent.skills.Skill
import com.gotcha.agent.skills.SkillRegistry
import com.gotcha.audio.AudioModel
import com.gotcha.audio.AudioProvider
import com.gotcha.audio.CompletionFeedback
import com.gotcha.audio.VoiceInfo
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.data.ThemeMode
import com.gotcha.i18n.Language
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /** Reads the persisted settings. Called once to seed the form, and again
     *  whenever a section saves, so a section always writes onto current storage
     *  rather than onto whatever was on screen when this composable started. */
    load: () -> Settings,
    /**
     * Persists one section's fields. The section supplies a mutator that copies
     * *only* the fields it owns onto the freshly-loaded [Settings], so saving in
     * one section can no longer drag another section's half-typed edits into
     * storage with it.
     */
    onSave: ((Settings) -> Settings) -> Unit,
    onTestConnection: suspend (Settings) -> Result<String>,
    onClearLlmCache: () -> Unit,
    onClearDebugScreenshots: () -> Unit,
    onBack: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit = {},
    onRefreshAudioModels: suspend (Settings) -> Pair<List<AudioModel>, List<AudioModel>> = {
        Pair(
            emptyList(),
            emptyList()
        )
    },
    onRefreshChatModels: suspend (Settings) -> Result<List<String>> = { Result.failure(Exception("Not available")) },
    /** Runs the Samosa Google Sign-In flow; returns (email, sessionToken) or an error. */
    onSamosaSignIn: suspend () -> Result<Pair<String, String>> = { Result.failure(Exception("Not available")) },
    /** Logs out of Samosa (clears JWT + Google state). */
    onSamosaSignOut: suspend () -> Unit = {},
    /**
     * Speaks the call-started phrase through the host's TTS engine and reports
     * whether the requested language was actually used. Returning null means
     * TTS isn't configured and the button should be a no-op. The default
     * (synchronous, always-true) lets callers ignore voice testing.
     */
    onTestVoice: suspend (Language) -> Boolean? = { null },
    packageName: String = ""
) {
    // Seeds the form once. Saves go through onSave's mutator against a fresh
    // load(), so this snapshot is never written back wholesale.
    val initial = remember { load() }
    var provider by remember { mutableStateOf(initial.provider) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    // Samosa auth state, kept live as the user signs in / out.
    var samosaToken by remember { mutableStateOf(initial.samosaSessionToken) }
    var samosaEmail by remember { mutableStateOf(initial.samosaEmail) }
    var samosaBusy by remember { mutableStateOf(false) }
    var providerExpanded by remember { mutableStateOf(false) }
    var subAgentModel by remember { mutableStateOf(initial.subAgentModel) }
    var navigatorModel by remember { mutableStateOf(initial.navigatorModel) }
    var maxToolRounds by remember { mutableStateOf(initial.maxToolRounds.toString()) }
    var maxRepeatedToolCalls by remember { mutableStateOf(initial.maxRepeatedToolCalls.toString()) }
    var maxNavigationToolCalls by remember { mutableStateOf(initial.maxNavigationToolCalls.toString()) }
    var maxConsecutiveDelegations by remember { mutableStateOf(initial.maxConsecutiveDelegations.toString()) }
    var maxContextTokens by remember { mutableStateOf(initial.maxContextTokens.toString()) }
    var apiTimeoutSeconds by remember { mutableStateOf(initial.apiTimeoutSeconds.toString()) }
    // TTS / STT
    var ttsProvider by remember { mutableStateOf(initial.ttsProvider) }
    var ttsApiBaseUrl by remember { mutableStateOf(initial.ttsApiBaseUrl) }
    var ttsApiKey by remember { mutableStateOf(initial.ttsApiKey) }
    var sttProvider by remember { mutableStateOf(initial.sttProvider) }
    var sttApiBaseUrl by remember { mutableStateOf(initial.sttApiBaseUrl) }
    var sttApiKey by remember { mutableStateOf(initial.sttApiKey) }
    var ttsApiModel by remember { mutableStateOf(initial.ttsApiModel) }
    var ttsVoice by remember { mutableStateOf(initial.ttsVoice) }
    var sttApiModel by remember { mutableStateOf(initial.sttApiModel) }
    var sttLanguage by remember { mutableStateOf(initial.sttLanguage) }
    var autoReadReplies by remember { mutableStateOf(initial.autoReadReplies) }
    var notifyVibration by remember { mutableStateOf(initial.notifyVibrationEnabled) }
    var notifyChime by remember { mutableStateOf(initial.notifyChimeEnabled) }
    var themeMode by remember { mutableStateOf(initial.themeMode) }
    var disabledSkills by remember { mutableStateOf(initial.disabledSkills) }
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
    var communitySkillHosts by remember { mutableStateOf(initial.communitySkillHosts) }
    var communitySkillUrl by remember { mutableStateOf("") }
    var communitySkillPasteJson by remember { mutableStateOf("") }
    var communityImportBusy by remember { mutableStateOf(false) }
    var communitySkillRefreshTick by remember { mutableStateOf(0) }
    var communitySkillToDelete by remember { mutableStateOf<Skill?>(null) }
    val localContext = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // Defense-in-depth: ChatViewModel also calls SkillRegistry.init, but
        // Settings can be opened before the chat screen is ever shown.
        SkillRegistry.bootstrap(localContext)
    }

    // Discovered model lists
    var availableTtsModels by remember { mutableStateOf<List<AudioModel>>(emptyList()) }
    var availableSttModels by remember { mutableStateOf<List<AudioModel>>(emptyList()) }
    var availableChatModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var showKey by remember { mutableStateOf(false) }
    var showTtsKey by remember { mutableStateOf(false) }
    var showSttKey by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val overlay = rememberSettingsOverlayState()
    var testing by remember { mutableStateOf(false) }
    var refreshingModels by remember { mutableStateOf(false) }
    var refreshingChatModels by remember { mutableStateOf(false) }
    // Collapsible sections
    var aiConfigExpanded by remember { mutableStateOf(false) }
    var speechExpanded by remember { mutableStateOf(false) }
    var skillsExpanded by remember { mutableStateOf(false) }
    var proactiveExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Dropdown expanded states
    var ttsProviderExpanded by remember { mutableStateOf(false) }
    var sttProviderExpanded by remember { mutableStateOf(false) }
    var ttsModelExpanded by remember { mutableStateOf(false) }
    var ttsVoiceExpanded by remember { mutableStateOf(false) }
    var sttModelExpanded by remember { mutableStateOf(false) }
    var sttLanguageExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var subAgentModelExpanded by remember { mutableStateOf(false) }
    var navigatorModelExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    var currencyExpanded by remember { mutableStateOf(false) }

    /**
     * The AI Configuration section's fields, copied onto [base].
     *
     * Deliberately does not write `samosaSessionToken` / `samosaEmail`: those are
     * owned by `SamosaAuthManager`, which persists them itself on sign-in and
     * clears them on a 401. Writing the form's copy back would resurrect a
     * session that expired while this screen was open.
     */
    fun applyAiConfig(base: Settings) = base.copy(
        provider = provider,
        apiKey = apiKey.trim(),
        baseUrl = baseUrl.trim(),
        model = model.trim(),
        subAgentModel = subAgentModel.trim(),
        navigatorModel = navigatorModel.trim(),
        maxToolRounds = maxToolRounds.toIntOrNull()?.takeIf { it > 0 } ?: 300,
        maxRepeatedToolCalls = maxRepeatedToolCalls.toIntOrNull()?.takeIf { it > 0 } ?: 20,
        maxNavigationToolCalls = maxNavigationToolCalls.toIntOrNull()?.takeIf { it > 0 } ?: 30,
        maxConsecutiveDelegations = maxConsecutiveDelegations.toIntOrNull()?.takeIf { it > 0 } ?: 3,
        maxContextTokens = maxContextTokens.toIntOrNull()?.takeIf { it > 0 } ?: 70000,
        apiTimeoutSeconds = apiTimeoutSeconds.toLongOrNull()?.takeIf { it >= 0 } ?: 0L
    )

    /** The Speech section's fields, copied onto [base]. */
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

    /** The Skills section's fields, copied onto [base]. */
    fun applySkills(base: Settings) = base.copy(
        disabledSkills = disabledSkills,
        communitySkillHosts = communitySkillHosts
    )

    /** The Proactive Assistance section's fields, copied onto [base]. */
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

    /**
     * The AI settings as they stand on screen, for calls that must see unsaved
     * edits (connection tests, model discovery) rather than what is in storage.
     */
    fun draftAiConfig(): Settings = applyAiConfig(load())

    /** As [draftAiConfig], for audio-model discovery against unsaved TTS/STT edits. */
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

    val refreshChatModelsAction = {
        if (!refreshingChatModels) {
            refreshingChatModels = true
            status = "Refreshing models…"
            scope.launch {
                val result = onRefreshChatModels(draftAiConfig())
                result.onSuccess { models ->
                    availableChatModels = models
                    status = "Found ${models.size} models"
                }.onFailure { e ->
                    status = "Failed: ${e.message}"
                }
                refreshingChatModels = false
            }
        }
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

    SettingsScaffold(title = "Settings", onBack = onBack, overlay = overlay) {
        // ---- Appearance (always visible, applies immediately) ----
        Text(
            "Appearance",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ThemeMode.values().forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = themeMode == mode,
                    onClick = {
                        themeMode = mode
                        onThemeChange(mode)
                    },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = ThemeMode.values().size
                    )
                ) { Text(mode.label) }
            }
        }

        HorizontalDivider(thickness = 1.dp)

        // ---- Notifications (always visible, applies immediately) ----
        Text(
            "Notifications",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Played as soon as a reply arrives. Turn both off for no alert.",
            style = MaterialTheme.typography.bodySmall
        )
        // Switching one on plays it once, so the user knows what to expect.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Vibration", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = notifyVibration,
                onCheckedChange = {
                    notifyVibration = it
                    onSave { s -> s.copy(notifyVibrationEnabled = it) }
                    if (it) CompletionFeedback.replyArrived(localContext, vibrate = true, chime = false)
                },
                modifier = Modifier.testTag("settings_notify_vibration")
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Chime", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = notifyChime,
                onCheckedChange = {
                    notifyChime = it
                    onSave { s -> s.copy(notifyChimeEnabled = it) }
                    if (it) CompletionFeedback.replyArrived(localContext, vibrate = false, chime = true)
                },
                modifier = Modifier.testTag("settings_notify_chime")
            )
        }

        HorizontalDivider(thickness = 1.dp)

        // ---- AI Configuration (collapsible, collapsed by default) ----
        SectionHeader(
            title = "AI Configuration",
            expanded = aiConfigExpanded,
            onToggle = { aiConfigExpanded = !aiConfigExpanded },
            modifier = Modifier.testTag("settings_ai_config_header")
        )
        AnimatedVisibility(visible = aiConfigExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // ---- LLM provider selector ----
                ExposedDropdownMenuBox(
                    expanded = providerExpanded,
                    onExpandedChange = { providerExpanded = it }
                ) {
                    OutlinedTextField(
                        value = provider.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("LLM Provider") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded)
                        },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = providerExpanded,
                        onDismissRequest = { providerExpanded = false }
                    ) {
                        LlmProvider.entries.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.label) },
                                onClick = {
                                    provider = p
                                    providerExpanded = false
                                }
                            )
                        }
                    }
                }

                if (provider == LlmProvider.SAMOSA_AI) {
                    // ---- Samosa AI: Google sign-in (no Base URL / API key) ----
                    SamosaAuthSection(
                        email = samosaEmail,
                        signedIn = samosaToken.isNotBlank(),
                        busy = samosaBusy,
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
                                availableChatModels = emptyList()
                                status = "Signed out of Samosa AI."
                                samosaBusy = false
                            }
                        }
                    )
                } else {
                    // ---- OpenAI-compatible: Base URL + API key (unchanged) ----
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API key") },
                        singleLine = true,
                        visualTransformation = if (showKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            TextButton(onClick = { showKey = !showKey }) {
                                Text(if (showKey) "Hide" else "Show")
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("settings_api_key")
                    )
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL (OpenAI-compatible)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_base_url")
                    )
                }
                ExposedDropdownMenuBox(
                    expanded = modelExpanded,
                    onExpandedChange = {
                        modelExpanded = it
                        if (it) refreshChatModelsAction()
                    }
                ) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        readOnly = false,
                        label = { Text("Main model") },
                        placeholder = { Text("(select model)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor().testTag("settings_model")
                    )
                    ExposedDropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (refreshingChatModels) "Refreshing…" else "🔄 Refresh models…") },
                            onClick = { refreshChatModelsAction() }
                        )
                        if (availableChatModels.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No models found") },
                                onClick = { modelExpanded = false }
                            )
                        } else {
                            availableChatModels.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m) },
                                    onClick = {
                                        model = m
                                        modelExpanded = false
                                    }
                                )
                            }
                        }
                        // Always allow manual text input
                        DropdownMenuItem(
                            text = { Text("✏️ Custom model…") },
                            onClick = {
                                modelExpanded = false
                            }
                        )
                    }
                }
                ExposedDropdownMenuBox(
                    expanded = subAgentModelExpanded,
                    onExpandedChange = {
                        subAgentModelExpanded = it
                        if (it) refreshChatModelsAction()
                    }
                ) {
                    val subLabel = if (subAgentModel.isBlank()) "Same as main agent" else subAgentModel
                    OutlinedTextField(
                        value = subLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Sub-agent model") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = subAgentModelExpanded
                            )
                        },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = subAgentModelExpanded,
                        onDismissRequest = { subAgentModelExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (refreshingChatModels) "Refreshing…" else "🔄 Refresh models…") },
                            onClick = { refreshChatModelsAction() }
                        )
                        DropdownMenuItem(
                            text = { Text("Same as main agent") },
                            onClick = {
                                subAgentModel = ""
                                subAgentModelExpanded = false
                            }
                        )
                        if (availableChatModels.isNotEmpty()) {
                            availableChatModels.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m) },
                                    onClick = {
                                        subAgentModel = m
                                        subAgentModelExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                ExposedDropdownMenuBox(
                    expanded = navigatorModelExpanded,
                    onExpandedChange = {
                        navigatorModelExpanded = it
                        if (it) refreshChatModelsAction()
                    }
                ) {
                    val navLabel = if (navigatorModel.isBlank()) "Same as main model" else navigatorModel
                    OutlinedTextField(
                        value = navLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Navigator model") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = navigatorModelExpanded
                            )
                        },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = navigatorModelExpanded,
                        onDismissRequest = { navigatorModelExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (refreshingChatModels) "Refreshing…" else "🔄 Refresh models…") },
                            onClick = { refreshChatModelsAction() }
                        )
                        DropdownMenuItem(
                            text = { Text("Same as main model") },
                            onClick = {
                                navigatorModel = ""
                                navigatorModelExpanded = false
                            }
                        )
                        if (availableChatModels.isNotEmpty()) {
                            availableChatModels.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m) },
                                    onClick = {
                                        navigatorModel = m
                                        navigatorModelExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = maxToolRounds,
                    onValueChange = { maxToolRounds = it },
                    label = { Text("Max tool rounds") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = maxRepeatedToolCalls,
                    onValueChange = { maxRepeatedToolCalls = it },
                    label = { Text("Max repeated tool calls") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = maxNavigationToolCalls,
                    onValueChange = { maxNavigationToolCalls = it },
                    label = { Text("Max navigation tool calls") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = maxConsecutiveDelegations,
                    onValueChange = { maxConsecutiveDelegations = it },
                    label = { Text("Max consecutive delegations") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = maxContextTokens,
                    onValueChange = { maxContextTokens = it },
                    label = { Text("Max context tokens") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiTimeoutSeconds,
                    onValueChange = { apiTimeoutSeconds = it },
                    label = { Text("API Timeout (seconds, 0 for infinite)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        onSave { applyAiConfig(it) }
                        overlay.show("Saved.")
                        status = null
                    },
                    enabled = when (provider) {
                        LlmProvider.SAMOSA_AI -> samosaToken.isNotBlank() && model.isNotBlank()
                        LlmProvider.OPENAI_COMPATIBLE -> baseUrl.isNotBlank() && model.isNotBlank()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("settings_save")
                ) { Text("Save") }
                OutlinedButton(
                    onClick = {
                        testing = true
                        // Sticky while the request is in flight, then the result
                        // replaces it and fades out on its own.
                        overlay.show("Testing connection…", sticky = true)
                        status = null
                        scope.launch {
                            val result = onTestConnection(draftAiConfig())
                            overlay.show(
                                result.fold(
                                    onSuccess = { "✓ Connected: $it" },
                                    onFailure = { "✗ Connection failed: ${it.message}" }
                                )
                            )
                            testing = false
                        }
                    },
                    enabled = !testing && when (provider) {
                        LlmProvider.SAMOSA_AI -> samosaToken.isNotBlank()
                        LlmProvider.OPENAI_COMPATIBLE -> baseUrl.isNotBlank()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Test connection") }
                OutlinedButton(
                    onClick = {
                        onClearLlmCache()
                        overlay.show("LLM response cache cleared.")
                        status = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Clear LLM cache") }
                OutlinedButton(
                    onClick = {
                        onClearDebugScreenshots()
                        overlay.show("Debug screenshots cleared.")
                        status = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Clear debug screenshots") }
            }
        }

        HorizontalDivider(thickness = 1.dp)

        // ---- Speech (collapsible, collapsed by default) ----
        SectionHeader(
            title = "Speech (TTS / STT)",
            expanded = speechExpanded,
            onToggle = { speechExpanded = !speechExpanded }
        )
        AnimatedVisibility(visible = speechExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val samosaAudioSelected = ttsProvider == AudioProvider.SAMOSA_AI ||
                    sttProvider == AudioProvider.SAMOSA_AI
                if (samosaAudioSelected) {
                    SamosaAuthSection(
                        email = samosaEmail,
                        signedIn = samosaToken.isNotBlank(),
                        busy = samosaBusy,
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
                    ExposedDropdownMenu(
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
                            placeholder = { Text("http://10.0.2.2:8969/v1") },
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
                    ExposedDropdownMenu(
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
                            placeholder = { Text("http://10.0.2.2:8969/v1") },
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
            }
        }

        HorizontalDivider(thickness = 1.dp)

        // ---- Permissions ----
        PermissionsSection(packageName = packageName)

        HorizontalDivider(thickness = 1.dp)

        // ---- Skills (collapsible, collapsed by default) ----
        SectionHeader(
            title = "Skills / Plugins",
            expanded = skillsExpanded,
            onToggle = { skillsExpanded = !skillsExpanded }
        )
        AnimatedVisibility(visible = skillsExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val allSkills = SkillRegistry.getAllSkills()
                if (allSkills.isEmpty()) {
                    Text("No skills loaded.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    allSkills.forEach { skill ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    skill.id,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                if (skill.description.isNotBlank()) {
                                    Text(skill.description, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Switch(
                                checked = !disabledSkills.contains(skill.id),
                                onCheckedChange = { enabled ->
                                    disabledSkills = if (enabled) {
                                        disabledSkills - skill.id
                                    } else {
                                        disabledSkills + skill.id
                                    }
                                    onSave { applySkills(it) }
                                }
                            )
                        }
                    }
                }

                HorizontalDivider(thickness = 1.dp)

                // ---- Community Skills ----
                Text(
                    "Community Skills",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Import skills from samosa-ai.example or paste JSON. " +
                        "Community skills appear in the agent's system prompt " +
                        "as advisory guidance.",
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedTextField(
                    value = communitySkillUrl,
                    onValueChange = { communitySkillUrl = it.trim() },
                    label = { Text("Skill URL (https://samosa-ai.example/...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (communityImportBusy) return@Button
                        val url = communitySkillUrl.trim()
                        if (url.isEmpty()) {
                            overlay.show("Enter a URL first.")
                            return@Button
                        }
                        communityImportBusy = true
                        overlay.show("Fetching skill…", sticky = true)
                        scope.launch {
                            val result = runCatching {
                                val hosts = communitySkillHosts
                                SkillRegistry.importCommunityFromUrl(url, hosts)
                            }
                            communityImportBusy = false
                            result.onSuccess { skill ->
                                communitySkillUrl = ""
                                communitySkillRefreshTick++
                                overlay.show("Imported '${skill.id}'.")
                            }.onFailure { e ->
                                overlay.show(formatImportError(e))
                            }
                        }
                    },
                    enabled = !communityImportBusy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Import from URL") }

                var pasteOpen by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { pasteOpen = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Paste JSON…") }
                if (pasteOpen) {
                    androidx.compose.ui.window.Dialog(onDismissRequest = {
                        pasteOpen = false
                        communitySkillPasteJson = ""
                    }) {
                        Surface(shape = MaterialTheme.shapes.medium) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "Paste community skill JSON",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                OutlinedTextField(
                                    value = communitySkillPasteJson,
                                    onValueChange = { communitySkillPasteJson = it },
                                    label = { Text("Skill JSON") },
                                    modifier = Modifier.fillMaxWidth().height(220.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                                )
                                Row(
                                    horizontalArrangement = Arrangement.End,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    TextButton(onClick = {
                                        pasteOpen = false
                                        communitySkillPasteJson = ""
                                    }) { Text("Cancel") }
                                    TextButton(onClick = {
                                        val src = communitySkillPasteJson.trim()
                                        if (src.isEmpty()) {
                                            overlay.show("Paste JSON first.")
                                            return@TextButton
                                        }
                                        communityImportBusy = true
                                        pasteOpen = false
                                        overlay.show("Importing skill…", sticky = true)
                                        scope.launch {
                                            val result = runCatching {
                                                SkillRegistry.importCommunity(src)
                                            }
                                            communityImportBusy = false
                                            communitySkillPasteJson = ""
                                            result.onSuccess { skill ->
                                                communitySkillRefreshTick++
                                                overlay.show(
                                                    "Imported '${skill.id}'."
                                                )
                                            }.onFailure { e ->
                                                overlay.show(formatImportError(e))
                                            }
                                        }
                                    }) { Text("Import") }
                                }
                            }
                        }
                    }
                }

                // ---- Imported list ----
                val communitySkills = remember(communitySkillRefreshTick) {
                    SkillRegistry.getCommunitySkills()
                }
                if (communitySkills.isEmpty()) {
                    Text(
                        "No community skills imported yet.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    communitySkills.forEach { skill ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        if (skill.title.isNotBlank()) skill.title else skill.id,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "id: ${skill.id}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (skill.description.isNotBlank()) {
                                        Text(
                                            skill.description,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                Switch(
                                    checked = !disabledSkills.contains(skill.id),
                                    onCheckedChange = { enabled ->
                                        disabledSkills = if (enabled) {
                                            disabledSkills - skill.id
                                        } else {
                                            disabledSkills + skill.id
                                        }
                                        onSave { applySkills(it) }
                                    }
                                )
                                IconButton(
                                    onClick = { communitySkillToDelete = skill }
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "Delete ${skill.id}",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                // ---- Delete confirmation dialog ----
                communitySkillToDelete?.let { pending ->
                    AlertDialog(
                        onDismissRequest = { communitySkillToDelete = null },
                        title = { Text("Delete community skill?") },
                        text = {
                            Text(
                                "Are you sure you want to permanently delete " +
                                    "\"${pending.id}\"? The skill will be removed from " +
                                    "this device and the agent will no longer have " +
                                    "access to it. This action cannot be undone."
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val id = pending.id
                                    communitySkillToDelete = null
                                    scope.launch {
                                        runCatching { SkillRegistry.removeCommunity(id) }
                                        disabledSkills = disabledSkills - id
                                        onSave { applySkills(it) }
                                        communitySkillRefreshTick++
                                        overlay.show("Deleted '$id'.")
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) { Text("Delete") }
                        },
                        dismissButton = {
                            TextButton(onClick = { communitySkillToDelete = null }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                HorizontalDivider(thickness = 1.dp)

                // ---- Host allowlist ----
                Text(
                    "Allowed community skill hosts",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Only HTTPS hosts in this list can be fetched. " +
                        "Default: samosa-ai.example.",
                    style = MaterialTheme.typography.bodySmall
                )
                communitySkillHosts.forEach { host ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(host, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            communitySkillHosts = communitySkillHosts - host
                            onSave { applySkills(it) }
                        }) { Text("Remove") }
                    }
                }
                var newHost by remember { mutableStateOf("") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newHost,
                        onValueChange = { newHost = it.trim() },
                        label = { Text("Add host") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val h = newHost.trim()
                            if (h.isEmpty()) return@Button
                            communitySkillHosts = communitySkillHosts + h
                            onSave { applySkills(it) }
                            newHost = ""
                        }
                    ) { Text("Add") }
                }
            }
        }

        HorizontalDivider(thickness = 1.dp)

        // ---- Proactive Assistance ----
        SectionHeader(
            title = "Proactive Assistance",
            expanded = proactiveExpanded,
            onToggle = { proactiveExpanded = !proactiveExpanded }
        )
        AnimatedVisibility(visible = proactiveExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        status = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save Proactive Settings") }
            }
        }

        // Status text
        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }

        Text(
            "The API key is stored encrypted on this device and never leaves it " +
                "except in requests to the base URL above.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (expanded) "▼ " else "▶ ",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
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
        ExposedDropdownMenu(
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
        ExposedDropdownMenu(
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
        ExposedDropdownMenu(
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
        ExposedDropdownMenu(
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
