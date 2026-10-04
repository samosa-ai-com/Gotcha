package com.gotcha.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gotcha.R
import com.gotcha.connectors.oauth.OAuthConnectFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The two reusable Connectors-screen card shapes.
 *
 * [TokenConnectorCard] — a set of text fields plus Connect/Disconnect, for
 * connectors authenticated by a pasted secret (IMAP app password, Notion
 * internal integration token).
 *
 * [OAuthConnectorCard] — client credentials plus the loopback consent flow and
 * its paste-URL fallback, for BYO-OAuth connectors (Google, Microsoft).
 *
 * Both cards are **collapsed by default**: a compact header row shows the
 * title, the live status line and (once connected) the enable switch, and the
 * body — blurb, setup steps, fields and buttons — only appears when the header
 * is tapped. Each card expands independently.
 *
 * Connectors own their own credential storage, so these talk to the connector
 * objects directly — there is nothing for SettingsRepository to persist.
 */

/**
 * The always-visible header row of a connector card: title, live status line,
 * the enable switch (kept reachable without expanding), and a chevron that
 * rotates to point up when the body is expanded. Tapping anywhere except the
 * switch toggles the body.
 */
@Composable
private fun ConnectorHeader(
    title: String,
    status: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    showSwitch: Boolean,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    headerTestTag: String
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp)
            .testTag(headerTestTag)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
        if (showSwitch) {
            val enableDescription = stringResource(R.string.connector_cards_enable, title)
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.semantics { contentDescription = enableDescription }
            )
        }
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = stringResource(
                if (expanded) R.string.connector_cards_collapse else R.string.connector_cards_expand,
                title
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer { rotationZ = rotation }
        )
    }
}

/**
 * The enable/disable switch, shown only once a connector is connected. Distinct
 * from Disconnect: switching off keeps the credentials (re-enabling needs no
 * re-auth) but withholds the connector's tools and skills from the assistant.
 *
 * [showSwitch] lets the expanded body keep the explanatory copy without
 * duplicating the switch itself — the switch already lives in the always-visible
 * header row so it stays reachable while the card is collapsed.
 */
@Composable
private fun EnabledRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    showSwitch: Boolean = true
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.connector_cards_enabled), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(
                    if (enabled) {
                        R.string.connector_cards_the_assistant_can_use_this
                    } else {
                        R.string.connector_cards_switched_off_tools_hidden_sign
                    }
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (showSwitch) Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
}

/**
 * The status line a collapsed card shows for a connector that has no saved
 * credentials yet — the "tap to set up" affordance hint.
 */
@Composable
private fun disconnectedHint(status: String): String =
    if (status == "Not connected") stringResource(R.string.connector_cards_not_connected_hint) else status

/**
 * Shared "Refresh tools" / "Refresh connection" button for a connected connector
 * card: runs [onRefresh] on [scope] while toggling [busy], then hands the status
 * message to [onResult].
 */
@Composable
private fun ConnectorRefreshButton(
    busy: Boolean,
    setBusy: (Boolean) -> Unit,
    idleLabel: String,
    busyLabel: String,
    scope: CoroutineScope,
    onRefresh: suspend () -> String,
    onResult: (String) -> Unit
) {
    Button(
        onClick = {
            setBusy(true)
            scope.launch {
                onResult(onRefresh())
                setBusy(false)
            }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (busy) busyLabel else idleLabel) }
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
    /** Current enable state; only meaningful once connected. */
    enabled: Boolean = true,
    /** Null hides the enable switch entirely. */
    onEnabledChange: ((Boolean) -> Unit)? = null,
    blurb: String? = null,
    steps: List<String> = emptyList(),
    canConnect: () -> Boolean = { fields.all { it.value.isNotBlank() } },
    /** Rendered between the fields and the Connect button (e.g. the Gmail preset shortcut). */
    belowFields: @Composable () -> Unit = {},
    /** Optional refresh action shown when connected (e.g. Home Assistant dynamic tool sync). */
    onRefresh: (suspend () -> String)? = null,
    /** Test tag for the always-visible header row. */
    headerTestTag: String = "connector_header_$title"
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Bumped after connect/disconnect so statusLine() is re-read.
    var refreshTick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    val connected = isConnected()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        key(refreshTick) {
            ConnectorHeader(
                title = title,
                status = if (connected) statusLine() else disconnectedHint(statusLine()),
                expanded = expanded,
                onToggle = { expanded = !expanded },
                showSwitch = connected && onEnabledChange != null,
                enabled = enabled,
                onEnabledChange = { onEnabledChange?.invoke(it) },
                headerTestTag = headerTestTag
            )
        }

        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!connected) {
                    if (blurb != null) Text(blurb, style = MaterialTheme.typography.bodySmall)
                    if (steps.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            steps.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
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
                    ) {
                        Text(
                            stringResource(
                                if (busy) R.string.connector_cards_connecting else R.string.connector_cards_connect
                            )
                        )
                    }
                } else {
                    if (onEnabledChange != null) EnabledRow(enabled, onEnabledChange, showSwitch = false)
                    if (onRefresh != null) {
                        ConnectorRefreshButton(
                            busy = busy,
                            setBusy = { busy = it },
                            idleLabel = stringResource(R.string.connector_cards_refresh_tools),
                            busyLabel = stringResource(R.string.connector_cards_refreshing_tools),
                            scope = scope,
                            onRefresh = onRefresh,
                            onResult = {
                                status = it
                                refreshTick++
                            }
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            onDisconnect()
                            fields.filter { it.secret }.forEach { it.value = "" }
                            status = context.getString(R.string.connector_cards_disconnected)
                            refreshTick++
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.connector_cards_disconnect)) }
                }
            }
        }
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
    /** Current enable state; only meaningful once connected. */
    enabled: Boolean = true,
    /** Null hides the enable switch entirely. */
    onEnabledChange: ((Boolean) -> Unit)? = null,
    blurb: String? = null,
    steps: List<String> = emptyList(),
    extraFields: @Composable () -> Unit = {},
    /** Optional refresh action shown when connected. */
    onRefresh: (suspend () -> String)? = null,
    /** Test tag for the always-visible header row. */
    headerTestTag: String = "connector_header_$title"
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    var clientId by remember { mutableStateOf(initialClientId) }
    var clientSecret by remember { mutableStateOf(initialClientSecret.orEmpty()) }
    var pastedUrl by remember { mutableStateOf("") }
    var showPasteFallback by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val usesSecret = initialClientSecret != null

    fun apply(outcome: OAuthConnectFlow.Outcome) {
        status = when (outcome) {
            is OAuthConnectFlow.Outcome.Connected -> context.getString(R.string.connector_cards_connected_as, outcome.account)
            is OAuthConnectFlow.Outcome.Failed -> outcome.message
        }
        busy = false
        refreshTick++
    }

    val connected = isConnected()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        key(refreshTick) {
            ConnectorHeader(
                title = title,
                status = if (connected) statusLine() else disconnectedHint(statusLine()),
                expanded = expanded,
                onToggle = { expanded = !expanded },
                showSwitch = connected && onEnabledChange != null,
                enabled = enabled,
                onEnabledChange = { onEnabledChange?.invoke(it) },
                headerTestTag = headerTestTag
            )
        }

        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!connected || needsReconnect()) {
                    if (blurb != null) Text(blurb, style = MaterialTheme.typography.bodySmall)
                    if (steps.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            steps.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it },
                        label = { Text(stringResource(R.string.connector_cards_client_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (usesSecret) {
                        OutlinedTextField(
                            value = clientSecret,
                            onValueChange = { clientSecret = it },
                            label = { Text(stringResource(R.string.connector_cards_client_secret)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    extraFields()
                    Button(
                        onClick = {
                            busy = true
                            status = context.getString(R.string.connector_cards_opening_sign_in)
                            scope.launch {
                                apply(flow.connect(clientId, clientSecret.takeIf { usesSecret }))
                            }
                        },
                        enabled = !busy && clientId.isNotBlank() && (!usesSecret || clientSecret.isNotBlank()),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(
                                if (busy) R.string.connector_cards_waiting_for_sign_in else R.string.connector_cards_connect
                            )
                        )
                    }

                    TextButton(onClick = { showPasteFallback = !showPasteFallback }) {
                        Text(
                            stringResource(
                                if (showPasteFallback) {
                                    R.string.connector_cards_hide_paste_url_fallback
                                } else {
                                    R.string.connector_cards_browser_didn_t_return_paste
                                }
                            )
                        )
                    }
                    if (showPasteFallback) {
                        OutlinedTextField(
                            value = pastedUrl,
                            onValueChange = { pastedUrl = it },
                            label = { Text(stringResource(R.string.connector_cards_pasted_redirect_url)) },
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
                        ) { Text(stringResource(R.string.connector_cards_finish_sign_in_with_pasted)) }
                    }
                } else {
                    if (onEnabledChange != null) EnabledRow(enabled, onEnabledChange, showSwitch = false)
                    if (onRefresh != null) {
                        ConnectorRefreshButton(
                            busy = busy,
                            setBusy = { busy = it },
                            idleLabel = stringResource(R.string.connector_cards_refresh_connection),
                            busyLabel = stringResource(R.string.connector_cards_refreshing),
                            scope = scope,
                            onRefresh = onRefresh,
                            onResult = {
                                status = it
                                refreshTick++
                            }
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            onDisconnect()
                            clientSecret = ""
                            status = context.getString(R.string.connector_cards_disconnected)
                            refreshTick++
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.connector_cards_disconnect)) }
                }
            }
        }
    }
}
