package com.gotcha.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.gotcha.audio.AudioModel
import com.gotcha.audio.AudioProvider
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.data.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initial: Settings,
    onSave: (Settings) -> Unit,
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
    packageName: String = ""
) {
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
    var maxContextTokens by remember { mutableStateOf(initial.maxContextTokens.toString()) }
    var apiTimeoutSeconds by remember { mutableStateOf(initial.apiTimeoutSeconds.toString()) }
    // TTS / STT
    var ttsProvider by remember { mutableStateOf(initial.ttsProvider) }
    var ttsApiBaseUrl by remember { mutableStateOf(initial.ttsApiBaseUrl) }
    var sttProvider by remember { mutableStateOf(initial.sttProvider) }
    var sttApiBaseUrl by remember { mutableStateOf(initial.sttApiBaseUrl) }
    var ttsApiModel by remember { mutableStateOf(initial.ttsApiModel) }
    var sttApiModel by remember { mutableStateOf(initial.sttApiModel) }
    var autoReadReplies by remember { mutableStateOf(initial.autoReadReplies) }
    var themeMode by remember { mutableStateOf(initial.themeMode) }
    // Discovered model lists
    var availableTtsModels by remember { mutableStateOf<List<AudioModel>>(emptyList()) }
    var availableSttModels by remember { mutableStateOf<List<AudioModel>>(emptyList()) }
    var availableChatModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var showKey by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var refreshingModels by remember { mutableStateOf(false) }
    var refreshingChatModels by remember { mutableStateOf(false) }
    // Collapsible sections
    var aiConfigExpanded by remember { mutableStateOf(false) }
    var speechExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Dropdown expanded states
    var ttsProviderExpanded by remember { mutableStateOf(false) }
    var sttProviderExpanded by remember { mutableStateOf(false) }
    var ttsModelExpanded by remember { mutableStateOf(false) }
    var sttModelExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var subAgentModelExpanded by remember { mutableStateOf(false) }
    var navigatorModelExpanded by remember { mutableStateOf(false) }

    fun currentSettings() = Settings(
        provider = provider,
        apiKey = apiKey.trim(),
        baseUrl = baseUrl.trim(),
        model = model.trim(),
        samosaSessionToken = samosaToken,
        samosaEmail = samosaEmail,
        subAgentModel = subAgentModel.trim(),
        navigatorModel = navigatorModel.trim(),
        maxToolRounds = maxToolRounds.toIntOrNull()?.takeIf { it > 0 } ?: 300,
        maxContextTokens = maxContextTokens.toIntOrNull()?.takeIf { it > 0 } ?: 70000,
        apiTimeoutSeconds = apiTimeoutSeconds.toLongOrNull()?.takeIf { it >= 0 } ?: 0L,
        ttsProvider = ttsProvider,
        ttsApiBaseUrl = ttsApiBaseUrl.trim(),
        ttsApiModel = ttsApiModel.trim(),
        sttProvider = sttProvider,
        sttApiBaseUrl = sttApiBaseUrl.trim(),
        sttApiModel = sttApiModel.trim(),
        autoReadReplies = autoReadReplies,
        assistiveBallEnabled = initial.assistiveBallEnabled,
        themeMode = themeMode
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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

            // ---- AI Configuration (collapsible, collapsed by default) ----
            SectionHeader(
                title = "AI Configuration",
                expanded = aiConfigExpanded,
                onToggle = { aiConfigExpanded = !aiConfigExpanded }
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
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("Base URL (OpenAI-compatible)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    ExposedDropdownMenuBox(
                        expanded = modelExpanded,
                        onExpandedChange = { modelExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it },
                            readOnly = false,
                            label = { Text("Main model") },
                            placeholder = { Text("(select model)") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = modelExpanded,
                            onDismissRequest = { modelExpanded = false }
                        ) {
                            if (availableChatModels.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No models — refresh below") },
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
                        onExpandedChange = { subAgentModelExpanded = it }
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
                        onExpandedChange = { navigatorModelExpanded = it }
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                refreshingChatModels = true
                                status = "Refreshing models…"
                                scope.launch {
                                    val result = onRefreshChatModels(currentSettings())
                                    result.onSuccess { models ->
                                        availableChatModels = models
                                        status = "Found ${models.size} models"
                                    }.onFailure { e ->
                                        status = "Failed: ${e.message}"
                                    }
                                    refreshingChatModels = false
                                }
                            },
                            enabled = !refreshingChatModels,
                            modifier = Modifier.weight(1f)
                        ) { Text(if (refreshingChatModels) "Refreshing…" else "Refresh models") }
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
                            onSave(currentSettings())
                            status = "Saved."
                        },
                        enabled = when (provider) {
                            LlmProvider.SAMOSA_AI -> samosaToken.isNotBlank() && model.isNotBlank()
                            LlmProvider.OPENAI_COMPATIBLE ->
                                apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Save") }
                    OutlinedButton(
                        onClick = {
                            testing = true
                            status = "Testing connection…"
                            scope.launch {
                                val result = onTestConnection(currentSettings())
                                status = result.fold(
                                    onSuccess = { "✓ Connected: $it" },
                                    onFailure = { "✗ Connection failed: ${it.message}" }
                                )
                                testing = false
                            }
                        },
                        enabled = !testing && when (provider) {
                            LlmProvider.SAMOSA_AI -> samosaToken.isNotBlank()
                            LlmProvider.OPENAI_COMPATIBLE -> apiKey.isNotBlank() && baseUrl.isNotBlank()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Test connection") }
                    OutlinedButton(
                        onClick = {
                            onClearLlmCache()
                            status = "LLM response cache cleared."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Clear LLM cache") }
                    OutlinedButton(
                        onClick = {
                            onClearDebugScreenshots()
                            status = "Debug screenshots cleared."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Clear debug screenshots") }
                }
            }

            HorizontalDivider(thickness = 1.dp)

            // ---- Permissions (collapsible, expanded by default) ----
            PermissionsSection(packageName = packageName)

            HorizontalDivider(thickness = 1.dp)

            // ---- Speech (collapsible, collapsed by default) ----
            SectionHeader(
                title = "Speech (TTS / STT)",
                expanded = speechExpanded,
                onToggle = { speechExpanded = !speechExpanded }
            )
            AnimatedVisibility(visible = speechExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExposedDropdownMenuBox(
                        expanded = ttsProviderExpanded,
                        onExpandedChange = { ttsProviderExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = ttsProvider.name,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("TTS Provider") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ttsProviderExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = ttsProviderExpanded,
                            onDismissRequest = { ttsProviderExpanded = false }
                        ) {
                            AudioProvider.entries.forEach { provider ->
                                DropdownMenuItem(
                                    text = { Text(provider.name) },
                                    onClick = {
                                        ttsProvider = provider
                                        ttsProviderExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    if (ttsProvider == AudioProvider.API) {
                        OutlinedTextField(
                            value = ttsApiBaseUrl,
                            onValueChange = { ttsApiBaseUrl = it },
                            label = { Text("TTS API Base URL") },
                            singleLine = true,
                            placeholder = { Text("http://10.0.2.2:8969/v1") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        ExposedDropdownMenuBox(
                            expanded = ttsModelExpanded,
                            onExpandedChange = { ttsModelExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = ttsApiModel.ifEmpty { "(select model)" },
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("TTS Model") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(
                                        expanded = ttsModelExpanded
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = ttsModelExpanded,
                                onDismissRequest = { ttsModelExpanded = false }
                            ) {
                                if (availableTtsModels.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No models — refresh below") },
                                        onClick = { ttsModelExpanded = false }
                                    )
                                } else {
                                    availableTtsModels.forEach { audioModel ->
                                        DropdownMenuItem(
                                            text = { Text(audioModel.id) },
                                            onClick = {
                                                ttsApiModel = audioModel.id
                                                ttsModelExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    ExposedDropdownMenuBox(
                        expanded = sttProviderExpanded,
                        onExpandedChange = { sttProviderExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = sttProvider.name,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("STT Provider") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sttProviderExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = sttProviderExpanded,
                            onDismissRequest = { sttProviderExpanded = false }
                        ) {
                            AudioProvider.entries.forEach { provider ->
                                DropdownMenuItem(
                                    text = { Text(provider.name) },
                                    onClick = {
                                        sttProvider = provider
                                        sttProviderExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    if (sttProvider == AudioProvider.API) {
                        OutlinedTextField(
                            value = sttApiBaseUrl,
                            onValueChange = { sttApiBaseUrl = it },
                            label = { Text("STT API Base URL") },
                            singleLine = true,
                            placeholder = { Text("http://10.0.2.2:8969/v1") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        ExposedDropdownMenuBox(
                            expanded = sttModelExpanded,
                            onExpandedChange = { sttModelExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = sttApiModel.ifEmpty { "(select model)" },
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("STT Model") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(
                                        expanded = sttModelExpanded
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = sttModelExpanded,
                                onDismissRequest = { sttModelExpanded = false }
                            ) {
                                if (availableSttModels.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No models — refresh below") },
                                        onClick = { sttModelExpanded = false }
                                    )
                                } else {
                                    availableSttModels.forEach { audioModel ->
                                        DropdownMenuItem(
                                            text = { Text(audioModel.id) },
                                            onClick = {
                                                sttApiModel = audioModel.id
                                                sttModelExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Auto-read replies aloud", style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = autoReadReplies, onCheckedChange = { autoReadReplies = it })
                    }
                    OutlinedButton(
                        onClick = {
                            refreshingModels = true
                            status = "Refreshing audio models…"
                            scope.launch {
                                val (tts, stt) = onRefreshAudioModels(currentSettings())
                                availableTtsModels = tts
                                availableSttModels = stt
                                status = "Found ${tts.size} TTS, ${stt.size} STT models"
                                refreshingModels = false
                            }
                        },
                        enabled = !refreshingModels && (ttsApiBaseUrl.isNotBlank() || sttApiBaseUrl.isNotBlank()),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (refreshingModels) "Refreshing…" else "Refresh audio models") }
                    Button(
                        onClick = {
                            onSave(currentSettings())
                            status = "Saved."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Save Speech Settings") }
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
}

@Composable
private fun SamosaAuthSection(
    email: String,
    signedIn: Boolean,
    busy: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (signedIn) "Signed in to Samosa AI" else "Not signed in",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        if (signedIn && email.isNotBlank()) {
            Text(
                text = email,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (!signedIn) {
            Text(
                text = "Sign in with Google to use Samosa AI. Your OpenAI-compatible " +
                    "settings are kept separately and are unaffected.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (signedIn) {
            OutlinedButton(
                onClick = onSignOut,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Please wait…" else "Log out") }
        } else {
            Button(
                onClick = onSignIn,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Signing in…" else "Sign in with Google") }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
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
