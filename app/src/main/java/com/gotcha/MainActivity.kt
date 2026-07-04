package com.gotcha

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.gotcha.agent.ChatViewModel
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.LLMClient
import com.gotcha.tools.ToolResult
import com.gotcha.ui.ChatScreen
import com.gotcha.ui.SettingsScreen
import com.gotcha.ui.theme.GotchaTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels()
    private lateinit var settingsRepository: SettingsRepository

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Toast.makeText(
                this,
                if (granted) "Permission granted — ask the assistant again."
                else "Permission denied. The assistant cannot perform that action.",
                Toast.LENGTH_SHORT
            ).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = SettingsRepository(this)

        // Phase 7: tools report missing permissions; request them here on first use.
        lifecycleScope.launch {
            chatViewModel.permissionRequests.collect { permission ->
                if (permission == ToolResult.WRITE_SETTINGS) {
                    startActivity(
                        Intent(
                            AndroidSettings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } else {
                    permissionLauncher.launch(permission)
                }
            }
        }

        setContent {
            GotchaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GotchaApp()
                }
            }
        }
    }

    @Composable
    private fun GotchaApp() {
        val state by chatViewModel.uiState.collectAsState()
        // Route straight to settings until an API key exists (Phase 6 gating).
        var showSettings by remember { mutableStateOf(!settingsRepository.load().isConfigured) }

        LaunchedEffect(Unit) { chatViewModel.refreshSettings() }

        if (showSettings) {
            SettingsScreen(
                initial = settingsRepository.load(),
                onSave = { settings ->
                    settingsRepository.save(settings)
                    chatViewModel.refreshSettings()
                },
                onTestConnection = ::testConnection,
                onClearLlmCache = {
                    LLMClient(
                        apiKey = "unused", baseUrl = "http://localhost/",
                        context = this@MainActivity
                    ).clearCache()
                },
                onBack = { showSettings = false }
            )
        } else {
            ChatScreen(
                state = state,
                onSend = chatViewModel::sendMessage,
                onConfirm = chatViewModel::confirmPendingActions,
                onClearChat = chatViewModel::clearChat,
                onOpenSettings = { showSettings = true }
            )
        }
    }

    /** Cheap "ping" request to validate credentials (Phase 6). */
    private suspend fun testConnection(settings: Settings): Result<String> = runCatching {
        val client = LLMClient(
            apiKey = settings.apiKey,
            baseUrl = settings.baseUrl,
            model = settings.model,
            context = this
        )
        val response = client.chat(
            messages = listOf(ChatMessage(role = "user", content = "Reply with the single word: pong")),
            temperature = 0f
        )
        response.choices.firstOrNull()?.message?.content?.take(60) ?: "empty response"
    }
}
