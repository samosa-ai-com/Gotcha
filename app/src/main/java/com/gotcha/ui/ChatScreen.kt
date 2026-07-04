package com.gotcha.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gotcha.agent.ChatUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onConfirm: (Boolean) -> Unit,
    onClearChat: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gotcha") },
                actions = {
                    TextButton(onClick = onClearChat, enabled = !state.isBusy) { Text("Clear") }
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(message)
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

            if (!state.isConfigured) {
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Set your API key in settings to start chatting →")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask me to do something…") },
                    enabled = !state.isBusy && state.isConfigured,
                    maxLines = 4
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        onSend(input)
                        input = ""
                    },
                    enabled = !state.isBusy && state.isConfigured && input.isNotBlank()
                ) {
                    Text("Send")
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
}
