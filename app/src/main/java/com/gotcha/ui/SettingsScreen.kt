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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.gotcha.data.Settings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initial: Settings,
    onSave: (Settings) -> Unit,
    onTestConnection: suspend (Settings) -> Result<String>,
    onClearLlmCache: () -> Unit,
    onBack: () -> Unit
) {
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var confirmSensitive by remember { mutableStateOf(initial.confirmSensitiveActions) }
    var maxToolRounds by remember { mutableStateOf(initial.maxToolRounds.toString()) }
    var showKey by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun currentSettings() = Settings(
        apiKey = apiKey.trim(),
        baseUrl = baseUrl.trim(),
        model = model.trim(),
        confirmSensitiveActions = confirmSensitive,
        maxToolRounds = maxToolRounds.toIntOrNull()?.takeIf { it > 0 } ?: 30
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Confirm sensitive actions", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = confirmSensitive, onCheckedChange = { confirmSensitive = it })
            }

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
