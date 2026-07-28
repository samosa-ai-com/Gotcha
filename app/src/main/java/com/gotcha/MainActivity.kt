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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.gotcha.agent.ChatViewModel
import com.gotcha.audio.AudioApi
import com.gotcha.audio.ModelCategory
import com.gotcha.auth.SamosaAuthManager
import com.gotcha.auth.SamosaSignInResult
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.data.ThemeMode
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.LLMClient
import com.gotcha.service.AssistiveBallService
import com.gotcha.service.GotchaDeviceAdminReceiver
import com.gotcha.tools.ScreenPerception
import com.gotcha.tools.ToolResult
import com.gotcha.ui.AppDrawerContent
import com.gotcha.ui.ChatScreen
import com.gotcha.ui.ConnectorsScreen
import com.gotcha.ui.SettingsPage
import com.gotcha.ui.SettingsScreen
import com.gotcha.ui.theme.GotchaTheme
import com.gotcha.ui.theme.SkinBackdrop
import com.gotcha.ui.theme.Skins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import android.provider.Settings as AndroidSettings

enum class Route { HOME, SETTINGS, CONNECTORS }

class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var samosaAuthManager: SamosaAuthManager

    /** Set when launched from the assistive ball's "Open Chat" option. */
    private var openChatRequested by mutableStateOf(false)

    /** Set when brought to front by the assistive ball (Operator-origin chats). */
    private var openedFromBall by mutableStateOf(false)

    /** Appearance, applied immediately when changed in Settings ▸ Appearance. */
    private var appearance by mutableStateOf(Appearance())

    /** The four settings that decide what the app looks like, and nothing else. */
    private data class Appearance(
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val skinId: String = Skins.DEFAULT_ID,
        val matchSystemBrightness: Boolean = true,
        val reduceTransparency: Boolean = false
    )

    private fun Settings.appearance() = Appearance(
        themeMode = themeMode,
        skinId = skinId,
        matchSystemBrightness = matchSystemBrightness,
        reduceTransparency = reduceTransparency
    )

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

    /**
     * Health Connect uses its own permission contract rather than the standard
     * runtime dialog, so it needs a launcher of its own.
     */
    private val healthConnectLauncher = registerForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        com.gotcha.tools.HealthPermissionState.set(granted.isNotEmpty())
        val message = if (granted.isEmpty()) {
            "No health permissions granted."
        } else {
            "Health Connect: ${granted.size} permission(s) granted."
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** Requests all runtime permissions at once on first launch. */
    private val firstLaunchLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // No action needed; the Settings screen shows live permission state.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleOwner = this
        // Some OEM skins (e.g. MIUI) force-dark light-themed apps even when the
        // theme opts out; disabling on the decorView covers those cases too.
        window.decorView.isForceDarkAllowed = false
        settingsRepository = SettingsRepository(this)
        samosaAuthManager = SamosaAuthManager(applicationContext, settingsRepository)
        openChatRequested = intent?.getBooleanExtra(EXTRA_OPEN_CHAT, false) == true
        openedFromBall = intent?.getBooleanExtra(EXTRA_FROM_ASSISTIVE_BALL, false) == true

        // Phase 7: tools report special-access markers; open Settings deep-links.
        // Runtime permissions are no longer requested here — they are pre-configured
        // in Settings → Permissions or auto-requested on first launch.
        lifecycleScope.launch {
            chatViewModel.permissionRequests.collect { permission ->
                handlePermissionRequest(permission)
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
            if (ScreenPerception.mediaProjectionResultData == null &&
                !prefs.getBoolean(KEY_SUPPRESS_MEDIA_PROJECTION_PROMPT, false)
            ) {
                val mpManager = getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as android.media.projection.MediaProjectionManager
                mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
            }
        }

        appearance = settingsRepository.load().appearance()

        setContent {
            val darkTheme = when (appearance.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            GotchaTheme(
                darkTheme = darkTheme,
                skinId = appearance.skinId,
                matchSystemBrightness = appearance.matchSystemBrightness,
                reduceTransparency = appearance.reduceTransparency
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // The wallpaper sits under everything; an opaque skin draws
                    // nothing here and the Surface above remains the whole story.
                    SkinBackdrop(Modifier.fillMaxSize())
                    GotchaApp()
                }
            }
        }
    }

    /** Opens the matching special-access Settings screen for a tool-reported marker. */
    private fun handlePermissionRequest(permission: String) {
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
                Intent(
                    AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
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
                    GotchaDeviceAdminReceiver.componentName(this)
                ).putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Gotcha uses device administration to lock the screen, enforce " +
                        "password policy, and disable the camera when you ask it to."
                )
            )
            ToolResult.VPN_CONSENT -> VpnService.prepare(this)?.let {
                startActivity(it)
            } ?: Toast.makeText(
                this,
                "VPN already authorized — ask the assistant again.",
                Toast.LENGTH_SHORT
            ).show()
            "special:screenshot_consent" -> {
                val mpManager = getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as android.media.projection.MediaProjectionManager
                mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
            }
            ToolResult.HEALTH_CONNECT -> requestHealthConnect()
            // Runtime permissions are mapped in Settings → Permissions; skip here.
        }
    }

    /**
     * Opens Health Connect's permission screen, or steers to the Play listing when
     * no provider is installed (Android 13 and below ship it as a separate app).
     */
    private fun requestHealthConnect() {
        val status = androidx.health.connect.client.HealthConnectClient.getSdkStatus(this)
        if (status == androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE) {
            healthConnectLauncher.launch(com.gotcha.tools.HealthTool.PERMISSIONS)
            return
        }
        Toast.makeText(
            this,
            "Health Connect is not available — install or update it from the Play Store.",
            Toast.LENGTH_LONG
        ).show()
        runCatching {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    android.net.Uri.parse(
                        "market://details?id=com.google.android.apps.healthdata"
                    )
                )
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_CHAT, false)) {
            openChatRequested = true
        }
        if (intent.getBooleanExtra(EXTRA_FROM_ASSISTIVE_BALL, false)) {
            openedFromBall = true
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

    /** Any Samosa 401 (LLM or audio) clears the session and refreshes the
     *  ChatViewModel so the UI reflects the unauthenticated state. */
    fun onSamosaUnauthorized() {
        val current = settingsRepository.load()
        val samosaUsed = current.provider == com.gotcha.data.LlmProvider.SAMOSA_AI
        val samosaToken = current.samosaSessionToken
        if (!samosaUsed && samosaToken.isBlank()) {
            // No Samosa session to invalidate — bail.
            return
        }
        settingsRepository.clearSamosaSession()
        Toast.makeText(this, "Samosa session expired — please sign in again.", Toast.LENGTH_LONG).show()
        chatViewModel.refreshSettings()
    }

    override fun onStop() {
        super.onStop()
        chatViewModel.setForeground(false)
    }

    @Composable
    private fun GotchaApp() {
        val state by chatViewModel.uiState.collectAsState()
        val sessions by chatViewModel.sessions.collectAsState()

        // An unconfigured install opens Settings; send it straight to the page
        // holding the API key and model rather than to the category list.
        val unconfigured = remember { !settingsRepository.load().isConfigured }
        var currentRoute by remember {
            mutableStateOf(if (unconfigured) Route.SETTINGS else Route.HOME)
        }
        var assistiveBallOn by remember { mutableStateOf(settingsRepository.load().assistiveBallEnabled) }
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) { chatViewModel.refreshSettings() }

        // Keep the drawer's session list fresh: on open, and after a run finishes
        // (picks up the auto-generated title once the first exchange is saved).
        LaunchedEffect(drawerState.isOpen) {
            if (drawerState.isOpen) chatViewModel.refreshSessions()
        }
        LaunchedEffect(state.isBusy) {
            if (!state.isBusy) chatViewModel.refreshSessions()
        }

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
                currentRoute = Route.HOME
                openChatRequested = false
            }
        }

        // Opened via the assistive ball: return to any running chat, or default a
        // fresh chat to Operator (ball-initiated chats are Operator by design).
        LaunchedEffect(openedFromBall) {
            if (openedFromBall) {
                currentRoute = Route.HOME
                chatViewModel.onOpenedFromAssistiveBall()
                openedFromBall = false
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = currentRoute == Route.HOME || drawerState.isOpen,
            drawerContent = {
                AppDrawerContent(
                    sessions = sessions,
                    activeSessionId = state.activeSessionId,
                    onNewChat = {
                        scope.launch { drawerState.close() }
                        chatViewModel.openSession(null)
                        currentRoute = Route.HOME
                    },
                    onSessionClick = { id ->
                        scope.launch { drawerState.close() }
                        chatViewModel.openSession(id)
                        currentRoute = Route.HOME
                    },
                    onDeleteSession = chatViewModel::deleteSession,
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        currentRoute = Route.SETTINGS
                    },
                    onOpenConnectors = {
                        scope.launch { drawerState.close() }
                        currentRoute = Route.CONNECTORS
                    },
                    maxContextTokens = state.maxContextTokens,
                    activeTokenCount = state.tokenCount
                )
            }
        ) {
            when (currentRoute) {
                Route.SETTINGS -> {
                    BackHandler { currentRoute = Route.HOME }
                    SettingsScreen(
                        load = { settingsRepository.load() },
                        onSave = { mutate ->
                            settingsRepository.save(mutate(settingsRepository.load()))
                            chatViewModel.refreshSettings()
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
                            com.gotcha.data.GotchaStorage.chatsRoot().listFiles()?.forEach { chatDir ->
                                java.io.File(chatDir, ".debug").deleteRecursively()
                            }
                        },
                        onBack = { currentRoute = Route.HOME },
                        onAppearanceChange = { updated -> appearance = updated.appearance() },
                        onRefreshAudioModels = { s ->
                            withContext(Dispatchers.IO) {
                                val ttsBase = s.effectiveTtsBaseUrl
                                if (ttsBase.isBlank()) return@withContext Pair(emptyList(), emptyList())
                                // AudioApi's onUnauthorized fires on an OkHttp thread; Toast
                                // and refreshSettings() must run on the main thread.
                                val onUnauthorized: () -> Unit = {
                                    this@MainActivity.runOnUiThread {
                                        this@MainActivity.onSamosaUnauthorized()
                                    }
                                }
                                val ttsApi = AudioApi(
                                    baseUrl = ttsBase,
                                    apiKey = s.effectiveTtsApiKey,
                                    onUnauthorized = onUnauthorized
                                )
                                val ttsAll = ttsApi.listAudioModels()
                                val ttsModels = ttsAll.filter { it.category == ModelCategory.TTS }
                                val sttBase = s.effectiveSttBaseUrl
                                val sttModels = if (sttBase.isNotBlank() && sttBase != ttsBase) {
                                    val sttApi = AudioApi(
                                        baseUrl = sttBase,
                                        apiKey = s.effectiveSttApiKey,
                                        onUnauthorized = onUnauthorized
                                    )
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
                                    apiKey = s.effectiveApiKey,
                                    baseUrl = s.effectiveBaseUrl,
                                    model = s.model,
                                    context = this@MainActivity,
                                    apiTimeoutSeconds = s.apiTimeoutSeconds
                                )
                                client.listModels()
                            }
                        },
                        onSamosaSignIn = {
                            when (val r = samosaAuthManager.signIn(this@MainActivity)) {
                                is SamosaSignInResult.Success -> {
                                    // Token is already persisted by the auth manager.
                                    val token = settingsRepository.load().samosaSessionToken
                                    chatViewModel.refreshSettings()
                                    Result.success(r.email to token)
                                }
                                is SamosaSignInResult.Cancelled ->
                                    Result.failure(Exception("Sign-in cancelled."))
                                is SamosaSignInResult.Error ->
                                    Result.failure(Exception(r.message))
                            }
                        },
                        onSamosaSignOut = {
                            samosaAuthManager.signOut()
                            chatViewModel.refreshSettings()
                        },
                        onTestVoice = { language -> chatViewModel.testAndroidTts(language) },
                        packageName = packageName,
                        initialPage = if (unconfigured) SettingsPage.AI_CONFIG else null
                    )
                }
                Route.CONNECTORS -> {
                    // refreshSettings() on the way out, like every other route: the
                    // enable/disable toggles write disabledConnectors, and the agent
                    // reads it from ChatViewModel's cached Settings.
                    val leaveConnectors = {
                        chatViewModel.refreshSettings()
                        currentRoute = Route.HOME
                    }
                    BackHandler { leaveConnectors() }
                    ConnectorsScreen(onBack = leaveConnectors)
                }
                Route.HOME -> {
                    // Back from an active chat returns to a fresh home (new session,
                    // new greeting); on an empty home the default back exits the app.
                    BackHandler(enabled = state.messages.isNotEmpty() && !state.isBusy) {
                        chatViewModel.openSession(null)
                    }
                    ChatScreen(
                        state = state,
                        onSend = { text, imageBase64 -> chatViewModel.sendMessage(text, imageBase64) },
                        onStop = chatViewModel::stopAgent,
                        onConfirm = chatViewModel::confirmPendingActions,
                        onAnswer = chatViewModel::submitAnswer,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onOpenSettings = { currentRoute = Route.SETTINGS },
                        sessionTitle = sessions.firstOrNull { it.id == state.activeSessionId }?.title,
                        assistiveBallEnabled = assistiveBallOn,
                        onToggleAssistiveBall = { enabled ->
                            assistiveBallOn = setAssistiveBall(enabled)
                        },
                        onPickImage = { uri -> chatViewModel.loadImageBase64(uri) },
                        onSwitchAgent = chatViewModel::switchAgent,
                        onSetAgent = chatViewModel::setAgent,
                        onSpeak = chatViewModel::speak,
                        onStopSpeaking = chatViewModel::stopSpeaking,
                        onStartListening = chatViewModel::startListening,
                        onStopRecording = { cb -> chatViewModel.stopRecording(cb) },
                        onExportChat = chatViewModel::exportChat,
                        onReturnToRunning = {
                            state.runningSessionId?.let { chatViewModel.openSession(it) }
                        }
                    )
                }
            }
        }

        // Composed last so this innermost enabled handler wins back dispatch:
        // back closes an open drawer before any other navigation.
        BackHandler(enabled = drawerState.isOpen) {
            scope.launch { drawerState.close() }
        }
    }

    /** Cheap "ping" request to validate credentials (Phase 6). */
    private suspend fun testConnection(settings: Settings): Result<String> = runCatching {
        val client = LLMClient(
            apiKey = settings.effectiveApiKey,
            baseUrl = settings.effectiveBaseUrl,
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

        /** Intent extra: brought to front by the assistive ball (Operator-origin). */
        const val EXTRA_FROM_ASSISTIVE_BALL = "com.gotcha.FROM_ASSISTIVE_BALL"

        /** SharedPreferences key to track first-launch permission setup. */
        const val KEY_FIRST_LAUNCH_DONE = "first_launch_setup_done"

        /** SharedPreferences key: when true, skip the MediaProjection consent prompt (test-only). */
        const val KEY_SUPPRESS_MEDIA_PROJECTION_PROMPT = "suppress_media_projection_prompt"

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.READ_MEDIA_IMAGES)
            }
        }
        firstLaunchLauncher.launch(perms.toTypedArray())
    }
}
