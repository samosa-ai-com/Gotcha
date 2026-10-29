package com.gotcha.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gotcha.R
import com.gotcha.connectors.mcp.McpProbe
import com.gotcha.connectors.mcp.McpServerConfig
import com.gotcha.connectors.mcp.McpServersConnector
import com.gotcha.connectors.mcp.formatHeaderLines
import com.gotcha.connectors.mcp.isCleartextWithSecret
import com.gotcha.connectors.mcp.parseHeaderLines
import com.gotcha.i18n.stringLookup
import kotlinx.coroutines.launch

/** [editing] value for the add form, as opposed to a server id. */
private const val NEW_SERVER = "+new"

/**
 * The Connectors card for the user's own MCP servers (issue #130): one row per
 * server with its own switch, its tools, Refresh, Edit and Remove, and a form
 * to add or edit one. Shaped like the other connector cards — collapsed to a
 * header with the connector switch — but holds a list instead of one account.
 *
 * The form is inline rather than a dialog, like the other cards' fields.
 */
@Composable
internal fun McpServersCard(
    mcp: McpServersConnector,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    val strings = LocalContext.current.stringLookup()
    // Bumped after every change, so the server list and status are read again.
    var tick by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    val servers = remember(tick) { mcp.servers() }
    val changed = { tick++ }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        key(tick) {
            val status = mcp.statusLine(strings)
            ConnectorHeader(
                title = stringResource(R.string.mcp_card_title),
                status = if (mcp.isConnected()) status else disconnectedHint(status),
                expanded = expanded,
                onToggle = { expanded = !expanded },
                showSwitch = mcp.isConnected(),
                enabled = enabled,
                onEnabledChange = onEnabledChange,
                headerTestTag = "connector_header_mcp"
            )
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.mcp_card_blurb), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.mcp_card_monitor_note), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.mcp_card_trust),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                servers.forEach { server ->
                    if (editing == server.id) {
                        McpServerForm(mcp, server, onDone = { saved, text ->
                            message = text
                            if (saved) editing = null
                            changed()
                        }, onCancel = { editing = null })
                    } else {
                        McpServerRow(
                            mcp = mcp,
                            server = server,
                            onEdit = { editing = server.id },
                            onChanged = { text ->
                                message = text
                                changed()
                            }
                        )
                    }
                }
                if (editing == NEW_SERVER) {
                    McpServerForm(mcp, null, onDone = { saved, text ->
                        message = text
                        if (saved) editing = null
                        changed()
                    }, onCancel = { editing = null })
                } else {
                    OutlinedButton(
                        onClick = {
                            editing = NEW_SERVER
                            message = ""
                        },
                        modifier = Modifier.fillMaxWidth().testTag("mcp_add_server")
                    ) { Text(stringResource(R.string.mcp_add_server)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpServerRow(
    mcp: McpServersConnector,
    server: McpServerConfig,
    onEdit: () -> Unit,
    onChanged: (String) -> Unit
) {
    val strings = LocalContext.current.stringLookup()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var confirmRemove by remember(server.id) { mutableStateOf(false) }
    val toolCount = pluralStringResource(R.plurals.mcp_tools_count, server.tools.size, server.tools.size)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("mcp_server_${server.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                val error = server.lastError
                when {
                    error != null -> Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    !server.enabled -> Text(
                        stringResource(R.string.mcp_server_off),
                        style = MaterialTheme.typography.bodySmall
                    )
                    else -> Text(
                        stringResource(R.string.mcp_server_status, McpServersConnector.hostOf(server.url), toolCount),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            val switchDescription = stringResource(R.string.connector_cards_enable, server.name)
            Switch(
                checked = server.enabled,
                onCheckedChange = {
                    mcp.setServerEnabled(server.id, it)
                    onChanged("")
                },
                modifier = Modifier.semantics { contentDescription = switchDescription }
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { showTools = !showTools }) {
                Text(
                    if (showTools) {
                        stringResource(R.string.mcp_hide_tools)
                    } else {
                        stringResource(R.string.mcp_show_tools, server.tools.size)
                    }
                )
            }
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val text = mcp.refresh(server.id, strings)
                        busy = false
                        onChanged(text)
                    }
                }
            ) {
                Text(
                    stringResource(
                        if (busy) R.string.connector_cards_refreshing_tools else R.string.connector_cards_refresh_tools
                    )
                )
            }
            TextButton(onClick = onEdit) { Text(stringResource(R.string.mcp_edit)) }
            TextButton(onClick = {
                if (confirmRemove) {
                    mcp.remove(server.id)
                    onChanged("")
                } else {
                    confirmRemove = true
                }
            }) {
                Text(
                    if (confirmRemove) {
                        stringResource(R.string.mcp_remove_confirm, server.name)
                    } else {
                        stringResource(R.string.mcp_remove)
                    },
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (showTools) {
            if (server.tools.isEmpty()) {
                Text(stringResource(R.string.mcp_no_tools), style = MaterialTheme.typography.bodySmall)
            }
            val byName = server.tools.associateBy { it.name }
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 12.dp)
            ) {
                mcp.exposedNames(server).values.forEach { name ->
                    val tool = byName[name] ?: return@forEach
                    Column {
                        Text(tool.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(
                                if (tool.readOnly) R.string.mcp_tool_read_only else R.string.mcp_tool_operator
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (tool.description.isNotBlank()) {
                            Text(
                                tool.description,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Add ([existing] null) or edit a server. Test contacts it; Save tests again and stores it only if it answers. */
@Composable
private fun McpServerForm(
    mcp: McpServersConnector,
    existing: McpServerConfig?,
    onDone: (saved: Boolean, message: String) -> Unit,
    onCancel: () -> Unit
) {
    val strings = LocalContext.current.stringLookup()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    var token by remember { mutableStateOf(existing?.token.orEmpty()) }
    var headers by remember { mutableStateOf(existing?.headers?.let(::formatHeaderLines).orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val badHeaders = stringResource(R.string.mcp_error_bad_header)

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("mcp_server_form")
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.mcp_field_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("mcp_field_name")
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.mcp_field_url)) },
            placeholder = { Text("https://example.com/mcp") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().testTag("mcp_field_url")
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(stringResource(R.string.mcp_field_token)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("mcp_field_token")
        )
        OutlinedTextField(
            value = headers,
            onValueChange = { headers = it },
            label = { Text(stringResource(R.string.mcp_field_headers)) },
            supportingText = { Text(stringResource(R.string.mcp_field_headers_hint)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag("mcp_field_headers")
        )
        if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !busy && url.isNotBlank(),
                onClick = {
                    val parsed = parseHeaderLines(headers) ?: run {
                        result = badHeaders
                        return@OutlinedButton
                    }
                    busy = true
                    scope.launch {
                        result = when (val probe = mcp.probe(url, token, parsed, strings)) {
                            is McpProbe.Ok -> strings(
                                R.string.mcp_connected,
                                probe.server.name.ifBlank { McpServersConnector.hostOf(url) },
                                strings.quantity(R.plurals.mcp_tools_count, probe.tools.size, probe.tools.size)
                            ) + if (isCleartextWithSecret(url, token, parsed)) {
                                " " + strings(R.string.mcp_error_cleartext)
                            } else {
                                ""
                            }
                            is McpProbe.Failed -> probe.message
                        }
                        busy = false
                    }
                },
                modifier = Modifier.weight(1f).testTag("mcp_test")
            ) { Text(stringResource(if (busy) R.string.mcp_testing else R.string.mcp_test_connection)) }
            Button(
                enabled = !busy && url.isNotBlank() && name.isNotBlank(),
                onClick = {
                    val parsed = parseHeaderLines(headers) ?: run {
                        result = badHeaders
                        return@Button
                    }
                    busy = true
                    scope.launch {
                        val (saved, text) = mcp.save(existing?.id, name, url, token, parsed, strings)
                        busy = false
                        if (saved) onDone(true, text) else result = text
                    }
                },
                modifier = Modifier.weight(1f).testTag("mcp_save")
            ) { Text(stringResource(if (busy) R.string.mcp_saving else R.string.mcp_save)) }
        }
        TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.mcp_cancel)) }
    }
}
