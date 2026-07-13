package com.gotcha

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import com.gotcha.audio.ModelCategory
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.LLMClient
import com.gotcha.service.AssistiveBallService
import com.gotcha.service.GotchaDeviceAdminReceiver
import com.gotcha.tools.ScreenPerception
import com.gotcha.tools.ToolResult
import com.gotcha.ui.ChatScreen
import com.gotcha.ui.SessionsScreen
import com.gotcha.ui.SettingsScreen
import com.gotcha.ui.theme.GotchaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import android.provider.Settings as AndroidSettings

enum class Route { SESSIONS, CHAT, SETTINGS }

class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels()
    private lateinit var settingsRepository: SettingsRepository

    /** Set when launched from the assistive ball's "Open Chat" option. */
    private var openChatRequested by mutableStateOf(false)

    /** MediaProjection consent result — stores intent for screenshot capture. */
    private val mediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            android.util.Log.d(
                "ScreenCapture",
                "mediaProjectionLauncher: resultCode=${result.resultCode}, data=${result.data != null}"
            )
            if (result.resultCode == RESULT_OK && result.data != null) {
                ScreenPerception.mediaProjectionResultData = result.data
                Toast.makeText(this, "Screenshot permission granted.", Toast.LENGTH_SHORT).show()
                android.util.Log.d("ScreenCapture", "mediaProjectionLauncher: consent stored successfully")
            } else {
                android.util.Log.w("ScreenCapture", "mediaProjectionLauncher: consent denied or data null")
            }
        }

    /** Requests all runtime permissions at once on first launch. */
    private val firstLaunchLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // No action needed; the Settings screen shows live permission state.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleOwner = this
        settingsRepository = SettingsRepository(this)
        openChatRequested = intent?.getBooleanExtra(EXTRA_OPEN_CHAT, false) == true

        // Phase 7: tools report special-access markers; open Settings deep-links.
        // Runtime permissions are no longer requested here — they are pre-configured
        // in Settings → Permissions or auto-requested on first launch.
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Intent(
                                AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        } else {
                            Intent(
                                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName")
                            )
                        }
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
                        startActivity(it)
                    } ?: Toast.makeText(
                        this@MainActivity,
                        "VPN already authorized — ask the assistant again.",
                        Toast.LENGTH_SHORT
                    ).show()
                    "special:screenshot_consent" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            val mpManager = getSystemService(
                                Context.MEDIA_PROJECTION_SERVICE
                            ) as android.media.projection.MediaProjectionManager
                            mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
                        }
                    }
                    // Runtime permissions are mapped in Settings → Permissions; skip here.
                }
            }
        }

        // Collect exported chat content and launch a share sheet
        lifecycleScope.launch {
            chatViewModel.exportContent.collect { markdown ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, markdown)
                    putExtra(Intent.EXTRA_SUBJECT, "Gotcha Chat Export")
                }
                startActivity(Intent.createChooser(shareIntent, "Share Chat Export"))
            }
        }

        // Auto-request runtime permissions on first launch
        lifecycleScope.launch {
            val prefs = settingsRepository.prefs
            if (!prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false)) {
                requestAllRuntimePermissions()
                prefs.edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply()
            }
            // Request MediaProjection consent for screenshot capture
            // Always request if not granted in this process session (cleared on process kill)
            if (ScreenPerception.mediaProjectionResultData == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val mpManager = getSystemService(
                        Context.MEDIA_PROJECTION_SERVICE
                    ) as android.media.projection.MediaProjectionManager
                    mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_CHAT, false)) {
            openChatRequested = true
        }
    }

    override fun onStart() {
        super.onStart()
        chatViewModel.setForeground(true)
    }

    /**
     * Start or stop the assistive-ball foreground service. Returns the resulting
     * enabled state. When enabling without the overlay permission, deep-links the user
     * to grant it and leaves the ball disabled (they can toggle again afterwards).
     */
    private fun setAssistiveBall(enabled: Boolean): Boolean {
        if (enabled) {
            if (!AndroidSettings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                Toast.makeText(
                    this,
                    "Allow \"Display over other apps\", then turn the ball on again.",
                    Toast.LENGTH_LONG
                ).show()
                persistAssistiveBall(false)
                return false
            }
            startForegroundService(AssistiveBallService.startIntent(this))
            persistAssistiveBall(true)
            return true
        } else {
            startService(AssistiveBallService.stopIntent(this))
            persistAssistiveBall(false)
            return false
        }
    }

    private fun persistAssistiveBall(enabled: Boolean) {
        val current = settingsRepository.load()
        settingsRepository.save(current.copy(assistiveBallEnabled = enabled))
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
        var previousRoute by remember { mutableStateOf(Route.SESSIONS) }
        var assistiveBallOn by remember { mutableStateOf(settingsRepository.load().assistiveBallEnabled) }

        LaunchedEffect(Unit) { chatViewModel.refreshSettings() }

        // Track the service's real state so the toggle updates when the ball is
        // hidden from its own overlay menu (which stops the service directly).
        LaunchedEffect(Unit) {
            AssistiveBallService.isRunning.collect { running ->
                assistiveBallOn = running
            }
        }

        // Honor the assistive ball's "Open Chat" option.
        LaunchedEffect(openChatRequested) {
            if (openChatRequested) {
                currentRoute = Route.CHAT
                openChatRequested = false
            }
        }

        when (currentRoute) {
            Route.SETTINGS -> {
                BackHandler { currentRoute = previousRoute }
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
                            apiKey = "unused",
                            baseUrl = "http://localhost/",
                            context = this@MainActivity
                        ).clearCache()
                    },
                    onClearDebugScreenshots = {
                        val baseDir = java.io.File("/storage/emulated/0/Gotcha")
                        if (baseDir.exists()) {
                            baseDir.walkTopDown()
                                .filter { it.isFile && it.name.startsWith("screenshot_overlay_") }
                                .forEach { it.delete() }
                        }
                    },
                    onBack = { currentRoute = previousRoute },
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
                    },
                    onRefreshChatModels = { s ->
                        withContext(Dispatchers.IO) {
                            val client = LLMClient(
                                apiKey = s.apiKey,
                                baseUrl = s.baseUrl,
                                model = s.model,
                                context = this@MainActivity,
                                apiTimeoutSeconds = s.apiTimeoutSeconds
                            )
                            client.listModels()
                        }
                    },
                    packageName = packageName
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
                    onOpenSettings = {
                        previousRoute = Route.SESSIONS
                        currentRoute = Route.SETTINGS
                    },
                    assistiveBallEnabled = assistiveBallOn,
                    onToggleAssistiveBall = { enabled ->
                        assistiveBallOn = setAssistiveBall(enabled)
                    }
                )
            }
            Route.CHAT -> {
                BackHandler {
                    chatViewModel.refreshSessions()
                    currentRoute = Route.SESSIONS
                }
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
                    onOpenSettings = {
                        previousRoute = Route.CHAT
                        currentRoute = Route.SETTINGS
                    },
                    onPickImage = { uri -> chatViewModel.loadImageBase64(uri) },
                    onSwitchAgent = chatViewModel::switchAgent,
                    onSpeak = chatViewModel::speak,
                    onStartListening = chatViewModel::startListening,
                    onStopRecording = chatViewModel::stopRecording,
                    onExportChat = chatViewModel::exportChat
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
        response.choices.firstOrNull()?.message?.textContent?. take(60) ?: "empty response"
    }

    companion object {
        /** Intent extra: when true, launch straight into the chat screen. */
        const val EXTRA_OPEN_CHAT = "com.gotcha.OPEN_CHAT"

        /** SharedPreferences key to track first-launch permission setup. */
        const val KEY_FIRST_LAUNCH_DONE = "first_launch_setup_done"

        /** Lifecycle owner for CameraX binding. Set in onCreate, valid while activity lives. */
        @Volatile
        var lifecycleOwner: androidx.lifecycle.LifecycleOwner? = null
            private set
    }

    /** Request all runtime permissions the app needs on first launch. */
    private fun requestAllRuntimePermissions() {
        val perms = mutableListOf<String>().apply {
            add(android.Manifest.permission.CAMERA)
            add(android.Manifest.permission.RECORD_AUDIO)
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            add(android.Manifest.permission.CALL_PHONE)
            add(android.Manifest.permission.SEND_SMS)
            add(android.Manifest.permission.READ_SMS)
            add(android.Manifest.permission.READ_CALL_LOG)
            add(android.Manifest.permission.READ_CONTACTS)
            add(android.Manifest.permission.WRITE_CONTACTS)
            add(android.Manifest.permission.READ_CALENDAR)
            add(android.Manifest.permission.WRITE_CALENDAR)
            if (Build.VERSION.SDK_INT <= 29) {
                add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.READ_MEDIA_IMAGES)
            }
        }
        firstLaunchLauncher.launch(perms.toTypedArray())
    }
}
