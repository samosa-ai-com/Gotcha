package com.gotcha

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.gotcha.agent.ChatViewModel
import com.gotcha.audio.AudioApi
import com.gotcha.audio.AudioModel
import com.gotcha.audio.ModelCategory
import com.gotcha.auth.SamosaAuthManager
import com.gotcha.auth.SamosaSignInResult
import com.gotcha.data.FeedbackChannel
import com.gotcha.data.FeedbackPrefill
import com.gotcha.data.LEGAL_VERSION
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.data.computeFeedbackStats
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.LLMClient
import com.gotcha.notifications.NotificationDispatcher
import com.gotcha.notifications.NotificationPayload
import com.gotcha.notifications.ServerMessages
import com.gotcha.service.AssistiveBallService
import com.gotcha.service.GotchaDeviceAdminReceiver
import com.gotcha.tools.ScreenPerception
import com.gotcha.tools.ToolResult
import com.gotcha.ui.AppDrawerContent
import com.gotcha.ui.ChatScreen
import com.gotcha.ui.ConnectorsScreen
import com.gotcha.ui.FeedbackSheet
import com.gotcha.ui.NotificationDetailDialog
import com.gotcha.ui.SettingsPage
import com.gotcha.ui.SettingsScreen
import com.gotcha.ui.SharePosterSheet
import com.gotcha.ui.SharePosterState
import com.gotcha.ui.theme.GotchaTheme
import com.gotcha.ui.theme.SkinBackdrop
import com.gotcha.ui.theme.Skins
import com.gotcha.ui.tour.LocalTourAnchors
import com.gotcha.ui.tour.TourHost
import com.gotcha.ui.tour.TourNavigation
import com.gotcha.ui.tour.TourOverlay
import com.gotcha.ui.tour.TourPlace
import com.gotcha.ui.tour.rememberTourHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import android.provider.Settings as AndroidSettings

enum class Route { HOME, SETTINGS, CONNECTORS }

/**
 * Where the user is, in the tour's vocabulary. Null means somewhere the tour has
 * nothing to say about — which is what hides the coach mark when they wander off
 * mid-step rather than pinning it over an unrelated screen.
 */
private fun tourPlaceOf(route: Route, page: SettingsPage?, drawerOpen: Boolean): TourPlace? = when {
    route == Route.HOME && drawerOpen -> TourPlace.CHAT_DRAWER
    route == Route.HOME -> TourPlace.CHAT
    route == Route.SETTINGS -> when (page) {
        null -> TourPlace.SETTINGS_HOME
        SettingsPage.AI_CONFIG -> TourPlace.AI_CONFIG
        SettingsPage.PERMISSIONS -> TourPlace.PERMISSIONS
        SettingsPage.PERSONAL_INFO -> TourPlace.PERSONAL_INFO
        else -> null
    }
    else -> null
}

/** The inverse: the route and settings page that put the user at [place]. */
private fun routeForTourPlace(place: TourPlace): Pair<Route, SettingsPage?> = when (place) {
    TourPlace.CHAT, TourPlace.CHAT_DRAWER -> Route.HOME to null
    TourPlace.SETTINGS_HOME -> Route.SETTINGS to null
    TourPlace.AI_CONFIG -> Route.SETTINGS to SettingsPage.AI_CONFIG
    TourPlace.PERMISSIONS -> Route.SETTINGS to SettingsPage.PERMISSIONS
    TourPlace.PERSONAL_INFO -> Route.SETTINGS to SettingsPage.PERSONAL_INFO
}

@Suppress("LargeClass")
class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var samosaAuthManager: SamosaAuthManager

    /** Active notification payload for display in scrollable dialog. */
    private var notificationPayload by mutableStateOf<NotificationPayload?>(null)

    /** Set when launched from the assistive ball's "Open Chat" option. */
    private var openChatRequested by mutableStateOf(false)

    /** Set when brought to front by the assistive ball (Operator-origin chats). */
    private var openedFromBall by mutableStateOf(false)

    /** Appearance, applied immediately when changed in Settings ▸ Appearance. */
    private var appearance by mutableStateOf(Appearance())

    // ---- "Share your Gotcha moment" poster state ----
    private val sharePoster: SharePosterState by lazy { SharePosterState(this, chatViewModel) }

    /**
     * ETag-style version of the legal bundle the user has accepted. Empty until
     * the first-launch dialog is dismissed with "I agree." Re-prompted whenever
     * [Settings.LEGAL_VERSION] (in [SettingsRepository]) is bumped.
     */
    private var legalAcceptedVersion by mutableStateOf("")

    /** The one setting that decides what the app looks like. */
    private data class Appearance(val skinId: String = Skins.DEFAULT_ID)

    /**
     * Repaints the window behind Compose in the current skin's ground. themes.xml
     * can only name one colour and has to guess Deep Space; once the setting has
     * been read, an activity recreate should flash the skin the user actually
     * chose rather than a slate blue they have never seen.
     */
    private fun applyLaunchBackground() {
        val skin = Skins.byId(appearance.skinId)
        window.setBackgroundDrawable(ColorDrawable(skin.launchGround.toArgb()))
    }

    private fun Settings.appearance() = Appearance(skinId = skinId)

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
        handleNotificationIntent(intent)

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
            // MediaProjection consent is deliberately NOT requested here. The token
            // dies with the process and is single-use on API 34, so prompting at
            // launch would re-fire the system dialog on every cold start for a
            // capability the user hasn't asked for yet. It is requested on demand
            // instead — see the "special:screenshot_consent" branch below.
        }

        appearance = settingsRepository.load().appearance()
        applyLaunchBackground()

        // First-launch / re-acceptance gate. Stored version is whatever the user
        // last agreed to; if it doesn't match the current LEGAL_VERSION (or is
        // empty), the consent dialog shows and the rest of the app waits behind
        // it. The dialog itself is mounted in [GotchaApp]; this only seeds the
        // initial state for the activity.
        legalAcceptedVersion = settingsRepository.load().legalAcceptedVersion

        setContent {
            GotchaTheme(skinId = appearance.skinId) {
                val tour = rememberTourHost(
                    repository = settingsRepository,
                    ready = legalAcceptedVersion == LEGAL_VERSION
                )
                CompositionLocalProvider(LocalTourAnchors provides tour.anchors) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        // The wallpaper sits under everything; an opaque skin draws
                        // nothing here and the Surface above remains the whole story.
                        SkinBackdrop(Modifier.fillMaxSize())
                        GotchaApp(tour)
                        // Above the navigation host, so every screen underneath
                        // stays unaware it is being toured — all any of them
                        // contribute is a tourAnchor on the control worth pointing at.
                        TourOverlay(controller = tour.controller)
                    }
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
                val suppressed = settingsRepository.prefs
                    .getBoolean(KEY_SUPPRESS_MEDIA_PROJECTION_PROMPT, false)
                if (!suppressed) {
                    val mpManager = getSystemService(
                        Context.MEDIA_PROJECTION_SERVICE
                    ) as android.media.projection.MediaProjectionManager
                    mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
                }
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
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val title = intent?.getStringExtra(NotificationDispatcher.EXTRA_NOTIFICATION_TITLE)
        val body = intent?.getStringExtra(NotificationDispatcher.EXTRA_NOTIFICATION_BODY)
        if (!title.isNullOrBlank() || !body.isNullOrBlank()) {
            val notifyId = intent?.getIntExtra(NotificationDispatcher.EXTRA_NOTIFICATION_ID, -1) ?: -1
            val url = intent?.getStringExtra(NotificationDispatcher.EXTRA_NOTIFICATION_URL)
            if (notifyId != -1) {
                NotificationManagerCompat.from(this).cancel(notifyId)
            }
            notificationPayload = NotificationPayload(
                id = notifyId,
                title = title.orEmpty(),
                body = body.orEmpty(),
                url = url
            )
        }
    }

    override fun onStart() {
        super.onStart()
        chatViewModel.setForeground(true)
    }

    override fun onResume() {
        super.onResume()
        // Server-driven notifications — fetch fresh if the cached value is
        // older than 6h. The dispatcher itself no-ops when the user has the
        // toggle off, so calling it on every resume is safe.
        lifecycleScope.launch {
            try {
                val settings = settingsRepository.load()
                val dispatcher = ServerMessages.create(
                    context = this@MainActivity,
                    settings = settings,
                    onUnauthorized = { onSamosaUnauthorized() }
                )
                ServerMessages.syncIfStale(
                    dispatcher = dispatcher,
                    enabled = settings.serverMessagesEnabled
                )
                // Persist the store's lastFetchedAt so Settings → Notifications
                // shows a correct "Last synced: …" line on next open, even if
                // the user never tapped Sync now.
                val fresh = dispatcher.lastFetchedAt()
                if (fresh > 0L && fresh != settings.serverMessagesLastFetchedAt) {
                    settingsRepository.save(settings.copy(serverMessagesLastFetchedAt = fresh))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Server messages are best-effort; never surface errors.
            }
        }
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
    private fun GotchaApp(tour: TourHost) {
        val state by chatViewModel.uiState.collectAsState()
        val sessions by chatViewModel.sessions.collectAsState()
        val liveTokenBySession by chatViewModel.liveTokenBySession.collectAsState()

        val initial = remember { settingsRepository.load() }

        // A fresh install has the tour to run, and the tour walks the user to the
        // API key itself — so it, not this, decides where they start. Only an
        // install that has already seen the tour and is still unconfigured gets
        // dropped straight on the page holding the key and model.
        val unconfigured = remember { !initial.isConfigured && !tour.willRun }
        var currentRoute by remember {
            mutableStateOf(if (unconfigured) Route.SETTINGS else Route.HOME)
        }
        var settingsPage by rememberSaveable {
            mutableStateOf(if (unconfigured) SettingsPage.AI_CONFIG else null)
        }
        var assistiveBallOn by remember { mutableStateOf(initial.assistiveBallEnabled) }
        var showFeedbackSheet by remember { mutableStateOf(false) }
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        val goToPlace: (TourPlace) -> Unit = { place ->
            val (route, page) = routeForTourPlace(place)
            currentRoute = route
            settingsPage = page
            scope.launch {
                if (place == TourPlace.CHAT_DRAWER) drawerState.open() else drawerState.close()
            }
        }
        val startTour = {
            goToPlace(TourPlace.CHAT)
            tour.controller.start()
        }

        TourNavigation(
            host = tour,
            place = tourPlaceOf(currentRoute, settingsPage, drawerState.isOpen),
            goToPlace = goToPlace
        )

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
            gesturesEnabled = (currentRoute == Route.HOME || drawerState.isOpen) &&
                legalAcceptedVersion == LEGAL_VERSION,
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
                    activeTokenCount = state.tokenCount,
                    liveTokenBySession = liveTokenBySession
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
                        onAppearanceChange = { updated ->
                            appearance = updated.appearance()
                            applyLaunchBackground()
                        },
                        onRefreshAudioModels = { s ->
                            withContext(Dispatchers.IO) {
                                val ttsBase = s.effectiveTtsBaseUrl
                                val sttBase = s.effectiveSttBaseUrl
                                if (ttsBase.isBlank() && sttBase.isBlank()) {
                                    return@withContext Pair(emptyList(), emptyList())
                                }
                                val fetch: (String, String) -> List<AudioModel> =
                                    { base, key ->
                                        AudioApi(
                                            baseUrl = base,
                                            apiKey = key,
                                            onUnauthorized = { this@MainActivity.runOnUiThread { onSamosaUnauthorized() } }
                                        ).listAudioModels()
                                    }
                                // Fetch the shared server once when both audio sides
                                // point at the same URL; fetch each side independently
                                // otherwise (STT-only configurations are valid).
                                val sharedAll = if (ttsBase.isNotBlank() && ttsBase == sttBase) {
                                    fetch(ttsBase, s.effectiveTtsApiKey)
                                } else {
                                    emptyList()
                                }
                                val ttsModels = when {
                                    ttsBase.isBlank() -> emptyList()
                                    ttsBase == sttBase -> sharedAll.filter { it.category == ModelCategory.TTS }
                                    else -> fetch(ttsBase, s.effectiveTtsApiKey)
                                        .filter { it.category == ModelCategory.TTS }
                                }
                                val sttModels = when {
                                    sttBase.isBlank() -> emptyList()
                                    sttBase == ttsBase -> sharedAll.filter { it.category == ModelCategory.STT }
                                    else -> fetch(sttBase, s.effectiveSttApiKey)
                                        .filter { it.category == ModelCategory.STT }
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
                        onFetchSamosaCredits = { samosaAuthManager.fetchCreditsRemaining() },
                        onSyncServerMessages = {
                            val s = settingsRepository.load()
                            if (!s.serverMessagesEnabled) return@SettingsScreen null
                            val dispatcher = ServerMessages.create(
                                context = this@MainActivity,
                                settings = s,
                                onUnauthorized = { onSamosaUnauthorized() }
                            )
                            dispatcher.fetchAndDeliver()
                            // Persist the fresh last-fetched-at so a future
                            // Settings reload reads the same value the screen
                            // is showing, and so a process kill + relaunch
                            // starts from this point.
                            val updated = s.copy(
                                serverMessagesLastFetchedAt = dispatcher.lastFetchedAt()
                            )
                            settingsRepository.save(updated)
                            dispatcher.lastFetchedAt()
                        },
                        onTestVoice = { language -> chatViewModel.testAndroidTts(language) },
                        packageName = packageName,
                        assistiveBallEnabled = assistiveBallOn,
                        onToggleAssistiveBall = { enabled ->
                            assistiveBallOn = setAssistiveBall(enabled)
                        },
                        onStartTour = { startTour() },
                        onSendFeedback = { showFeedbackSheet = true },
                        page = settingsPage,
                        onPageChange = { settingsPage = it }
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
                        onSend = { text, imageBase64, attachment, isVoiceInput ->
                            chatViewModel.sendMessage(text, imageBase64, attachment, isVoiceInput)
                        },
                        onStop = chatViewModel::stopAgent,
                        onConfirm = chatViewModel::confirmPendingActions,
                        onAnswer = chatViewModel::submitAnswer,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onOpenSettings = { currentRoute = Route.SETTINGS },
                        sessionTitle = sessions.firstOrNull { it.id == state.activeSessionId }?.title,
                        onPickFile = chatViewModel::pickContent,
                        pickResults = chatViewModel.pickResults,
                        onSwitchAgent = chatViewModel::switchAgent,
                        onSetAgent = chatViewModel::setAgent,
                        onSpeak = chatViewModel::speak,
                        onStopSpeaking = chatViewModel::stopSpeaking,
                        onStartListening = chatViewModel::startListening,
                        onStopRecording = { cb -> chatViewModel.stopRecording(cb) },
                        onExportChat = chatViewModel::exportChat,
                        onReturnToRunning = {
                            state.runningSessionId?.let { chatViewModel.openSession(it) }
                        },
                        onCreateShareCard = {
                            sharePoster.open(chatViewModel.activeSessionRunSummaries())
                        },
                        onEditMessage = { id, text, imageBase64, attachment ->
                            chatViewModel.editMessage(id, text, imageBase64, attachment)
                        },
                        onRevertMessage = { id -> chatViewModel.revertTo(id) }
                    )
                }
            }
        }

        // Composed last so this innermost enabled handler wins back dispatch:
        // back closes an open drawer before any other navigation. When the
        // legal-consent dialog is up, we swallow back entirely — the only way
        // out is "I agree" (or uninstalling the app), which keeps a fresh
        // install from booting straight into Settings via the back button.
        BackHandler(enabled = drawerState.isOpen) {
            scope.launch { drawerState.close() }
        }
        if (legalAcceptedVersion != LEGAL_VERSION) {
            BackHandler(enabled = true) { /* swallow */ }
        }

        notificationPayload?.let { payload ->
            NotificationDetailDialog(
                payload = payload,
                onDismiss = { notificationPayload = null }
            )
        }

        sharePoster.runs?.let { runs ->
            SharePosterSheet(
                runs = runs,
                loading = sharePoster.loading,
                preview = sharePoster.preview,
                error = sharePoster.error,
                onGenerate = sharePoster::generate,
                onShare = { sharePoster.preview?.let { sharePoster.share(it) } },
                onSave = { sharePoster.preview?.let { sharePoster.save(it) } },
                onRegenerate = sharePoster::generate,
                onDismiss = sharePoster::dismiss
            )
        }

        if (showFeedbackSheet) {
            FeedbackSheet(
                onDismiss = { showFeedbackSheet = false },
                onSubmit = { includeAppInfo, includeUsageStats, includeChatLog, includeUserId ->
                    showFeedbackSheet = false
                    lifecycleScope.launch {
                        val url = buildFeedbackPrefillUrl(
                            includeAppInfo,
                            includeUsageStats,
                            includeChatLog,
                            includeUserId
                        )
                        if (url.isBlank()) {
                            Toast.makeText(
                                this@MainActivity,
                                "Feedback form is not configured",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@launch
                        }
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: Exception) {
                            Toast.makeText(
                                this@MainActivity,
                                "No app can open the feedback form",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            )
        }

        // First-launch / re-acceptance gate. Non-dismissable while not accepted
        // — the only way out is tapping "I agree." Re-prompted whenever the
        // current LEGAL_VERSION doesn't match the stored acceptance, so a
        // meaningful change to any of the three documents forces re-acceptance
        // on every install.
        if (legalAcceptedVersion != LEGAL_VERSION) {
            LegalConsentDialog(
                onAgree = {
                    val current = settingsRepository.load()
                    settingsRepository.save(
                        current.copy(legalAcceptedVersion = LEGAL_VERSION)
                    )
                    legalAcceptedVersion = LEGAL_VERSION
                }
            )
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

    /**
     * Gathers the consent-selected feedback pre-fill and builds the form URL.
     * Called on the main dispatcher; the stats/excerpt/user-id work is suspend
     * and offloaded by the underlying helpers. Never throws.
     */
    private suspend fun buildFeedbackPrefillUrl(
        includeAppInfo: Boolean,
        includeUsageStats: Boolean,
        includeChatLog: Boolean,
        includeUserId: Boolean
    ): String {
        val settings = settingsRepository.load()
        val metadata = if (includeAppInfo) {
            FeedbackChannel.deviceMetadata(this)
        } else {
            FeedbackPrefill()
        }
        val userId = if (includeUserId) {
            FeedbackChannel.resolveSamosaUserId(settings.samosaSessionToken, settings.samosaEmail)
                ?: settingsRepository.anonymousFeedbackId()
        } else {
            null
        }
        val stats = if (includeUsageStats) {
            computeFeedbackStats(this).toPrefillText()
        } else {
            null
        }
        val chatLog = if (includeChatLog) {
            FeedbackChannel.recentChatExcerpt(this)
        } else {
            null
        }
        return FeedbackChannel.buildFeedbackUrl(
            metadata.copy(userId = userId, usageStats = stats, chatLog = chatLog)
        )
    }

    companion object {
        /** Intent extra: when true, launch straight into the chat screen. */
        const val EXTRA_OPEN_CHAT = "com.gotcha.OPEN_CHAT"

        /** Intent extra: brought to front by the assistive ball (Operator-origin). */
        const val EXTRA_FROM_ASSISTIVE_BALL = "com.gotcha.FROM_ASSISTIVE_BALL"

        /** SharedPreferences key to track first-launch permission setup. */
        const val KEY_FIRST_LAUNCH_DONE = "first_launch_setup_done"

        /**
         * SharedPreferences key: when true, skip the on-demand MediaProjection consent
         * prompt so instrumentation never faces the system dialog (test-only).
         */
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
                add(android.Manifest.permission.POST_NOTIFICATIONS)
                add(android.Manifest.permission.READ_MEDIA_IMAGES)
            }
        }
        firstLaunchLauncher.launch(perms.toTypedArray())
    }
}
