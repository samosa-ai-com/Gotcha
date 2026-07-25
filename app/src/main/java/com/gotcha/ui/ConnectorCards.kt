package com.gotcha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gotcha.connectors.oauth.OAuthConnectFlow
import kotlinx.coroutines.launch

/**
 * The two reusable Settings → Connectors card shapes.
 *
 * [TokenConnectorCard] — a set of text fields plus Connect/Disconnect, for
 * connectors authenticated by a pasted secret (IMAP app password, Notion
 * internal integration token).
 *
 * [OAuthConnectorCard] — client credentials plus the loopback consent flow and
 * its paste-URL fallback, for BYO-OAuth connectors (Google, Microsoft).
 *
 * Connectors own their own credential storage, so these talk to the connector
 * objects directly — there is nothing for SettingsRepository to persist.
 */

/** Title + live status line + explanatory blurb and numbered setup steps. */
@Composable
private fun CardHeader(title: String, status: String, blurb: String?, steps: List<String>) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    Text(status, style = MaterialTheme.typography.bodyMedium)
    if (blurb != null) Text(blurb, style = MaterialTheme.typography.bodySmall)
    if (steps.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            steps.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun TokenConnectorCard(
    title: String,
    statusLine: () -> String,
    isConnected: () -> Boolean,
    fields: List<TokenFieldState>,
    /** Performs the connect (may hit the network) and returns the status message to show. */
    onConnect: suspend () -> String,
    onDisconnect: () -> Unit,
    blurb: String? = null,
    steps: List<String> = emptyList(),
    canConnect: () -> Boolean = { fields.all { it.value.isNotBlank() } },
    aboveFields: @Composable () -> Unit = {},
    belowFields: @Composable () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    // Bumped after connect/disconnect so statusLine() is re-read.
    var refreshTick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        key(refreshTick) { CardHeader(title, statusLine(), blurb, steps) }

        if (!isConnected()) {
            aboveFields()
            fields.forEach { field ->
                OutlinedTextField(
                    value = field.value,
                    onValueChange = { field.value = it },
                    label = { Text(field.label) },
                    singleLine = true,
                    visualTransformation = if (field.secret) {
                        PasswordVisualTransformation()
                    } else {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = field.keyboard),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            belowFields()
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        status = onConnect()
                        busy = false
                        refreshTick++
                    }
                },
                enabled = !busy && canConnect(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Connecting…" else "Connect") }
        } else {
            OutlinedButton(
                onClick = {
                    onDisconnect()
                    fields.filter { it.secret }.forEach { it.value = "" }
                    status = "Disconnected."
                    refreshTick++
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Disconnect") }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun OAuthConnectorCard(
    title: String,
    statusLine: () -> String,
    isConnected: () -> Boolean,
    needsReconnect: () -> Boolean,
    initialClientId: String,
    /** Null for public clients (Microsoft) — the secret field is hidden entirely. */
    initialClientSecret: String?,
    flow: OAuthConnectFlow,
    onDisconnect: () -> Unit,
    blurb: String? = null,
    steps: List<String> = emptyList(),
    extraFields: @Composable () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableStateOf(0) }
    var clientId by remember { mutableStateOf(initialClientId) }
    var clientSecret by remember { mutableStateOf(initialClientSecret.orEmpty()) }
    var pastedUrl by remember { mutableStateOf("") }
    var showPasteFallback by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val usesSecret = initialClientSecret != null

    fun apply(outcome: OAuthConnectFlow.Outcome) {
        status = when (outcome) {
            is OAuthConnectFlow.Outcome.Connected -> "Connected as ${outcome.account}."
            is OAuthConnectFlow.Outcome.Failed -> outcome.message
        }
        busy = false
        refreshTick++
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        key(refreshTick) { CardHeader(title, statusLine(), blurb, steps) }

        if (!isConnected() || needsReconnect()) {
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                label = { Text("Client ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (usesSecret) {
                OutlinedTextField(
                    value = clientSecret,
                    onValueChange = { clientSecret = it },
                    label = { Text("Client secret") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            extraFields()
            Button(
                onClick = {
                    busy = true
                    status = "Opening sign-in…"
                    scope.launch {
                        apply(flow.connect(clientId, clientSecret.takeIf { usesSecret }))
                    }
                },
                enabled = !busy && clientId.isNotBlank() && (!usesSecret || clientSecret.isNotBlank()),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Waiting for sign-in…" else "Connect") }

            TextButton(onClick = { showPasteFallback = !showPasteFallback }) {
                Text(
                    if (showPasteFallback) {
                        "Hide paste-URL fallback"
                    } else {
                        "Browser didn't return? Paste redirect URL"
                    }
                )
            }
            if (showPasteFallback) {
                OutlinedTextField(
                    value = pastedUrl,
                    onValueChange = { pastedUrl = it },
                    label = { Text("Pasted redirect URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            apply(
                                flow.connectWithPastedUrl(
                                    clientId,
                                    clientSecret.takeIf { usesSecret },
                                    pastedUrl
                                )
                            )
                        }
                    },
                    enabled = !busy && pastedUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Finish sign-in with pasted URL") }
            }
        } else {
            OutlinedButton(
                onClick = {
                    onDisconnect()
                    clientSecret = ""
                    status = "Disconnected."
                    refreshTick++
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Disconnect") }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
