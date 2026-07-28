package com.gotcha.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gotcha.R
import com.gotcha.agent.ChatUiState
import com.gotcha.tools.AgentMode
import com.gotcha.ui.theme.GotchaMono
import com.gotcha.ui.theme.LocalSkin
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
    onStopSpeaking: () -> Unit = {},
    onStartListening: () -> Unit = {},
    onStopRecording: ((String) -> Unit) -> Unit = {},
    onExportChat: () -> Unit = {},
    onReturnToRunning: () -> Unit = {}
) {
    val skin = LocalSkin.current
    val isHome = state.messages.isEmpty()
    // A run is active in a DIFFERENT chat than the one being viewed: sending here
    // is blocked (one agent at a time), and a banner offers a jump back.
    val otherChatRunning = state.runningSessionId != null &&
        state.runningSessionId != state.activeSessionId
    var input by rememberSaveable { mutableStateOf("") }
    var pendingImageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingImageBase64 by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // Everything already in the transcript when this chat opened is history, and
    // history should not animate itself in. Re-keyed per session so switching
    // chats does not replay someone else's conversation.
    val arrivalBaseline = remember(state.activeSessionId) {
        state.messages.lastOrNull()?.id ?: -1L
    }
    val animatedIds = remember(state.activeSessionId) { mutableSetOf<Long>() }

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
                // Transparent so the wallpaper carries the bar on a glass skin.
                // On Deep Space the background underneath is the same colour the
                // bar would have painted, so this changes nothing there.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
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
                        // Operator can change the device, Monitor cannot. An icon
                        // that only changes shape is not enough signal for that, so
                        // the riskier of the two says its own name.
                        val isOperator = state.activeAgent == AgentMode.OPERATOR
                        if (isOperator) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                shape = RoundedCornerShape(999.dp),
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .clickable(enabled = !state.isBusy, onClick = onSwitchAgent)
                                    .testTag("agent_mode_badge")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.TouchApp,
                                        contentDescription = "Operator mode — tap to switch to Monitor",
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        "Operator",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        } else {
                            IconButton(onClick = onSwitchAgent, enabled = !state.isBusy) {
                                Icon(
                                    Icons.Outlined.Visibility,
                                    contentDescription = "Monitor mode — tap to switch to Operator",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (state.isSpeaking) {
                            IconButton(onClick = onStopSpeaking) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.VolumeOff,
                                    contentDescription = "Stop reading aloud",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
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
                ContextMeter(fraction = state.contextUsagePercent)
            }
            if (isHome) {
                val greeting = rememberSaveable(state.activeSessionId) { HOME_GREETINGS.random() }
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ComposeImage(
                        // The in-app mark, not the launcher icon: @mipmap/ic_launcher_round
                        // is an adaptive-icon XML, which painterResource cannot load.
                        painter = painterResource(R.drawable.gotcha_logo),
                        contentDescription = "Gotcha logo",
                        modifier = Modifier.size(96.dp).clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        greeting,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        // The largest text on the home screen, so it takes the
                        // primary ink. Secondary ink over a wallpaper was the
                        // weakest thing on the screen.
                        color = MaterialTheme.colorScheme.onSurface
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
                        // Only what arrives after the chat is open animates, and
                        // each id only ever animates once — `add` is false the
                        // second time an item is composed, which is what stops a
                        // scroll back up from replaying the whole thread.
                        val firstShow = remember(message.id) { animatedIds.add(message.id) }
                        MessageArrival(animate = firstShow && message.id > arrivalBaseline) {
                            MessageBubble(
                                message = message,
                                onSpeak = onSpeak,
                                isSpeaking = state.isSpeaking,
                                onStopSpeaking = onStopSpeaking
                            )
                        }
                    }
                }
            }

            if (state.activity != null) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ActivityPulse(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        state.activity,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GotchaMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (state.subAgentRunning != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer,
                            RoundedCornerShape(skin.cornerSmall)
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(16.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "⚡ ${state.subAgentRunning}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    state.subAgentCurrentAction?.let { action ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            action,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
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
                        .clip(RoundedCornerShape(skin.cornerSmall))
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
                                .clip(RoundedCornerShape(skin.cornerSmall)),
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

            if (state.isSpeaking) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { onStopSpeaking() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.AutoMirrored.Rounded.VolumeUp,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Reading aloud…",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Stop",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Stop speaking",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // One pill rather than a docked row: it floats clear of the bottom
            // edge, and the send button is the only saturated fill on the screen.
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
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
                            Text(
                                when {
                                    state.isTranscribing -> "Transcribing…"
                                    otherChatRunning -> "Finish the running chat first…"
                                    else -> "Let's Go"
                                }
                            )
                        },
                        enabled = !state.isBusy && !state.isTranscribing && state.isConfigured && !otherChatRunning,
                        maxLines = 6,
                        singleLine = false,
                        // The pill is the container now; a second outline inside it
                        // reads as a box in a box.
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent
                        )
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
                            state.isTranscribing -> {
                                // Recording has stopped but STT hasn't returned text yet —
                                // without this the button would blink back to the mic icon
                                // and look like the recording was dropped.
                                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
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
