package com.gotcha.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gotcha.R
import com.gotcha.agent.ChatUiState
import com.gotcha.tools.AgentMode
import kotlinx.coroutines.delay
import androidx.compose.foundation.Image as ComposeImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onSend: (String, String?) -> Unit,
    onStop: () -> Unit,
    onConfirm: (Boolean) -> Unit,
    onAnswer: (String?) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    sessionTitle: String? = null,
    assistiveBallEnabled: Boolean = false,
    onToggleAssistiveBall: (Boolean) -> Unit = {},
    onPickImage: (Uri) -> String?,
    onSwitchAgent: () -> Unit,
    onSetAgent: (AgentMode) -> Unit = {},
    onSpeak: (String) -> Unit = {},
    onStartListening: () -> Unit = {},
    onStopRecording: ((String) -> Unit) -> Unit = {},
    onExportChat: () -> Unit = {},
    onReturnToRunning: () -> Unit = {}
) {
    val isHome = state.messages.isEmpty()
    // A run is active in a DIFFERENT chat than the one being viewed: sending here
    // is blocked (one agent at a time), and a banner offers a jump back.
    val otherChatRunning = state.runningSessionId != null &&
        state.runningSessionId != state.activeSessionId
    var input by rememberSaveable { mutableStateOf("") }
    var pendingImageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingImageBase64 by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingImageUri = uri
            pendingImageBase64 = onPickImage(uri)
        }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            delay(100)
            try {
                listState.animateScrollToItem(state.messages.size - 1)
            } catch (_: Exception) {
                listState.scrollToItem(state.messages.size - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isHome) "Gotcha" else (sessionTitle ?: "Gotcha"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Open menu")
                    }
                },
                actions = {
                    if (isHome) {
                        IconButton(onClick = { onToggleAssistiveBall(!assistiveBallEnabled) }) {
                            Icon(
                                Icons.Outlined.Adjust,
                                contentDescription = if (assistiveBallEnabled) {
                                    "Turn off assistive ball"
                                } else {
                                    "Turn on assistive ball"
                                },
                                tint = if (assistiveBallEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    } else {
                        val isOperator = state.activeAgent == AgentMode.OPERATOR
                        IconButton(onClick = onSwitchAgent, enabled = !state.isBusy) {
                            Icon(
                                if (isOperator) Icons.Filled.TouchApp else Icons.Outlined.Visibility,
                                contentDescription = if (isOperator) {
                                    "Operator mode — tap to switch to Monitor"
                                } else {
                                    "Monitor mode — tap to switch to Operator"
                                },
                                tint = if (isOperator) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        IconButton(onClick = onExportChat, enabled = !state.isBusy) {
                            Icon(Icons.Default.Share, contentDescription = "Export chat")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!isHome) {
                LinearProgressIndicator(
                    progress = { state.contextUsagePercent },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (state.contextUsagePercent > 0.8f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
            if (isHome) {
                val greeting = rememberSaveable(state.activeSessionId) { HOME_GREETINGS.random() }
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ComposeImage(
                        painter = painterResource(R.mipmap.ic_launcher_round),
                        contentDescription = "Gotcha logo",
                        modifier = Modifier.size(96.dp).clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        greeting,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    AgentModeSelector(
                        selected = state.activeAgent,
                        onSelect = onSetAgent
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("message_list")
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(message = message, onSpeak = onSpeak)
                    }
                }
            }

            if (state.activity != null) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.width(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(state.activity, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (state.subAgentRunning != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A1A2E), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(16.dp),
                            color = Color(0xFFE0D4FF)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "⚡ ${state.subAgentRunning}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE0D4FF)
                        )
                    }
                    state.subAgentCurrentAction?.let { action ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            action,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE0D4FF).copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (!state.isConfigured) {
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Set your API key in settings to start chatting →")
                }
            }

            // "Agent working in another chat" banner — tap to jump back to it.
            if (otherChatRunning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable { onReturnToRunning() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "⚡ Agent working in “${state.runningSessionTitle ?: "another chat"}” — tap to return",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Image attachment preview
            if (pendingImageBase64 != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    val bitmap = try {
                        val bytes = android.util.Base64.decode(pendingImageBase64, android.util.Base64.DEFAULT)
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (_: Exception) { null }
                    if (bitmap != null) {
                        ComposeImage(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Attached image",
                            modifier = Modifier
                                .height(120.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                    IconButton(
                        onClick = {
                            pendingImageUri = null
                            pendingImageBase64 = null
                        },
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Remove image")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    enabled = !state.isBusy && state.isConfigured && !otherChatRunning
                ) {
                    Text("+", style = MaterialTheme.typography.titleLarge)
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f).testTag("chat_input"),
                    shape = RoundedCornerShape(24.dp),
                    placeholder = {
                        Text(if (otherChatRunning) "Finish the running chat first…" else "Let's Go")
                    },
                    enabled = !state.isBusy && state.isConfigured && !otherChatRunning,
                    maxLines = 6,
                    singleLine = false
                )
                Spacer(modifier = Modifier.width(8.dp))
                // All action buttons use the same 40dp box so the layout never resizes
                Box(modifier = Modifier.size(40.dp)) {
                    when {
                        state.isBusy -> {
                            Button(
                                onClick = onStop,
                                modifier = Modifier.size(40.dp),
                                contentPadding = ButtonDefaults.TextButtonContentPadding,
                                shape = CircleShape
                            ) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = "Stop",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        state.isRecording || state.isListening -> {
                            // Recording in progress — show red stop button for both providers
                            Button(
                                onClick = {
                                    onStopRecording { text ->
                                        if (input.isNotEmpty()) {
                                            input += " " + text
                                        } else {
                                            input = text
                                        }
                                    }
                                },
                                modifier = Modifier.size(40.dp),
                                contentPadding = ButtonDefaults.TextButtonContentPadding,
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = "Stop recording",
                                    modifier = Modifier.size(20.dp),
                                    tint = Color.White
                                )
                            }
                        }
                        input.isBlank() && pendingImageBase64 == null -> {
                            IconButton(
                                onClick = onStartListening,
                                modifier = Modifier.size(40.dp),
                                enabled = state.isConfigured && !otherChatRunning
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = "Voice input")
                            }
                        }
                        else -> {
                            IconButton(
                                onClick = {
                                    onSend(input, pendingImageBase64)
                                    input = ""
                                    pendingImageUri = null
                                    pendingImageBase64 = null
                                },
                                modifier = Modifier.size(40.dp).testTag("send_button"),
                                enabled = state.isConfigured && !otherChatRunning &&
                                    (input.isNotBlank() || pendingImageBase64 != null)
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "Send")
                            }
                        }
                    }
                }
            }
        }
    }

    state.pendingConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = { onConfirm(false) },
            title = { Text("Allow these actions?") },
            text = {
                Text(
                    "The assistant wants to run:\n\n${pending.description}",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(onClick = { onConfirm(true) }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { onConfirm(false) }) { Text("Deny") }
            }
        )
    }

    if (state.pendingQuestion != null) {
        val pending = state.pendingQuestion!!
        var customAnswer by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { onAnswer(null) },
            title = { Text(pending.question) },
            text = {
                Column {
                    if (pending.options.isNotEmpty()) {
                        pending.options.forEach { option ->
                            Button(onClick = { onAnswer(option) }, modifier = Modifier.fillMaxWidth()) {
                                Text(option)
                            }
                        }
                    }
                    if (pending.allowCustom || pending.options.isEmpty()) {
                        OutlinedTextField(
                            value = customAnswer,
                            onValueChange = { customAnswer = it },
                            label = { Text("Your answer") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(onClick = { onAnswer(customAnswer.trim()) }, enabled = customAnswer.isNotBlank()) {
                            Text("Submit")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onAnswer(null) }) { Text("Skip") } }
        )
    }
}

/**
 * Home-screen segmented control to choose the agent before the first message.
 * Monitor = read-only, Operator = full device control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentModeSelector(
    selected: AgentMode,
    onSelect: (AgentMode) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Agent",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selected == AgentMode.MONITOR,
                onClick = { onSelect(AgentMode.MONITOR) },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                label = { Text("Monitor") }
            )
            FilterChip(
                selected = selected == AgentMode.OPERATOR,
                onClick = { onSelect(AgentMode.OPERATOR) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.TouchApp,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                label = { Text("Operator") }
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            if (selected == AgentMode.MONITOR) {
                "Read-only — observes but cannot change your device."
            } else {
                "Full control — can make changes to your device."
            },
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
