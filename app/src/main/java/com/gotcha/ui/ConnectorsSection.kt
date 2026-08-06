package com.gotcha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gotcha.connectors.ConnectorRegistry
import com.gotcha.connectors.google.GoogleConnector
import com.gotcha.connectors.homeassistant.HomeAssistantConnector
import com.gotcha.connectors.imap.ImapConnector
import com.gotcha.connectors.imap.ImapCredentials
import com.gotcha.connectors.microsoft.MicrosoftConnector
import com.gotcha.connectors.notion.NotionConnector
import com.gotcha.connectors.oauth.OAuthConnectFlow
import com.gotcha.data.SettingsRepository

/**
 * The body of the Connectors screen: one card per connector, built from the two
 * reusable shapes in ConnectorCards.kt. Talks to [ConnectorRegistry] directly
 * since connectors own their own credential storage — there is nothing for the
 * Settings/SettingsRepository layer to persist.
 */
@Composable
fun ConnectorsSection() {
    val context = LocalContext.current
    // init is idempotent; folded into the remember that hands back the registry so
    // this stays a value-producing remember rather than a Unit side effect.
    val registry = remember(context) { ConnectorRegistry.apply { init(context) } }
    val imap = remember(registry) { registry.byId("imap") as ImapConnector }
    val google = remember(registry) { registry.byId("google") as GoogleConnector }
    val microsoft = remember(registry) { registry.byId("microsoft") as MicrosoftConnector }
    val notion = remember(registry) { registry.byId("notion") as NotionConnector }
    val homeAssistant = remember(registry) { registry.byId("homeassistant") as HomeAssistantConnector }

    // The one piece of connector state that is *not* a credential, so it lives in
    // Settings rather than the connector's own encrypted blob.
    val settingsRepo = remember(context) { SettingsRepository(context) }
    var disabled by remember { mutableStateOf(settingsRepo.load().disabledConnectors) }
    fun setEnabled(id: String, enabled: Boolean) {
        disabled = if (enabled) disabled - id else disabled + id
        settingsRepo.save(settingsRepo.load().copy(disabledConnectors = disabled))
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ImapCard(imap, "imap" !in disabled) { setEnabled("imap", it) }
        HorizontalDivider(thickness = 1.dp)
        GoogleCard(google, "google" !in disabled) { setEnabled("google", it) }
        HorizontalDivider(thickness = 1.dp)
        MicrosoftCard(microsoft, "microsoft" !in disabled) { setEnabled("microsoft", it) }
        HorizontalDivider(thickness = 1.dp)
        NotionCard(notion, "notion" !in disabled) { setEnabled("notion", it) }
        HorizontalDivider(thickness = 1.dp)
        HomeAssistantCard(homeAssistant, "homeassistant" !in disabled) { setEnabled("homeassistant", it) }
    }
}

@Composable
private fun HomeAssistantCard(
    homeAssistant: HomeAssistantConnector,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    val saved = homeAssistant.credentials()
    val url = rememberTokenField(
        "Home Assistant URL",
        saved?.baseUrl ?: "",
        keyboard = KeyboardType.Uri
    )
    val token = rememberTokenField("Long-lived access token", "", secret = true)

    TokenConnectorCard(
        title = "Home Assistant",
        statusLine = homeAssistant::statusLine,
        isConnected = homeAssistant::isConnected,
        fields = listOf(url, token),
        headerTestTag = "connector_header_homeassistant",
        blurb = "Control and query your smart home through Home Assistant's Model Context " +
            "Protocol server. Its tools are defined by your server and scoped to the entities " +
            "you expose to Assist.",
        steps = listOf(
            "1. In Home Assistant, add the \"Model Context Protocol Server\" integration " +
                "(Settings ▸ Devices & services ▸ Add integration).",
            "2. Create a long-lived access token: your profile ▸ Security ▸ Long-lived " +
                "access tokens ▸ Create token.",
            "3. Paste your Home Assistant URL (e.g. http://192.168.1.10:8123) and the token below.",
            "4. Expose the devices you want the assistant to control: Settings ▸ Voice " +
                "assistants ▸ Expose entities."
        ),
        onConnect = { homeAssistant.connect(url.value, token.value) },
        onDisconnect = homeAssistant::disconnect,
        enabled = enabled,
        onEnabledChange = onEnabledChange
    )
}

@Composable
private fun NotionCard(
    notion: NotionConnector,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    val token = rememberTokenField("Internal integration token", "", secret = true)

    TokenConnectorCard(
        title = "Notion",
        statusLine = notion::statusLine,
        isConnected = notion::isConnected,
        fields = listOf(token),
        headerTestTag = "connector_header_notion",
        blurb = "Search, read and write Notion pages using an internal integration in your own " +
            "workspace — no OAuth app to register.",
        steps = listOf(
            "1. Go to notion.so/my-integrations ▸ New integration, in your workspace.",
            "2. Give it Read, Insert and Update content capabilities.",
            "3. Copy the Internal Integration Secret and paste it below.",
            "4. Important: open each page you want reachable and use ⋯ ▸ Connections ▸ " +
                "add the integration. Pages that are not shared stay invisible to it."
        ),
        onConnect = { notion.connect(token.value) },
        onDisconnect = notion::disconnect,
        enabled = enabled,
        onEnabledChange = onEnabledChange
    )
}

@Composable
private fun ImapCard(
    imap: ImapConnector,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    val saved = imap.credentials()
    val email = rememberTokenField("Email address", saved?.email ?: "", keyboard = KeyboardType.Email)
    val appPassword = rememberTokenField("App password", "", secret = true)
    val imapHost = rememberTokenField("IMAP host", saved?.imapHost ?: "imap.gmail.com")
    val imapPort = rememberTokenField("IMAP port", (saved?.imapPort ?: 993).toString(), keyboard = KeyboardType.Number)
    val smtpHost = rememberTokenField("SMTP host", saved?.smtpHost ?: "smtp.gmail.com")
    val smtpPort = rememberTokenField("SMTP port", (saved?.smtpPort ?: 465).toString(), keyboard = KeyboardType.Number)
    val fields = listOf(email, appPassword, imapHost, imapPort, smtpHost, smtpPort)

    TokenConnectorCard(
        title = "Email (IMAP)",
        statusLine = imap::statusLine,
        isConnected = imap::isConnected,
        fields = fields,
        headerTestTag = "connector_header_imap",
        steps = listOf(
            "Needs an app password, not your regular password. Requires " +
                "2-Step Verification to be enabled first.",
            "Generate one at myaccount.google.com/apppasswords, or via " +
                "Google Account ▸ Security ▸ 2-Step Verification ▸ App passwords.",
            "Other providers: check their IMAP app-password documentation."
        ),
        belowFields = {
            TextButton(onClick = {
                imapHost.value = "imap.gmail.com"
                imapPort.value = "993"
                smtpHost.value = "smtp.gmail.com"
                smtpPort.value = "465"
            }) { Text("Use Gmail preset") }
        },
        onConnect = {
            imap.connect(
                ImapCredentials(
                    email = email.value.trim(),
                    appPassword = appPassword.value.trim(),
                    imapHost = imapHost.value.trim(),
                    imapPort = imapPort.value.toIntOrNull() ?: 993,
                    smtpHost = smtpHost.value.trim(),
                    smtpPort = smtpPort.value.toIntOrNull() ?: 465
                )
            )
            "Saved. Credentials are verified on first use."
        },
        onDisconnect = imap::disconnect,
        enabled = enabled,
        onEnabledChange = onEnabledChange
    )
}

@Composable
private fun GoogleCard(
    google: GoogleConnector,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    // Which Google services to request consent for. Adding one later needs a reconnect,
    // because the refresh token only carries the scopes granted at consent time.
    var wantGmail by remember {
        mutableStateOf(google.credentials()?.scopes?.contains(GoogleConnector.SCOPE_GMAIL_MODIFY) ?: true)
    }
    var wantCalendar by remember { mutableStateOf(google.hasCalendar()) }
    val scopes = {
        buildList {
            if (wantGmail) add(GoogleConnector.SCOPE_GMAIL_MODIFY)
            if (wantCalendar) add(GoogleConnector.SCOPE_CALENDAR)
        }.ifEmpty { listOf(GoogleConnector.SCOPE_GMAIL_MODIFY) }
    }
    val flow = remember(google) {
        OAuthConnectFlow(
            context = context,
            configFor = { id, secret -> google.oauthConfig(id, secret.orEmpty(), scopes()) },
            onTokens = { id, secret, tokens ->
                google.completeConnect(id, secret.orEmpty(), tokens, scopes())
            },
            accountLabel = { google.credentials()?.accountEmail.orEmpty() }
        )
    }

    OAuthConnectorCard(
        title = "Google (Gmail, Calendar)",
        statusLine = google::statusLine,
        isConnected = google::isConnected,
        needsReconnect = google::needsReconnect,
        initialClientId = google.credentials()?.clientId ?: "",
        initialClientSecret = google.credentials()?.clientSecret ?: "",
        flow = flow,
        headerTestTag = "connector_header_google",
        onDisconnect = google::disconnect,
        enabled = enabled,
        onEnabledChange = onEnabledChange,
        blurb = "Full read/write Gmail and Google Calendar access using your own Google Cloud " +
            "OAuth client — no shared app, no verification wait.",
        steps = listOf(
            "1. Create a Google Cloud project.",
            "2. Enable the Gmail API and/or the Google Calendar API — whichever you tick below.",
            "3. Configure the OAuth consent screen (External, add yourself as a " +
                "test user — or publish it to skip weekly reconnects).",
            "4. Create a Desktop app OAuth client.",
            "5. Paste its Client ID and secret below.",
            "6. Tap Connect."
        ),
        extraFields = {
            Text("Services to authorise:", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = wantGmail, onCheckedChange = { wantGmail = it })
                Text("Gmail", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(16.dp))
                Checkbox(checked = wantCalendar, onCheckedChange = { wantCalendar = it })
                Text("Calendar", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "Changing this needs a reconnect — the saved sign-in only carries the scopes " +
                    "granted at consent time.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    )
}

@Composable
private fun MicrosoftCard(
    microsoft: MicrosoftConnector,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    // "common" covers personal and work accounts; a tenant id/domain locks it to one org.
    var tenant by remember {
        mutableStateOf(microsoft.credentials()?.tenant ?: MicrosoftConnector.DEFAULT_TENANT)
    }
    val flow = remember(microsoft) {
        OAuthConnectFlow(
            context = context,
            configFor = { id, _ -> microsoft.oauthConfig(id, tenant.trim()) },
            onTokens = { id, _, tokens -> microsoft.completeConnect(id, tenant.trim(), tokens) },
            accountLabel = { microsoft.credentials()?.accountEmail.orEmpty() }
        )
    }

    OAuthConnectorCard(
        title = "Microsoft (Outlook, Calendar, To Do)",
        statusLine = microsoft::statusLine,
        isConnected = microsoft::isConnected,
        needsReconnect = microsoft::needsReconnect,
        initialClientId = microsoft.credentials()?.clientId ?: "",
        // Public client — PKCE only, so there is no secret to paste.
        initialClientSecret = null,
        flow = flow,
        headerTestTag = "connector_header_microsoft",
        onDisconnect = microsoft::disconnect,
        enabled = enabled,
        onEnabledChange = onEnabledChange,
        blurb = "Outlook mail, calendar and To Do using your own Entra app registration. " +
            "One sign-in covers all three.",
        steps = listOf(
            "1. Go to portal.azure.com ▸ Microsoft Entra ID ▸ App registrations ▸ New.",
            "2. Under \"Redirect URI\" pick \"Public client/native\" and enter http://localhost.",
            "3. In API permissions add delegated Microsoft Graph permissions: " +
                "offline_access, User.Read, Mail.ReadWrite, Mail.Send, Calendars.ReadWrite, " +
                "Tasks.ReadWrite.",
            "4. Copy the Application (client) ID from the Overview page.",
            "5. Paste it below and tap Connect. Leave Tenant as \"common\" for personal accounts."
        ),
        extraFields = {
            OutlinedTextField(
                value = tenant,
                onValueChange = { tenant = it },
                label = { Text("Tenant (common, or your organisation's tenant ID)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}
