package com.gotcha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.gotcha.audio.AudioModel
import com.gotcha.audio.AudioProvider
import com.gotcha.data.Settings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initial: Settings,
    onSave: (Settings) -> Unit,
    onTestConnection: suspend (Settings) -> Result<String>,
    onClearLlmCache: () -> Unit,
    onBack: () -> Unit,
    onRefreshAudioModels: suspend (Settings) -> Pair<List<AudioModel>, List<AudioModel>> = { Pair(emptyList(), emptyList()) }
) {
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var confirmSensitive by remember { mutableStateOf(initial.confirmSensitiveActions) }
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
    // Discovered model lists
    var availableTtsModels by remember { mutableStateOf<List<AudioModel>>(emptyList()) }
    var availableSttModels by remember { mutableStateOf<List<AudioModel>>(emptyList()) }
    var showKey by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var refreshingModels by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Dropdown expanded states
    var ttsProviderExpanded by remember { mutableStateOf(false) }
    var sttProviderExpanded by remember { mutableStateOf(false) }
    var ttsModelExpanded by remember { mutableStateOf(false) }
    var sttModelExpanded by remember { mutableStateOf(false) }

    fun currentSettings() = Settings(
        apiKey = apiKey.trim(),
        baseUrl = baseUrl.trim(),
        model = model.trim(),
        confirmSensitiveActions = confirmSensitive,
        maxToolRounds = maxToolRounds.toIntOrNull()?.takeIf { it > 0 } ?: 30,
        maxContextTokens = maxContextTokens.toIntOrNull()?.takeIf { it > 0 } ?: 40000,
        apiTimeoutSeconds = apiTimeoutSeconds.toLongOrNull()?.takeIf { it >= 0 } ?: 0L,
        ttsProvider = ttsProvider,
        ttsApiBaseUrl = ttsApiBaseUrl.trim(),
        ttsApiModel = ttsApiModel.trim(),
        sttProvider = sttProvider,
        sttApiBaseUrl = sttApiBaseUrl.trim(),
        sttApiModel = sttApiModel.trim(),
        autoReadReplies = autoReadReplies
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
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None
                else PasswordVisualTransformation(),
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
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Confirm sensitive actions", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = confirmSensitive, onCheckedChange = { confirmSensitive = it })
            }

            HorizontalDivider(thickness = 1.dp)
            Text("Speech (TTS / STT)", style = MaterialTheme.typography.titleMedium)

            // TTS provider dropdown
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

                // TTS model dropdown (populated after refresh)
                ExposedDropdownMenuBox(
                    expanded = ttsModelExpanded,
                    onExpandedChange = { ttsModelExpanded = it }
                ) {
                    OutlinedTextField(
                        value = ttsApiModel.ifEmpty { "(select model)" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("TTS Model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ttsModelExpanded) },
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

            // STT provider dropdown
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

                // STT model dropdown
                ExposedDropdownMenuBox(
                    expanded = sttModelExpanded,
                    onExpandedChange = { sttModelExpanded = it }
                ) {
                    OutlinedTextField(
                        value = sttApiModel.ifEmpty { "(select model)" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("STT Model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sttModelExpanded) },
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

            // Save / actions
            HorizontalDivider(thickness = 1.dp)

            Button(
                onClick = {
                    onSave(currentSettings())
                    status = "Saved."
                },
                enabled = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank(),
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
                enabled = !testing && apiKey.isNotBlank() && baseUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Test connection") }

            OutlinedButton(
                onClick = {
                    onClearLlmCache()
                    status = "LLM response cache cleared."
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Clear LLM cache") }

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
