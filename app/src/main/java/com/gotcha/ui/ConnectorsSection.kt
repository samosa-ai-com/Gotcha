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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gotcha.connectors.ConnectorRefreshScheduler
import com.gotcha.connectors.ConnectorRegistry
import com.gotcha.connectors.google.GoogleConnector
import com.gotcha.connectors.homeassistant.HomeAssistantConnector
import com.gotcha.connectors.imap.ImapConnector
import com.gotcha.connectors.imap.ImapCredentials
import com.gotcha.connectors.microsoft.MicrosoftConnector
import com.gotcha.connectors.notion.NotionConnector
import com.gotcha.connectors.oauth.OAuthConnectFlow
import com.gotcha.data.SettingsRepository
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var settings by remember { mutableStateOf(settingsRepo.load()) }
    var disabled by remember { mutableStateOf(settings.disabledConnectors) }
    val scope = rememberCoroutineScope()
    val refreshScheduler = remember(context) { ConnectorRefreshScheduler(context) }
    // True while the scheduled refresh below is in flight, so the header can say
    // so instead of just looking stuck.
    var autoSyncing by remember { mutableStateOf(false) }

    // Automatic background scheduler loop while screen is open.
    //
    // Changing the interval restarts this effect, so everything in it has to
    // stay off the main thread: a Settings load is ~60 AES-decrypted reads and
    // takes the same prefs lock the interval write holds, which is what used to
    // freeze the slider for a moment after each change.
    LaunchedEffect(settings.connectorAutoRefreshIntervalMinutes) {
        if (settings.connectorAutoRefreshIntervalMinutes <= 0) return@LaunchedEffect
        while (isActive) {
            autoSyncing = true
            val refreshed = try {
                refreshScheduler.refreshIfNeeded()
            } finally {
                autoSyncing = false
            }
            if (refreshed.isNotEmpty()) {
                // Take the new stamp and nothing else. Reassigning all of
                // Settings from disk would also overwrite the interval held in
                // memory -- with a value read before the slider's write landed,
                // which snapped the thumb back to where it started.
                val stamp = withContext(Dispatchers.IO) {
                    settingsRepo.load().connectorLastRefreshedAt
                }
                settings = settings.copy(connectorLastRefreshedAt = stamp)
            }
            delay(60_000L)
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        disabled = if (enabled) disabled - id else disabled + id
        val current = settingsRepo.load()
        settings = current.copy(disabledConnectors = disabled)
        settingsRepo.save(settings)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AutoRefreshHeader(
            intervalMinutes = settings.connectorAutoRefreshIntervalMinutes,
            lastRefreshedAt = settings.connectorLastRefreshedAt,
            isAutoSyncing = autoSyncing,
            // Reflected in the UI immediately and persisted off the main thread.
            // Settings lives in EncryptedSharedPreferences, so the read-modify-
            // write behind this is ~60 AES reads plus 58 writes -- long enough
            // to drop frames while a finger is still on the slider.
            onIntervalChange = { newInterval ->
                settings = settings.copy(connectorAutoRefreshIntervalMinutes = newInterval)
                scope.launch(Dispatchers.IO) {
                    settingsRepo.saveConnectorAutoRefreshIntervalMinutes(newInterval)
                }
            },
            onRefreshAll = {
                val results = refreshScheduler.refreshIfNeeded(force = true)
                settings = settingsRepo.load()
                results
            }
        )
        HorizontalDivider(thickness = 1.dp)
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

/**
 * The auto-sync intervals the slider offers, in minutes, with the label shown
 * for each. 1m is the floor: the loop driving auto-sync in [ConnectorsSection]
 * ticks every 60s, so anything finer would be a setting the app cannot honour.
 */
private val AUTO_REFRESH_INTERVALS = listOf(
    0 to "Off",
    1 to "1m",
    2 to "2m",
    5 to "5m",
    10 to "10m",
    15 to "15m",
    30 to "30m",
    120 to "2h"
)

/**
 * Slider index for a stored interval. A value that is not one of the offered
 * steps -- written by an older build, or by a settings import -- snaps to the
 * nearest one rather than resetting the user to Off.
 */
/** Slider position to a valid index into [AUTO_REFRESH_INTERVALS]. */
private fun Float.toIndex(): Int =
    roundToInt().coerceIn(0, AUTO_REFRESH_INTERVALS.lastIndex)

private fun indexOfInterval(minutes: Int): Int {
    val exact = AUTO_REFRESH_INTERVALS.indexOfFirst { it.first == minutes }
    if (exact >= 0) return exact
    return AUTO_REFRESH_INTERVALS.indices.minByOrNull {
        kotlin.math.abs(AUTO_REFRESH_INTERVALS[it].first - minutes)
    } ?: 0
}

@Composable
private fun AutoRefreshHeader(
    intervalMinutes: Int,
    lastRefreshedAt: Long,
    isAutoSyncing: Boolean,
    onIntervalChange: (Int) -> Unit,
    onRefreshAll: suspend () -> Map<String, String>
) {
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    var syncFeedback by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    // Seeded once from the stored interval and authoritative from then on. It is
    // deliberately not keyed on intervalMinutes: this screen is the only writer
    // while it is open, and re-seeding from a reload gives a stale read a way to
    // drag the thumb back out from under the user.
    var sliderPosition by remember { mutableStateOf(indexOfInterval(intervalMinutes).toFloat()) }
    val sliderIndex = sliderPosition.toIndex()

    // Live recomposition ticker for "X min ago" display
    LaunchedEffect(lastRefreshedAt) {
        while (isActive) {
            delay(10_000L)
            tick++
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto Tool Sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                // Accessing `tick` forces recomposition when time passes
                val minutesAgo = if (lastRefreshedAt > 0 && tick >= 0) {
                    ((System.currentTimeMillis() - lastRefreshedAt) / 60_000L).coerceAtLeast(0)
                } else {
                    null
                }

                Text(
                    if (minutesAgo != null) {
                        if (minutesAgo == 0L) "Last sync: Just now" else "Last sync: $minutesAgo min ago"
                    } else {
                        "Last sync: Never"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }

            TextButton(
                onClick = {
                    if (!isRefreshing) {
                        isRefreshing = true
                        scope.launch {
                            val results = onRefreshAll()
                            isRefreshing = false
                            syncFeedback = if (results.isEmpty()) {
                                "All active connectors are up to date."
                            } else {
                                "Refreshed ${results.size} connector(s): ${results.keys.joinToString(", ")}"
                            }
                        }
                    }
                },
                enabled = !isRefreshing
            ) {
                Text(if (isRefreshing) "Syncing…" else "Refresh All")
            }
        }

        if (syncFeedback.isNotBlank()) {
            Text(syncFeedback, style = MaterialTheme.typography.bodySmall)
        }

        // Eight choices are too many to sit on one phone-width line as chips, so
        // the interval is a slider that snaps to the steps below. The spacing is
        // deliberately not proportional to the durations -- every step is one
        // notch, so the far end stays reachable without a 2h-wide gap.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Interval", style = MaterialTheme.typography.bodyMedium)
                Text(
                    AUTO_REFRESH_INTERVALS[sliderIndex].second,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = sliderPosition,
                onValueChange = { sliderPosition = it },
                // The interval only reaches Settings once the thumb is let go;
                // dragging would otherwise write SharedPreferences every frame
                // and restart the refresh loop on each one.
                //
                // Read the position here rather than closing over sliderIndex.
                // Slider calls onValueChange and then onValueChangeFinished
                // within the one gesture, so this lambda can still be the
                // instance built by the composition *before* the tap -- and the
                // index it captured is where the thumb used to be. That is what
                // made a first tap commit the old value and appear to do
                // nothing, while a second tap on the same spot worked.
                onValueChangeFinished = {
                    onIntervalChange(AUTO_REFRESH_INTERVALS[sliderPosition.toIndex()].first)
                },
                valueRange = 0f..(AUTO_REFRESH_INTERVALS.size - 1).toFloat(),
                steps = AUTO_REFRESH_INTERVALS.size - 2,
                modifier = Modifier.fillMaxWidth()
            )
            // A new interval saves in milliseconds; what takes a moment is the
            // sync it starts when one is already due. Name that rather than the
            // save, so the pause is explained instead of looking like a hang.
            if (isAutoSyncing) {
                Text(
                    "Syncing connectors…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
        onRefresh = homeAssistant::refreshTools,
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
        onRefresh = notion::refreshTools,
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
        onRefresh = imap::refreshTools,
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
        onRefresh = google::refreshTools,
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
        onRefresh = microsoft::refreshTools,
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
