package com.gotcha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gotcha.connectors.ConnectorRegistry
import com.gotcha.connectors.google.GoogleOAuthFlow
import com.gotcha.connectors.imap.ImapCredentials
import kotlinx.coroutines.launch

/**
 * Settings → Connectors: one card per connector (IMAP app-password, Gmail
 * BYO-OAuth). Talks to [ConnectorRegistry] directly since connectors own their
 * own credential storage — there is nothing for the Settings/SettingsRepository
 * layer to persist.
 */
@Composable
fun ConnectorsSection() {
    val context = LocalContext.current
    remember { ConnectorRegistry.init(context) }
    val imap = remember { ConnectorRegistry.byId("imap") as com.gotcha.connectors.imap.ImapConnector }
    val google = remember { ConnectorRegistry.byId("google") as com.gotcha.connectors.google.GoogleConnector }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ImapCard(imap)
        HorizontalDivider(thickness = 1.dp)
        GoogleCard(google, context)
    }
}

@Composable
private fun ImapCard(imap: com.gotcha.connectors.imap.ImapConnector) {
    var refreshTick by remember { mutableStateOf(0) }
    var email by remember { mutableStateOf(imap.credentials()?.email ?: "") }
    var appPassword by remember { mutableStateOf("") }
    var imapHost by remember { mutableStateOf(imap.credentials()?.imapHost ?: "imap.gmail.com") }
    var imapPort by remember { mutableStateOf((imap.credentials()?.imapPort ?: 993).toString()) }
    var smtpHost by remember { mutableStateOf(imap.credentials()?.smtpHost ?: "smtp.gmail.com") }
    var smtpPort by remember { mutableStateOf((imap.credentials()?.smtpPort ?: 465).toString()) }
    var status by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Email (IMAP)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Text(imap.statusLine(), style = MaterialTheme.typography.bodyMedium)

        if (!imap.isConnected()) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email address") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = appPassword,
                onValueChange = { appPassword = it },
                label = { Text("App password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Needs an app password, not your regular password. Requires " +
                        "2-Step Verification to be enabled first.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Generate one at myaccount.google.com/apppasswords, or via " +
                        "Google Account ▸ Security ▸ 2-Step Verification ▸ App passwords.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Other providers: check their IMAP app-password documentation.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = {
                imapHost = "imap.gmail.com"
                imapPort = "993"
                smtpHost = "smtp.gmail.com"
                smtpPort = "465"
            }) { Text("Use Gmail preset") }
            OutlinedTextField(
                value = imapHost,
                onValueChange = { imapHost = it },
                label = { Text("IMAP host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = imapPort,
                onValueChange = { imapPort = it },
                label = { Text("IMAP port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = smtpHost,
                onValueChange = { smtpHost = it },
                label = { Text("SMTP host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = smtpPort,
                onValueChange = { smtpPort = it },
                label = { Text("SMTP port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val port = imapPort.toIntOrNull() ?: 993
                    val sPort = smtpPort.toIntOrNull() ?: 465
                    imap.connect(
                        ImapCredentials(
                            email = email.trim(),
                            appPassword = appPassword.trim(),
                            imapHost = imapHost.trim(),
                            imapPort = port,
                            smtpHost = smtpHost.trim(),
                            smtpPort = sPort
                        )
                    )
                    status = "Saved. Credentials are verified on first use."
                    refreshTick++
                },
                enabled = email.isNotBlank() && appPassword.isNotBlank() &&
                    imapHost.isNotBlank() && smtpHost.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Connect") }
        } else {
            OutlinedButton(
                onClick = {
                    imap.disconnect()
                    appPassword = ""
                    status = "Disconnected."
                    refreshTick++
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Disconnect") }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        // Force recomposition of statusLine() after connect/disconnect.
        if (refreshTick >= 0) Unit
    }
}

@Composable
private fun GoogleCard(
    google: com.gotcha.connectors.google.GoogleConnector,
    context: android.content.Context
) {
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableStateOf(0) }
    var clientId by remember { mutableStateOf(google.credentials()?.clientId ?: "") }
    var clientSecret by remember { mutableStateOf(google.credentials()?.clientSecret ?: "") }
    var pastedUrl by remember { mutableStateOf("") }
    var showPasteFallback by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val flow = remember(google) { GoogleOAuthFlow(context, google) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Gmail (BYO OAuth)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Text(google.statusLine(), style = MaterialTheme.typography.bodyMedium)
        Text(
            "Full read/write Gmail access using your own Google Cloud OAuth client — " +
                "no shared app, no verification wait.",
            style = MaterialTheme.typography.bodySmall
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("1. Create a Google Cloud project.", style = MaterialTheme.typography.bodySmall)
            Text("2. Enable the Gmail API.", style = MaterialTheme.typography.bodySmall)
            Text(
                "3. Configure the OAuth consent screen (External, add yourself as a " +
                    "test user — or publish it to skip weekly reconnects).",
                style = MaterialTheme.typography.bodySmall
            )
            Text("4. Create a Desktop app OAuth client.", style = MaterialTheme.typography.bodySmall)
            Text("5. Paste its Client ID and secret below.", style = MaterialTheme.typography.bodySmall)
            Text("6. Tap Connect.", style = MaterialTheme.typography.bodySmall)
        }

        if (!google.isConnected() || google.needsReconnect()) {
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                label = { Text("Client ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = clientSecret,
                onValueChange = { clientSecret = it },
                label = { Text("Client secret") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    busy = true
                    status = "Opening sign-in…"
                    scope.launch {
                        when (val outcome = flow.connect(clientId, clientSecret)) {
                            is GoogleOAuthFlow.Outcome.Connected ->
                                status = "Connected as ${outcome.email}."
                            is GoogleOAuthFlow.Outcome.Failed ->
                                status = outcome.message
                        }
                        busy = false
                        refreshTick++
                    }
                },
                enabled = !busy && clientId.isNotBlank() && clientSecret.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Waiting for sign-in…" else "Connect") }

            TextButton(onClick = { showPasteFallback = !showPasteFallback }) {
                Text(if (showPasteFallback) "Hide paste-URL fallback" else "Browser didn't return? Paste redirect URL")
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
                            when (val outcome = flow.connectWithPastedUrl(clientId, clientSecret, pastedUrl)) {
                                is GoogleOAuthFlow.Outcome.Connected ->
                                    status = "Connected as ${outcome.email}."
                                is GoogleOAuthFlow.Outcome.Failed ->
                                    status = outcome.message
                            }
                            busy = false
                            refreshTick++
                        }
                    },
                    enabled = !busy && pastedUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Finish sign-in with pasted URL") }
            }
        } else {
            OutlinedButton(
                onClick = {
                    google.disconnect()
                    clientSecret = ""
                    status = "Disconnected."
                    refreshTick++
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Disconnect") }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        if (refreshTick >= 0) Unit
    }
}
