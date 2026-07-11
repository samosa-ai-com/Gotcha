package com.gotcha

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
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
import com.gotcha.audio.AudioApi
import com.gotcha.audio.AudioModel
import com.gotcha.audio.ModelCategory
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.LLMClient
import kotlinx.serialization.json.JsonPrimitive
import com.gotcha.service.GotchaDeviceAdminReceiver
import com.gotcha.tools.ToolResult
import com.gotcha.ui.ChatScreen
import com.gotcha.ui.SessionsScreen
import com.gotcha.ui.SettingsScreen
import com.gotcha.ui.theme.GotchaTheme
import kotlinx.coroutines.launch

enum class Route { SESSIONS, CHAT, SETTINGS }

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
        // "special:*" markers map to per-access Settings deep-links; anything else is a
        // real runtime permission handled by the single RequestPermission launcher.
        lifecycleScope.launch {
            chatViewModel.permissionRequests.collect { permission ->
                when (permission) {
                    ToolResult.WRITE_SETTINGS -> startActivity(
                        Intent(
                            AndroidSettings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                    ToolResult.USAGE_ACCESS -> startActivity(
                        Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS)
                    )
                    ToolResult.DND_ACCESS -> startActivity(
                        Intent(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    )
                    ToolResult.ACCESSIBILITY_ACCESS -> startActivity(
                        Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
                    )
                    ToolResult.NOTIFICATION_LISTENER_ACCESS -> startActivity(
                        Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    )
                    ToolResult.ALL_FILES_ACCESS -> startActivity(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Intent(
                            AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        ) else Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName"))
                    )
                    ToolResult.OVERLAY_ACCESS -> startActivity(
                        Intent(
                            AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                    ToolResult.DEVICE_ADMIN -> startActivity(
                        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(
                            DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                            GotchaDeviceAdminReceiver.componentName(this@MainActivity)
                        ).putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Gotcha uses device administration to lock the screen, enforce " +
                                "password policy, and disable the camera when you ask it to."
                        )
                    )
                    ToolResult.VPN_CONSENT -> VpnService.prepare(this@MainActivity)?.let {
                        // Non-null means consent is still needed; launch the system dialog.
                        startActivity(it)
                    } ?: Toast.makeText(
                        this@MainActivity,
                        "VPN already authorized — ask the assistant again.",
                        Toast.LENGTH_SHORT
                    ).show()
                    else -> permissionLauncher.launch(permission)
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

    override fun onStart() {
        super.onStart()
        chatViewModel.setForeground(true)
    }

    override fun onStop() {
        super.onStop()
        chatViewModel.setForeground(false)
    }

    @Composable
    private fun GotchaApp() {
        val state by chatViewModel.uiState.collectAsState()
        val sessions by chatViewModel.sessions.collectAsState()
        
        var currentRoute by remember { 
            mutableStateOf(if (settingsRepository.load().isConfigured) Route.SESSIONS else Route.SETTINGS) 
        }

        LaunchedEffect(Unit) { chatViewModel.refreshSettings() }

        when (currentRoute) {
            Route.SETTINGS -> {
                SettingsScreen(
                    initial = settingsRepository.load(),
                    onSave = { settings ->
                        settingsRepository.save(settings)
                        chatViewModel.refreshSettings()
                        currentRoute = Route.SESSIONS
                    },
                    onTestConnection = ::testConnection,
                    onClearLlmCache = {
                        LLMClient(
                            apiKey = "unused", baseUrl = "http://localhost/",
                            context = this@MainActivity
                        ).clearCache()
                    },
                    onBack = { currentRoute = Route.SESSIONS },
                    onRefreshAudioModels = { s ->
                        withContext(Dispatchers.IO) {
                            val ttsApi = AudioApi(s.ttsApiBaseUrl.ifBlank { s.baseUrl }, s.apiKey)
                            val ttsAll = ttsApi.listAudioModels()
                            val ttsModels = ttsAll.filter { it.category == ModelCategory.TTS }
                            val sttModels = if (s.sttApiBaseUrl.isNotBlank() && s.sttApiBaseUrl != s.ttsApiBaseUrl) {
                                val sttApi = AudioApi(s.sttApiBaseUrl, s.apiKey)
                                sttApi.listAudioModels().filter { it.category == ModelCategory.STT }
                            } else {
                                ttsAll.filter { it.category == ModelCategory.STT }
                            }
                            Pair(ttsModels, sttModels)
                        }
                    }
                )
            }
            Route.SESSIONS -> {
                SessionsScreen(
                    sessions = sessions,
                    onSessionClick = { id -> 
                        chatViewModel.openSession(id)
                        currentRoute = Route.CHAT
                    },
                    onDeleteSession = chatViewModel::deleteSession,
                    onNewChat = {
                        chatViewModel.openSession(null)
                        currentRoute = Route.CHAT
                    },
                    onOpenSettings = { currentRoute = Route.SETTINGS }
                )
            }
            Route.CHAT -> {
                ChatScreen(
                    state = state,
                    onSend = { text, imageBase64 -> chatViewModel.sendMessage(text, imageBase64) },
                    onStop = chatViewModel::stopAgent,
                    onConfirm = chatViewModel::confirmPendingActions,
                    onAnswer = chatViewModel::submitAnswer,
                    onBack = { 
                        chatViewModel.refreshSessions()
                        currentRoute = Route.SESSIONS 
                    },
                    onOpenSettings = { currentRoute = Route.SETTINGS },
                    onPickImage = { uri -> chatViewModel.loadImageBase64(uri) },
                    onSwitchAgent = chatViewModel::switchAgent,
                    onSpeak = chatViewModel::speak,
                    onStartListening = chatViewModel::startListening,
                    onStopRecording = chatViewModel::stopRecording
                )
            }
        }
    }

    /** Cheap "ping" request to validate credentials (Phase 6). */
    private suspend fun testConnection(settings: Settings): Result<String> = runCatching {
        val client = LLMClient(
            apiKey = settings.apiKey,
            baseUrl = settings.baseUrl,
            model = settings.model,
            context = this,
            apiTimeoutSeconds = settings.apiTimeoutSeconds
        )
        val response = client.chat(
            messages = listOf(ChatMessage(role = "user", content = JsonPrimitive("Reply with the single word: pong"))),
            temperature = 0f
        )
        response.choices.firstOrNull()?.message?.textContent?.take(60) ?: "empty response"
    }
}
