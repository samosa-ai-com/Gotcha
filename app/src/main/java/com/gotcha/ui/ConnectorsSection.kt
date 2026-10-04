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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gotcha.R
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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

    // Automatic background scheduler loop while screen is open.
    //
    // Changing the interval restarts this effect, so everything in it has to
    // stay off the main thread: a Settings load is ~60 AES-decrypted reads and
    // takes the same prefs lock the interval write holds, which is what used to
    // freeze the slider for a moment after each change.
    LaunchedEffect(settings.connectorAutoRefreshIntervalMinutes) {
        if (settings.connectorAutoRefreshIntervalMinutes <= 0) return@LaunchedEffect
        while (isActive) {
            val refreshed = refreshScheduler.refreshIfNeeded()
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
    onIntervalChange: (Int) -> Unit,
    onRefreshAll: suspend () -> Map<String, String>
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
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
                Text(
                    stringResource(R.string.connectors_auto_tool_sync),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                // Accessing `tick` forces recomposition when time passes
                val minutesAgo = if (lastRefreshedAt > 0 && tick >= 0) {
                    ((System.currentTimeMillis() - lastRefreshedAt) / 60_000L).coerceAtLeast(0)
                } else {
                    null
                }

                Text(
                    when (minutesAgo) {
                        null -> stringResource(R.string.connectors_last_sync_never)
                        0L -> stringResource(R.string.connectors_last_sync_just_now)
                        else -> pluralStringResource(
                            R.plurals.connectors_last_sync_minutes,
                            minutesAgo.toInt(),
                            minutesAgo
                        )
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
                                context.getString(R.string.connectors_all_up_to_date)
                            } else {
                                context.resources.getQuantityString(
                                    R.plurals.connectors_refreshed,
                                    results.size,
                                    results.size,
                                    results.keys.joinToString(", ")
                                )
                            }
                        }
                    }
                },
                enabled = !isRefreshing
            ) {
                Text(stringResource(if (isRefreshing) R.string.connectors_syncing else R.string.connectors_refresh_all))
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
                Text(stringResource(R.string.connectors_interval), style = MaterialTheme.typography.bodyMedium)
                Text(
                    AUTO_REFRESH_INTERVALS[sliderIndex].let { (minutes, label) ->
                        if (minutes == 0) stringResource(R.string.connectors_interval_off) else label
                    },
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
        stringResource(R.string.connectors_home_assistant_url),
        saved?.baseUrl ?: "",
        keyboard = KeyboardType.Uri
    )
    val token = rememberTokenField(stringResource(R.string.connectors_long_lived_access_token), "", secret = true)

    TokenConnectorCard(
        title = stringResource(R.string.connectors_home_assistant),
        statusLine = homeAssistant::statusLine,
        isConnected = homeAssistant::isConnected,
        fields = listOf(url, token),
        headerTestTag = "connector_header_homeassistant",
        blurb = stringResource(R.string.connectors_control_and_query_your_smart),
        steps = listOf(
            stringResource(R.string.connectors_1_in_home_assistant_add),
            stringResource(R.string.connectors_2_create_a_long_lived),
            stringResource(R.string.connectors_3_paste_your_home_assistant),
            stringResource(R.string.connectors_4_expose_the_devices_you)
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
    val token = rememberTokenField(stringResource(R.string.connectors_internal_integration_token), "", secret = true)

    TokenConnectorCard(
        title = stringResource(R.string.connectors_notion),
        statusLine = notion::statusLine,
        isConnected = notion::isConnected,
        fields = listOf(token),
        headerTestTag = "connector_header_notion",
        blurb = stringResource(R.string.connectors_search_read_and_write_notion),
        steps = listOf(
            stringResource(R.string.connectors_1_go_to_notion_so),
            stringResource(R.string.connectors_2_give_it_read_insert),
            stringResource(R.string.connectors_3_copy_the_internal_integration),
            stringResource(R.string.connectors_4_important_open_each_page)
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
    val context = LocalContext.current
    val email =
        rememberTokenField(
            stringResource(R.string.connectors_email_address),
            saved?.email ?: "",
            keyboard = KeyboardType.Email
        )
    val appPassword = rememberTokenField(stringResource(R.string.connectors_app_password), "", secret = true)
    val imapHost =
        rememberTokenField(stringResource(R.string.connectors_imap_host), saved?.imapHost ?: "imap.gmail.com")
    val imapPort =
        rememberTokenField(
            stringResource(R.string.connectors_imap_port),
            (saved?.imapPort ?: 993).toString(),
            keyboard = KeyboardType.Number
        )
    val smtpHost =
        rememberTokenField(stringResource(R.string.connectors_smtp_host), saved?.smtpHost ?: "smtp.gmail.com")
    val smtpPort =
        rememberTokenField(
            stringResource(R.string.connectors_smtp_port),
            (saved?.smtpPort ?: 465).toString(),
            keyboard = KeyboardType.Number
        )
    val fields = listOf(email, appPassword, imapHost, imapPort, smtpHost, smtpPort)

    TokenConnectorCard(
        title = stringResource(R.string.connectors_email_imap),
        statusLine = imap::statusLine,
        isConnected = imap::isConnected,
        fields = fields,
        headerTestTag = "connector_header_imap",
        steps = listOf(
            stringResource(R.string.connectors_needs_an_app_password_not),
            stringResource(R.string.connectors_generate_one_at_myaccount_google),
            stringResource(R.string.connectors_other_providers_check_their_imap)
        ),
        belowFields = {
            TextButton(onClick = {
                imapHost.value = "imap.gmail.com"
                imapPort.value = "993"
                smtpHost.value = "smtp.gmail.com"
                smtpPort.value = "465"
            }) { Text(stringResource(R.string.connectors_use_gmail_preset)) }
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
            context.getString(R.string.connectors_imap_saved)
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
        title = stringResource(R.string.connectors_google_gmail_calendar),
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
        blurb = stringResource(R.string.connectors_full_read_write_gmail_and),
        steps = listOf(
            stringResource(R.string.connectors_1_create_a_google_cloud),
            stringResource(R.string.connectors_2_enable_the_gmail_api),
            stringResource(R.string.connectors_3_configure_the_oauth_consent),
            stringResource(R.string.connectors_4_create_a_desktop_app),
            stringResource(R.string.connectors_5_paste_its_client_id),
            stringResource(R.string.connectors_6_tap_connect)
        ),
        extraFields = {
            Text(stringResource(R.string.connectors_services_to_authorise), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = wantGmail, onCheckedChange = { wantGmail = it })
                Text(stringResource(R.string.connectors_gmail), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(16.dp))
                Checkbox(checked = wantCalendar, onCheckedChange = { wantCalendar = it })
                Text(stringResource(R.string.connectors_calendar), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                stringResource(R.string.connectors_changing_this_needs_a_reconnect),
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
        title = stringResource(R.string.connectors_microsoft_outlook_calendar_to_do),
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
        blurb = stringResource(R.string.connectors_outlook_mail_calendar_and_to),
        steps = listOf(
            stringResource(R.string.connectors_1_go_to_portal_azure),
            stringResource(R.string.connectors_2_under_redirect_uri_pick),
            stringResource(R.string.connectors_3_in_api_permissions_add),
            stringResource(R.string.connectors_4_copy_the_application_client),
            stringResource(R.string.connectors_5_paste_it_below_and)
        ),
        extraFields = {
            OutlinedTextField(
                value = tenant,
                onValueChange = { tenant = it },
                label = { Text(stringResource(R.string.connectors_tenant_common_or_your_organisation)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}
