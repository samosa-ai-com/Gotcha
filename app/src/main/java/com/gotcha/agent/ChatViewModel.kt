package com.gotcha.agent

import android.app.Application
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gotcha.audio.AudioModel
import com.gotcha.audio.AudioProvider
import com.gotcha.audio.CompletionFeedback
import com.gotcha.audio.SttEngine
import com.gotcha.audio.TtsEngine
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.ChatMarkdown
import com.gotcha.data.ChatSession
import com.gotcha.data.LlmProvider
import com.gotcha.data.RunSummary
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.data.documentPromptText
import com.gotcha.i18n.Language
import com.gotcha.i18n.SpokenPhrases
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.DocumentPart
import com.gotcha.llm.LLMClient
import com.gotcha.llm.attachmentsUserMessage
import com.gotcha.marketing.PosterRenderer
import com.gotcha.marketing.PosterStatsBuilder
import com.gotcha.marketing.ShareCardClient
import com.gotcha.notifications.ChatCompletionNotifier
import com.gotcha.notifications.LocalNotificationStore
import com.gotcha.notifications.NotificationCategory
import com.gotcha.notifications.NotificationTarget
import com.gotcha.notifications.RunOutcome
import com.gotcha.tools.AgentMode
import com.gotcha.tools.DocumentError
import com.gotcha.tools.DocumentParser
import com.gotcha.tools.GotchaSettingsUpdate
import com.gotcha.tools.ScreenPerception
import com.gotcha.tools.ToolResult
import com.gotcha.tools.mergeProfileUpdate
import com.gotcha.ui.ConfirmationOverlay
import com.gotcha.ui.Persona
import com.gotcha.util.HumanReadableError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * A document the user picked from the system picker. The extracted [text] is what
 * gets sent to the model; it is also kept on the [com.gotcha.agent.UiMessage] so
 * re-editing a sent message can rebuild the request without re-reading the file.
 */
@kotlinx.serialization.Serializable
data class Attachment(
    val name: String,
    val mimeType: String,
    val size: Long,
    val text: String,
    val pageCount: Int? = null,
    val truncated: Boolean = false
)

/**
 * One file queued in the composer, or carried by a sent user message. Images are
 * already downscaled to JPEG base64; documents carry their extracted text. The
 * [id] is stable for the life of the attachment, so one can be removed from the
 * composer without disturbing the order of the rest.
 */
@kotlinx.serialization.Serializable
sealed class ComposerAttachment {
    abstract val id: String
    abstract val name: String

    @kotlinx.serialization.Serializable
    @kotlinx.serialization.SerialName("image")
    data class Image(
        override val id: String,
        override val name: String,
        val base64: String
    ) : ComposerAttachment()

    @kotlinx.serialization.Serializable
    @kotlinx.serialization.SerialName("document")
    data class Document(
        override val id: String,
        val attachment: Attachment
    ) : ComposerAttachment() {
        override val name: String get() = attachment.name
    }

    companion object {
        /**
         * Files per message. Images are downscaled to ~1024 px JPEG, so ten stay
         * far below every provider's request-size cap; the tighter per-request
         * image caps some providers apply (e.g. Groq's 3) surface as a readable
         * error from [HumanReadableError] instead of being guessed here.
         */
        const val MAX_PER_MESSAGE = 10

        /**
         * Extracted document text across one message. Each document is already
         * capped at [DocumentParser.MAX_EXTRACTED_CHARS]; this keeps several of
         * them from crowding the model's context window.
         */
        const val MAX_TOTAL_DOCUMENT_CHARS = 2 * DocumentParser.MAX_EXTRACTED_CHARS
    }
}

/**
 * Transcript labels for a prompt that was only attachments. The composer treats
 * these as empty when a message is edited, so they never become the new prompt.
 */
internal val ATTACHMENT_PLACEHOLDERS = setOf("(image attached)", "(document attached)", "(files attached)")

/**
 * The "you can leave Gotcha" hint shown while a run works. It only promises the
 * signal the user will actually get: [notify] is true only when a task-finished
 * notification is switched on and Android allows it (issue #97); otherwise the
 * opt-in buzz and chime are all there is.
 */
internal fun backgroundHintText(vibrate: Boolean, chime: Boolean, notify: Boolean = false): String {
    val base = "Gotcha is working in the background. You can use another app while it works"
    val signal = when {
        notify -> "Gotcha will notify you when the task is finished"
        vibrate && chime -> "your phone will buzz and chime when it's done"
        vibrate -> "your phone will buzz when it's done"
        chime -> "your phone will chime when it's done"
        else -> null
    }
    return if (signal == null) "$base." else "$base — $signal."
}

@kotlinx.serialization.Serializable
data class UiMessage(
    val id: Long,
    val kind: MessageKind,
    val text: String,
    val imageBase64: String? = null,
    val subAgentSteps: List<String> = emptyList(),
    val subAgentCollapsed: Boolean = true,
    val reasoningContent: String? = null,
    /** Files the user sent with this message, in the order they were picked. */
    val attachments: List<ComposerAttachment> = emptyList()
)

data class SubAgentStepUi(
    val action: String,
    val status: String,
    val detail: String = ""
)

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val isBusy: Boolean = false,
    val activity: String? = null,
    val subAgentRunning: String? = null,
    val subAgentCurrentAction: String? = null,
    val pendingConfirmation: PendingConfirmation? = null,
    val pendingQuestion: PendingQuestion? = null,
    /**
     * Runtime permission a tool is waiting on, asked for at the moment it is
     * needed (issue #79). Held in the state rather than fired as a one-shot
     * event so the dialog comes back with the activity — a rotation while it is
     * open must not leave the agent blocked on an answer nobody can give.
     */
    val pendingPermission: String? = null,
    val isConfigured: Boolean = false,
    val activeSessionId: String? = null,
    val activeAgent: AgentMode = AgentMode.MONITOR,
    /**
     * Id of the persona the open chat was started with, or null for a plain one.
     * Chosen on the home screen before the first message and fixed from there:
     * the picker is only shown while the chat is empty.
     */
    val activePersonaId: String? = null,
    /**
     * True while the open chat is one of the samples seeded on first run, so the
     * transcript can say so above the first bubble. Nothing else depends on it:
     * a sample is continued, renamed and deleted like any other chat.
     */
    val viewingSample: Boolean = false,
    /** Id of the session with an in-progress run, or null when nothing is running. */
    val runningSessionId: String? = null,
    /** Title of the running session, for the "return to running chat" banner. */
    val runningSessionTitle: String? = null,
    /**
     * Informational hint that the user may leave Gotcha while the run works
     * (issue #96), or null when nothing is running. Lives only as long as the
     * run, never in the transcript, so returning to the app can't repeat it.
     */
    val backgroundHint: String? = null,
    /**
     * True while the one-time "allow notifications?" ask is on screen. Asked the
     * first time a request is sent without the permission, so the user learns a
     * task can notify them; the run does not wait on the answer.
     */
    val askNotificationPermission: Boolean = false,
    val contextUsagePercent: Float = 0f,
    val tokenCount: Int = 0,
    val maxContextTokens: Int = 0,
    val isListening: Boolean = false,
    val isRecording: Boolean = false,
    /** True from the moment recording stops until the transcript (and cleanup) is ready. */
    val isTranscribing: Boolean = false,
    val isSpeaking: Boolean = false,
    val ttsModels: List<AudioModel> = emptyList(),
    val sttModels: List<AudioModel> = emptyList(),
    /**
     * Files queued in the composer until the user taps Send. Held here rather
     * than in the composer's saved state: several base64 images would overflow
     * the saved-instance Bundle.
     */
    val pendingAttachments: List<ComposerAttachment> = emptyList(),
    /**
     * Text to put in the composer once, e.g. a tapped daily tip's prompt (issue
     * #101). The composer owns its text, so this is a hand-off: the screen copies
     * it in and calls [ChatViewModel.consumeComposerDraft]. Never sent by itself.
     */
    val composerDraft: String? = null
)

// In-app chat host: session/UI state, dialogs, and TTS/STT wiring. The agent
// loop itself lives in AgentEngine and reports back through AgentEvents.
@Suppress("TooManyFunctions", "LargeClass")
class ChatViewModel(application: Application) : AndroidViewModel(application), AgentEvents {

    private val settingsRepository = SettingsRepository(application)
    private val historyRepository = ChatHistoryRepository(application)
    private val confirmationOverlay = ConfirmationOverlay(application)
    private val completionNotifier = ChatCompletionNotifier(application)
    private val localNotificationStore = LocalNotificationStore(application)

    private var settings: Settings = Settings()
    private var client: LLMClient? = null

    /** True when the most recent user message was sent via voice (STT). */
    @Volatile
    private var lastInputWasVoice = false

    /** True when the active LLM run was initiated by voice dictation. */
    @Volatile
    private var currentRunIsVoice = false

    private val ttsEngine: TtsEngine = TtsEngine(
        getApplication(),
        settings.effectiveTtsBaseUrl,
        settings.effectiveTtsApiKey,
        onUnauthorized = { viewModelScope.launch { onSamosaUnauthorized() } }
    )
    private val sttEngine: SttEngine = SttEngine(
        getApplication(),
        settings.effectiveSttBaseUrl,
        settings.effectiveSttApiKey,
        onUnauthorized = { viewModelScope.launch { onSamosaUnauthorized() } }
    )

    /** Set by the Activity in onStart/onStop; drives whether confirmations use the overlay. */
    @Volatile
    private var appInForeground = true

    private val agentEngine = AgentEngine(
        appContext = application,
        events = this,
        historyRepository = historyRepository,
        settingsProvider = { settings },
        clientProvider = { client },
        onUpdateUserProfile = { update ->
            // Reload so a manual Personal Info edit isn't clobbered by a stale snapshot,
            // then persist and refresh the cached settings the engine reads next turn.
            val merged = mergeProfileUpdate(settingsRepository.load(), update)
                ?: return@AgentEngine ToolResult.ok("No material change — profile left as is.")
            settingsRepository.save(merged.updated)
            settings = merged.updated
            // Surface the change to the user: the profile is silently re-injected into every
            // future prompt, so a prompt-injected update_user_profile call must not go
            // unnoticed. A TOOL bubble in the transcript gives the user a chance to catch
            // and revert a poisoned update. Dispatched to Main because this handler runs
            // inside the tool executor's IO context while appendEngineUi touches UI state.
            withContext(Dispatchers.Main) {
                appendEngineUi(
                    MessageKind.TOOL,
                    "Assistant updated your profile: " +
                        merged.changedFields.joinToString(", ") + "."
                )
            }
            ToolResult.ok(
                "Updated " + merged.changedFields.joinToString(", ") + ". " +
                    "The new value will be used from the next message."
            )
        },
        onUpdateGotchaSettings = { plan ->
            // Only reached after the user approved this exact change. Apply it onto a
            // fresh load so a concurrent write elsewhere (the Settings screen, the
            // assistive ball) is not clobbered by the snapshot the prompt was built from.
            settingsRepository.save(plan.applyTo(settingsRepository.load()))
            withContext(Dispatchers.Main) {
                // Rebuilds the cached settings the engine reads next round, and the
                // speech engines; the skin and services follow settingsChangeNotifier.
                refreshSettings()
                appendEngineUi(
                    MessageKind.TOOL,
                    "Assistant changed settings: " + plan.lines().joinToString("; ") + "."
                )
            }
            ToolResult.ok(GotchaSettingsUpdate.appliedMessage(plan))
        },
        // Persist the ENGINE session's own data, never the viewed session's —
        // the user may be browsing another chat while this run continues.
        displayMessagesProvider = { engineTranscript },
        agentModeProvider = { engineAgent }
    )

    private var nextId = 0L
    private var confirmationGate: CompletableDeferred<Boolean>? = null
    private var questionGate: CompletableDeferred<String>? = null
    private var permissionGate: CompletableDeferred<Boolean>? = null
    private var agentJob: Job? = null

    /**
     * Live on-screen transcript of the session currently bound to [agentEngine]
     * (the one that runs). Kept separate from [_uiState].messages so the user
     * can browse to another chat while a run continues in the background without
     * the engine's output overwriting — or being overwritten by — the viewed chat.
     */
    private var engineTranscript: List<UiMessage> = emptyList()

    /** Monotonic UiMessage id for engine bubbles while browsing a different chat. */
    private var engineNextId: Long = 1_000_000_000L

    /** Agent mode of the session currently bound to [agentEngine]. */
    private var engineAgent: AgentMode = AgentMode.MONITOR

    /**
     * True when the run in flight has surfaced an error bubble (LLM failure,
     * user interruption, …). Decides whether an arriving reply gets the normal
     * alert or the error buzz, since the engine reports both outcomes through
     * the same `onAssistantReply` path.
     */
    @Volatile
    private var runHadError = false

    /** True when the session the user is viewing is the one bound to the engine. */
    private fun viewingEngineSession(): Boolean =
        _uiState.value.activeSessionId == agentEngine.sessionId

    /**
     * True when [ChatSession.title] is still the legacy truncated-first-message
     * fallback rather than an LLM-generated title, so it's eligible to be
     * (re)generated next time the session is saved.
     */
    private fun ChatSession.isFallbackTitle(): Boolean {
        val fallback = messages.firstOrNull { it.role == "user" }?.textContent?.take(30)
        return title.isBlank() || title == fallback
    }

    /**
     * Startup: the fresh session, the chat-directory migration and the sample
     * seeding. Anything that loads a saved chat on the user's behalf before the
     * UI is up (a notification tap) waits for it, or the migration could move
     * the chat out from under the load.
     */
    private val initJob: Job

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    /**
     * Live per-session token counts. Updated on every [onTokenCount] so the
     * drawer's per-row readout doesn't lag one round behind the running
     * session. The persisted [ChatSession.tokenCount] on disk catches up
     * through [saveCurrentSession], so this overlay is read-first, disk-second.
     */
    private val _liveTokenBySession = MutableStateFlow<Map<String, Int>>(emptyMap())
    val liveTokenBySession: StateFlow<Map<String, Int>> = _liveTokenBySession.asStateFlow()

    /**
     * Special-access markers ("special:*") the Activity should deep-link to.
     * Runtime permissions travel as [ChatUiState.pendingPermission] instead —
     * they need an answer, and this is a fire-and-forget signal.
     */
    private val _permissionRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val permissionRequests: SharedFlow<String> = _permissionRequests.asSharedFlow()

    /** Exported chat markdown content the Activity should share. */
    private val _exportContent = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val exportContent: SharedFlow<String> = _exportContent.asSharedFlow()

    init {
        ScreenPerception.appContext = application
        com.gotcha.agent.skills.SkillRegistry.init(application)
        refreshSettings()
        initJob = viewModelScope.launch {
            // Always start on a fresh session so the home screen greets with an
            // empty chat; past sessions remain one tap away in the drawer.
            agentEngine.sessionId = java.util.UUID.randomUUID().toString()
            agentEngine.tokenCount = 0
            agentEngine.restoreTitle(null)
            agentEngine.setupWorkingDir(create = false)
            _uiState.update { it.copy(activeSessionId = agentEngine.sessionId) }
            updateContextUsage()
            migrateChatDirsIfNeeded()
            com.gotcha.data.SampleChatSeeder.seedIfNeeded(historyRepository, settingsRepository.prefs)
            refreshSessions()
        }
    }

    // ---- AgentEvents (engine → UI) ----

    override fun onUi(
        kind: MessageKind,
        text: String,
        imageBase64: String?,
        subAgentSteps: List<String>,
        reasoningContent: String?
    ) {
        appendEngineUi(
            kind = kind,
            text = text,
            imageBase64 = imageBase64,
            subAgentSteps = subAgentSteps,
            reasoningContent = reasoningContent
        )
    }

    override fun onActivity(activity: String?) {
        if (viewingEngineSession()) {
            _uiState.update { it.copy(activity = activity) }
        }
    }

    override fun onTokenCount(totalTokens: Int) {
        val engineId = agentEngine.sessionId ?: return
        // Publish live to the drawer so the running session's row updates in
        // the same frame, without waiting for the disk save at end-of-round.
        _liveTokenBySession.update { it + (engineId to totalTokens) }
        if (viewingEngineSession()) updateContextUsage()
        // Best-effort disk write so a crash mid-run doesn't lose the count.
        viewModelScope.launch { agentEngine.saveCurrentSession() }
    }

    override fun onAssistantReply(text: String) {
        signalReplyArrived()
        val shouldRead = lastInputWasVoice || currentRunIsVoice ||
            (settings.autoReadReplies && settings.ttsProvider != AudioProvider.NONE)
        if (shouldRead && settings.ttsProvider != AudioProvider.NONE) {
            speak(text)
        }
        lastInputWasVoice = false
        currentRunIsVoice = false
    }

    override fun onSubAgentUpdate(running: String?, currentAction: String?) {
        if (viewingEngineSession()) {
            _uiState.update { it.copy(subAgentRunning = running, subAgentCurrentAction = currentAction) }
        }
    }

    override fun onPermissionRequest(marker: String) {
        _permissionRequests.tryEmit(marker)
    }

    /**
     * A tool needs a runtime permission right now. The Activity collecting
     * [permissionRequests] explains why and raises the system dialog, then
     * answers through [onPermissionResult].
     *
     * A runtime dialog can only be raised by a foreground Activity, so a
     * backgrounded run says "not granted" immediately rather than stalling the
     * agent behind a prompt nobody can see — the tool's own error message
     * already tells the model (and, on screen, the user) what is missing.
     */
    override suspend fun awaitPermissionGrant(permission: String): Boolean {
        if (!appInForeground) return false
        val gate = CompletableDeferred<Boolean>()
        permissionGate = gate
        _uiState.update { it.copy(activity = null, pendingPermission = permission) }

        val granted = withTimeoutOrNull(GATE_TIMEOUT_MS) { gate.await() } ?: false

        _uiState.update { it.copy(pendingPermission = null) }
        permissionGate = null
        return granted
    }

    /** The Activity's answer to [awaitPermissionGrant]: the system dialog's outcome. */
    fun onPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(pendingPermission = null) }
        permissionGate?.complete(granted)
        permissionGate = null
    }

    /** Compaction dropped the LLM history; clear the engine transcript to match. */
    override fun onHistoryReset() {
        engineTranscript = emptyList()
        if (viewingEngineSession()) {
            nextId = 0
            _uiState.update { it.copy(messages = emptyList()) }
        }
    }

    override suspend fun awaitQuestionAnswer(question: PendingQuestion): String {
        val gate = CompletableDeferred<String>()
        questionGate = gate
        _uiState.update { it.copy(activity = null, pendingQuestion = question) }

        val answer = withTimeoutOrNull(GATE_TIMEOUT_MS) { gate.await() } ?: ""

        _uiState.update { it.copy(pendingQuestion = null) }
        questionGate = null
        return answer
    }

    override suspend fun awaitConfirmation(toolNames: List<String>, description: String): Boolean {
        val gate = CompletableDeferred<Boolean>()
        confirmationGate = gate
        _uiState.update {
            it.copy(activity = null, pendingConfirmation = PendingConfirmation(toolNames, description))
        }

        val approved = withTimeoutOrNull(GATE_TIMEOUT_MS) { gate.await() } ?: false
        confirmationOverlay.dismiss()
        _uiState.update { it.copy(pendingConfirmation = null) }
        confirmationGate = null
        return approved
    }

    // ---- Settings / models ----

    // Never read from the engine here. The engine may be bound to a different
    // session than the one being viewed (background run); use the value
    // already shown on screen so refreshSettings() etc. cannot clobber the
    // viewed session's readout with another session's live count.
    private fun updateContextUsage() = applyContextUsage(_uiState.value.tokenCount)

    /** Sets the context readout for an explicit token count (viewed session). */
    private fun applyContextUsage(tokens: Int) {
        val limit = settings.maxContextTokens.toFloat()
        val percent = if (limit > 0) tokens.toFloat() / limit else 0f
        _uiState.update {
            it.copy(
                contextUsagePercent = percent.coerceIn(0f, 1f),
                tokenCount = tokens,
                maxContextTokens = settings.maxContextTokens
            )
        }
    }

    /** Re-reads settings; call after the settings screen saves. */
    fun refreshSettings() {
        settings = settingsRepository.load()
        client = if (settings.isConfigured) {
            LLMClient(
                apiKey = settings.effectiveApiKey,
                baseUrl = settings.effectiveBaseUrl,
                model = settings.model,
                context = getApplication(),
                apiTimeoutSeconds = settings.apiTimeoutSeconds,
                onUnauthorized = { onSamosaUnauthorized() }
            )
        } else {
            null
        }
        ttsEngine.configureApi(settings.effectiveTtsBaseUrl, settings.effectiveTtsApiKey)
        sttEngine.configureApi(settings.effectiveSttBaseUrl, settings.effectiveSttApiKey)
        _uiState.update { it.copy(isConfigured = settings.isConfigured) }
        updateContextUsage()
    }

    /**
     * On a 401 while using Samosa AI, the JWT is expired/blacklisted: drop it so
     * the app returns to the unauthenticated state and prompts sign-in again.
     */
    private fun onSamosaUnauthorized() {
        val usingSamosa = settings.provider == LlmProvider.SAMOSA_AI
        if (!usingSamosa) return
        settingsRepository.clearSamosaSession()
        settings = settingsRepository.load()
        client = null
        _uiState.update { it.copy(isConfigured = false) }
    }

    suspend fun refreshChatModels(): Result<List<String>> {
        val cfg = settings
        if (!cfg.isConfigured) return Result.failure(Exception("API not configured"))
        val client = LLMClient(
            apiKey = cfg.effectiveApiKey,
            baseUrl = cfg.effectiveBaseUrl,
            model = cfg.model,
            context = getApplication(),
            apiTimeoutSeconds = cfg.apiTimeoutSeconds
        )
        return client.listModels()
    }

    fun sendMessage(
        text: String,
        attachments: List<ComposerAttachment> = emptyList(),
        isVoiceInput: Boolean = false
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        // One agent runs at a time. Block sending while any run is in flight —
        // the user can browse other chats but must let the current run finish.
        if (_uiState.value.isBusy || _uiState.value.runningSessionId != null) return
        if (client == null) {
            appendUi(MessageKind.ERROR, "No API key configured. Open settings to add one.")
            return
        }
        val msg = buildUserMessage(trimmed, attachments)
        launchUserRun(msg, attachments, trimmed, isVoiceInput)
    }

    /**
     * Builds the LLM user message for the given prompt and attachments: every
     * document's text joins the prompt in the first text part, and every image
     * follows as its own image part.
     */
    private fun buildUserMessage(text: String, attachments: List<ComposerAttachment>): ChatMessage {
        if (attachments.isEmpty()) return ChatMessage(role = "user", content = JsonPrimitive(text))
        val documents = attachments.filterIsInstance<ComposerAttachment.Document>().map {
            DocumentPart(it.attachment.name, it.attachment.mimeType, it.attachment.text, it.attachment.pageCount)
        }
        val images = attachments.filterIsInstance<ComposerAttachment.Image>().map { it.base64 }
        return attachmentsUserMessage(text, documents, images, imageFormat = "jpeg")
    }

    /**
     * Appends [msg] to the viewed session's LLM history, shows the USER bubble,
     * and starts an agent run. Shared by [sendMessage] and [editMessage], so both
     * paths go through the same busy-marking, run, and NonCancellable cleanup.
     */
    private fun launchUserRun(
        msg: ChatMessage,
        attachments: List<ComposerAttachment>,
        userText: String,
        isVoiceInput: Boolean
    ) {
        currentRunIsVoice = isVoiceInput || lastInputWasVoice
        lastInputWasVoice = false
        val viewedId = _uiState.value.activeSessionId
        agentJob = viewModelScope.launch {
            // Ensure the engine is bound to the session being viewed. After a
            // previous run finished while the user browsed elsewhere, the engine
            // may still point at that older session — reload the viewed one.
            bindEngineToViewedSession(viewedId)

            agentEngine.history += msg
            appendEngineUi(
                MessageKind.USER,
                userDisplayText(userText, msg, attachments),
                attachments = attachments
            )

            val runningId = agentEngine.sessionId ?: return@launch
            executeRun(engineAgent, runningId)
        }
    }

    /**
     * The transcript label for a sent message: the prompt text, or a placeholder
     * when the message is attachment-only. Messages with documents show the
     * prompt rather than the extracted bodies; image-only messages show a
     * placeholder, never the whitespace text part sent to the model.
     */
    private fun userDisplayText(userText: String, msg: ChatMessage, attachments: List<ComposerAttachment>): String =
        when {
            attachments.isEmpty() -> msg.textContent
            attachments.none { it is ComposerAttachment.Document } -> userText.ifEmpty {
                if (attachments.size == 1) "(image attached)" else "(files attached)"
            }
            else -> userText.ifEmpty {
                if (attachments.size == 1) "(document attached)" else "(files attached)"
            }
        }

    /** Busy-marking + agent run + NonCancellable cleanup, from the old sendMessage body. */
    private suspend fun executeRun(agent: AgentMode, runningId: String) {
        val runningTitle = engineTranscript.firstOrNull { it.kind == MessageKind.USER }
            ?.text?.take(30) ?: "New Chat"
        _uiState.update {
            it.copy(
                isBusy = true,
                runningSessionId = runningId,
                runningSessionTitle = runningTitle,
                backgroundHint = currentBackgroundHint(),
                askNotificationPermission = it.askNotificationPermission || claimNotificationPermissionAsk()
            )
        }
        runHadError = false
        var stopped = false
        try {
            agentEngine.run(agent)
        } catch (_: CancellationException) {
            stopped = true
            appendEngineUi(MessageKind.ERROR, "Agent was interrupted by the user.")
            // The interrupt may have orphaned an assistant with tool_calls but no
            // matching tool results. Repair it in NonCancellable before the next
            // turn is built — otherwise the provider 400s every later request.
            withContext(NonCancellable) {
                agentEngine.sanitizeLastOrphanedAssistant()
            }
        } finally {
            withContext(NonCancellable) {
                currentRunIsVoice = false
                lastInputWasVoice = false
                // Belt-and-suspenders: an interrupt that slipped past the engine's
                // own sanitize still gets repaired here before persisting.
                agentEngine.sanitizeLastOrphanedAssistant()
                agentEngine.saveCurrentSession()
                notifyRunFinished(
                    sessionId = runningId,
                    outcome = when {
                        stopped -> RunOutcome.STOPPED
                        runHadError -> RunOutcome.FAILED
                        else -> RunOutcome.DONE
                    }
                )
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        runningSessionId = null,
                        runningSessionTitle = null,
                        backgroundHint = null,
                        activity = if (viewingEngineSession()) null else it.activity,
                        subAgentRunning = if (viewingEngineSession()) null else it.subAgentRunning,
                        subAgentCurrentAction = if (viewingEngineSession()) null else it.subAgentCurrentAction
                    )
                }
                agentJob = null
            }
        }
    }

    private fun currentBackgroundHint(): String = backgroundHintText(
        vibrate = settings.notifyVibrationEnabled,
        chime = settings.notifyChimeEnabled,
        notify = settings.chatCompletionNotificationsEnabled && completionNotifier.canPost()
    )

    /**
     * The run in [sessionId] just ended. Posts the task-finished notification
     * when the user is away from Gotcha; in the foreground the reply buzz is
     * enough. Called once per run, from [executeRun]'s cleanup, so a run never
     * produces two — the engine reports text mid-run too, which is why this is
     * not driven by [onAssistantReply].
     */
    private fun notifyRunFinished(sessionId: String, outcome: RunOutcome) {
        if (appInForeground || !settings.chatCompletionNotificationsEnabled) return
        if (!completionNotifier.canPost()) return
        val reply = engineTranscript.lastOrNull {
            it.kind == MessageKind.ASSISTANT || it.kind == MessageKind.ERROR
        }?.text
        // Issue #100: a chat kept out of notifications, or chats not to be named
        // at all, get a notification that says only that a task finished.
        val named = settings.notificationsMentionChats &&
            !localNotificationStore.isChatSensitive(sessionId, agentEngine.sessionPersonaId)
        val title = if (named) {
            ChatCompletionNotifier.notificationTitle(agentEngine.currentTitle(), outcome)
        } else {
            ChatCompletionNotifier.anonymousTitle(outcome)
        }
        val entryId = localNotificationStore.addEntry(
            category = NotificationCategory.TASK_FINISHED,
            title = title,
            body = ChatCompletionNotifier.defaultBody(outcome),
            target = NotificationTarget.Chat(sessionId)
        )
        completionNotifier.notify(
            sessionId = sessionId,
            chatTitle = agentEngine.currentTitle(),
            outcome = outcome,
            reply = reply,
            preview = settings.chatCompletionPreview,
            named = named,
            inboxEntryId = entryId
        )
    }

    /**
     * True, once per install, when the user should be asked for notification
     * permission: they want task-finished notifications, Android 13+ blocks
     * them, and they are here to see the ask. Claimed as it is shown, so
     * "Not now" is final — the Notifications settings page can still ask.
     */
    private fun claimNotificationPermissionAsk(): Boolean {
        if (!appInForeground || !settings.chatCompletionNotificationsEnabled) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (completionNotifier.canPost()) return false
        val prefs = settingsRepository.prefs
        if (prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, false)) return false
        prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, true).apply()
        return true
    }

    /**
     * The answer to [ChatUiState.askNotificationPermission]. A grant mid-run
     * upgrades the hint on screen to the notification promise it can now keep.
     */
    fun onNotificationPermissionResult(granted: Boolean) {
        _uiState.update {
            it.copy(
                askNotificationPermission = false,
                backgroundHint = if (granted && it.backgroundHint != null) currentBackgroundHint() else it.backgroundHint
            )
        }
    }

    /**
     * Opens [id] from a tapped task-finished notification. Waits for startup,
     * which would otherwise replace the chat with the fresh one it opens.
     */
    fun openSessionFromNotification(id: String) {
        viewModelScope.launch {
            initJob.join()
            openSession(id)
        }
    }

    /**
     * Opens a new chat for a tapped daily tip (issue #101): in the tip's mode —
     * a fresh chat has no earlier choice of mode to override — and with its
     * prompt in the composer for the user to edit or send. Waits for startup
     * like [openSessionFromNotification]. Without an API key the composer is
     * disabled, so the chat opens without the draft.
     */
    fun startChatFromTip(prompt: String, mode: AgentMode) {
        viewModelScope.launch {
            initJob.join()
            clearChat(mode)
            if (_uiState.value.isConfigured) {
                _uiState.update { it.copy(composerDraft = prompt) }
            }
        }
    }

    /**
     * Whether chat [sessionId] is kept out of notifications (issue #100): the
     * user's own choice, else true for a Doctor-persona chat.
     */
    fun isChatKeptOutOfNotifications(sessionId: String, personaId: String?): Boolean =
        localNotificationStore.isChatSensitive(sessionId, personaId)

    fun setChatKeptOutOfNotifications(sessionId: String, keptOut: Boolean) {
        localNotificationStore.setChatKeptOut(sessionId, keptOut)
        // A notification already in the tray may name the chat.
        if (keptOut) completionNotifier.cancel(sessionId)
    }

    /** The composer has taken [ChatUiState.composerDraft]. */
    fun consumeComposerDraft() {
        _uiState.update { it.copy(composerDraft = null) }
    }

    /**
     * Replaces the user message [targetId] with [newText] and [attachments]; a
     * null [attachments] keeps the ones the original message was sent with. The target's whole turn and
     * everything after it are dropped from both the LLM history and the on-screen
     * transcript, then the agent re-runs immediately so a fresh reply is
     * generated from the edited history.
     */
    fun editMessage(targetId: Long, newText: String, attachments: List<ComposerAttachment>? = null) {
        val trimmed = newText.trim()
        if (trimmed.isEmpty() && attachments.isNullOrEmpty()) return
        if (_uiState.value.isBusy || _uiState.value.runningSessionId != null) return
        if (client == null) {
            appendUi(MessageKind.ERROR, "No API key configured. Open settings to add one.")
            return
        }
        currentRunIsVoice = false
        lastInputWasVoice = false
        val viewedId = _uiState.value.activeSessionId
        // Cancel any in-flight edit/run before overwriting the reference, so a
        // rapid second tap can't leave two coroutines truncating the same history.
        agentJob?.cancel()
        agentJob = viewModelScope.launch {
            // Re-check the busy guard inside the coroutine: a second invocation can
            // slip past the synchronous check above (isBusy only becomes true once
            // executeRun runs), so refuse rather than interleave two truncations.
            if (_uiState.value.isBusy || _uiState.value.runningSessionId != null) return@launch
            bindEngineToViewedSession(viewedId)
            val transcript = engineTranscript
            val target = transcript.firstOrNull { it.id == targetId && it.kind == MessageKind.USER }
                ?: return@launch
            val k = transcript.takeWhile { it.id != targetId }.count { it.kind == MessageKind.USER }
            // History/transcript desync guard (e.g. after compaction the transcript
            // is reset), so a stale target id bails without partial truncation.
            if (k >= userTurnStarts(agentEngine.history).size) return@launch
            val kept = truncateHistoryAtTurn(agentEngine.history, k, dropTurn = true)
            agentEngine.history.clear()
            agentEngine.history.addAll(kept)
            engineTranscript = transcript.take(transcript.indexOf(target))
            _uiState.update {
                it.copy(
                    messages = engineTranscript,
                    activity = null,
                    subAgentRunning = null,
                    subAgentCurrentAction = null
                )
            }
            // Never promote undone work on the share card.
            agentEngine.restoreRunSummaries(emptyList())
            // A previously-sent document keeps its extracted text (the file grant is
            // long gone); a newly-picked one carries its own.
            val editAttachments = attachments ?: target.attachments
            val msg = buildUserMessage(trimmed, editAttachments)
            agentEngine.history += msg
            appendEngineUi(
                MessageKind.USER,
                userDisplayText(trimmed, msg, editAttachments),
                attachments = editAttachments
            )
            executeRun(engineAgent, agentEngine.sessionId ?: return@launch)
        }
    }

    /**
     * Truncates the conversation so the user message [targetId] becomes the last
     * message: its own replies and everything after are dropped from both the LLM
     * history and the on-screen transcript, letting the user continue from a
     * clean state. No LLM call, so it works even without a configured API key.
     */
    fun revertTo(targetId: Long) {
        if (_uiState.value.isBusy || _uiState.value.runningSessionId != null) return
        val viewedId = _uiState.value.activeSessionId
        // Cancel any in-flight edit/run first so a revert can't interleave with a
        // coroutine that is mid-truncation on the same engine history.
        agentJob?.cancel()
        viewModelScope.launch {
            // Re-check the busy guard inside the coroutine (mirrors editMessage):
            // the synchronous check above can be raced by a second dispatch before
            // isBusy is set, so refuse rather than truncate concurrently.
            if (_uiState.value.isBusy || _uiState.value.runningSessionId != null) return@launch
            bindEngineToViewedSession(viewedId)
            val transcript = engineTranscript
            val target = transcript.firstOrNull { it.id == targetId && it.kind == MessageKind.USER }
                ?: return@launch
            val k = transcript.takeWhile { it.id != targetId }.count { it.kind == MessageKind.USER }
            // History/transcript desync guard (e.g. after compaction the transcript
            // is reset), so a stale target id bails without partial truncation.
            if (k >= userTurnStarts(agentEngine.history).size) return@launch
            val kept = truncateHistoryAtTurn(agentEngine.history, k, dropTurn = false)
            agentEngine.history.clear()
            agentEngine.history.addAll(kept)
            engineTranscript = transcript.take(transcript.indexOf(target) + 1)
            _uiState.update {
                it.copy(
                    messages = engineTranscript,
                    activity = null,
                    subAgentRunning = null,
                    subAgentCurrentAction = null
                )
            }
            // Re-derive the context readout from the truncated history, matching
            // the engine's own estimate units so the meter doesn't lie after revert.
            val tokens = kept.sumOf { it.textContent.length / 4 } + AgentEngine.PROMPT_OVERHEAD_TOKENS
            agentEngine.tokenCount = tokens
            applyContextUsage(tokens)
            // Keep the drawer's live overlay in sync so the reverted session's
            // row doesn't keep showing the pre-truncation count.
            agentEngine.sessionId?.let { sid ->
                _liveTokenBySession.update { it + (sid to tokens) }
            }
            // Never promote undone work on the share card.
            agentEngine.restoreRunSummaries(emptyList())
            agentEngine.saveCurrentSession()
            refreshSessions()
        }
    }

    /**
     * Buzz/chime as a reply lands. The chat screen has no spoken "I'm done"
     * unless auto-read is on, so without this a reply that arrives while the
     * user is elsewhere goes unnoticed.
     */
    private fun signalReplyArrived() {
        if (runHadError) {
            CompletionFeedback.error(getApplication())
        } else {
            CompletionFeedback.replyArrived(
                context = getApplication(),
                vibrate = settings.notifyVibrationEnabled,
                chime = settings.notifyChimeEnabled
            )
        }
    }

    /**
     * Point [agentEngine] at [viewedId] (the session being viewed) so a run
     * operates on it. Only called from [sendMessage], which is gated on nothing
     * else running, so re-pointing the engine here is safe. Reloads the session's
     * LLM history from disk when the engine had drifted to another session.
     *
     * `internal` rather than private so the handoff can be tested directly: it is
     * where a persona picked while another chat was running finally reaches the
     * engine, and [sendMessage] itself can't be driven from a JVM test.
     */
    internal suspend fun bindEngineToViewedSession(viewedId: String?) {
        if (viewedId != null && agentEngine.sessionId != viewedId) {
            val saved = historyRepository.loadSession(viewedId)
            agentEngine.history.clear()
            agentEngine.sessionId = viewedId
            if (saved != null) {
                agentEngine.history.addAll(saved.messages)
                agentEngine.tokenCount = saved.tokenCount
                agentEngine.restoreTitle(if (saved.isFallbackTitle()) null else saved.title)
                agentEngine.restoreRunSummaries(saved.runSummaries)
                agentEngine.sessionIsSample = saved.isSample
            } else {
                agentEngine.tokenCount = 0
                agentEngine.restoreTitle(null)
                agentEngine.restoreRunSummaries(emptyList())
                agentEngine.sessionIsSample = false
            }
            agentEngine.setupWorkingDir()
        }
        engineTranscript = _uiState.value.messages
        engineAgent = _uiState.value.activeAgent
        agentEngine.sessionPersonaId = _uiState.value.activePersonaId
    }

    /** Load an image from a content:// URI, downscale, and return base64. */
    fun loadImageBase64(uri: Uri, maxDimension: Int = 1024): String? {
        return try {
            val resolver = getApplication<Application>().contentResolver
            val inputStream = resolver.openInputStream(uri) ?: return null
            val bytes = inputStream.use { it.readBytes() }

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

            com.gotcha.tools.ScreenPerception.compressBitmap(
                bitmap = bitmap,
                maxDimension = maxDimension,
                quality = 85,
                format = Bitmap.CompressFormat.JPEG,
                recycleInput = true
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Entry point for the composer's "+" button. Each picked file is read and
     * parsed on [Dispatchers.IO] in the order it was picked, then appended to
     * [ChatUiState.pendingAttachments] — nothing already queued is replaced, and
     * nothing is sent until the user taps Send. Images reuse the image pipeline;
     * anything else goes through [loadDocument]. Files that fail to load, or that
     * would go past [ComposerAttachment.MAX_PER_MESSAGE] or
     * [ComposerAttachment.MAX_TOTAL_DOCUMENT_CHARS], are skipped with an ERROR
     * bubble saying why; the rest are still added.
     */
    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val room = ComposerAttachment.MAX_PER_MESSAGE - _uiState.value.pendingAttachments.size
            val toLoad = uris.take(room.coerceAtLeast(0))
            val loaded = withContext(Dispatchers.IO) { toLoad.map { loadAttachment(it) } }
            // Back on the main dispatcher. Re-check against the queue as it is now,
            // since another pick may have landed while these were loading.
            val errors = loaded.mapNotNull { it.exceptionOrNull()?.message }.toMutableList()
            var overCount = 0
            var overText = 0
            _uiState.update { state ->
                // update may retry this block, so the counts start over each time.
                overCount = uris.size - toLoad.size
                overText = 0
                val queue = state.pendingAttachments.toMutableList()
                var docChars = queue.documentChars()
                for (attachment in loaded.mapNotNull { it.getOrNull() }) {
                    val chars = (attachment as? ComposerAttachment.Document)?.attachment?.text?.length ?: 0
                    when {
                        queue.size >= ComposerAttachment.MAX_PER_MESSAGE -> overCount++
                        docChars + chars > ComposerAttachment.MAX_TOTAL_DOCUMENT_CHARS -> overText++
                        else -> {
                            queue += attachment
                            docChars += chars
                        }
                    }
                }
                state.copy(pendingAttachments = queue)
            }
            if (overCount > 0) {
                errors += "You can attach up to ${ComposerAttachment.MAX_PER_MESSAGE} files per message — " +
                    "skipped ${plural(overCount, "file")}."
            }
            if (overText > 0) {
                errors += "The attached documents are too long to send together — " +
                    "skipped ${plural(overText, "document")}. Send them in separate messages."
            }
            errors.forEach { appendUi(MessageKind.ERROR, it) }
        }
    }

    /** Removes one queued attachment from the composer. */
    fun removeAttachment(id: String) {
        _uiState.update { state -> state.copy(pendingAttachments = state.pendingAttachments.filterNot { it.id == id }) }
    }

    /** Replaces the composer's queue, e.g. with a message's attachments when it is edited. */
    fun setAttachments(attachments: List<ComposerAttachment>) {
        _uiState.update { it.copy(pendingAttachments = attachments) }
    }

    fun clearAttachments() = setAttachments(emptyList())

    private fun List<ComposerAttachment>.documentChars(): Int =
        sumOf { (it as? ComposerAttachment.Document)?.attachment?.text?.length ?: 0 }

    private fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

    /**
     * Reads one picked file into an attachment. A failure carries the message to
     * show the user, so one unreadable file never costs the rest of the pick.
     */
    private fun loadAttachment(uri: Uri): Result<ComposerAttachment> = try {
        val resolver = getApplication<Application>().contentResolver
        val id = java.util.UUID.randomUUID().toString()
        val attachment = if (resolver.getType(uri)?.startsWith("image/") == true) {
            loadImageBase64(uri)?.let { ComposerAttachment.Image(id, queryDisplayName(resolver, uri, "image"), it) }
        } else {
            loadDocument(uri)?.let { ComposerAttachment.Document(id, it) }
        }
        // Both loaders return null when the stream cannot be opened; treat that
        // like any other failed pick so the user gets a visible error instead of
        // a silent no-op.
        if (attachment != null) Result.success(attachment) else Result.failure(Exception("Could not read that file."))
    } catch (e: CancellationException) {
        throw e
    } catch (e: DocumentError) {
        Result.failure(Exception(e.message ?: "Could not read that file."))
    } catch (e: Exception) {
        Result.failure(Exception("Could not read that file: ${HumanReadableError.format(e)}"))
    }

    /**
     * Load a document from a content:// URI: read the bytes and extract the text
     * via [DocumentParser]. The extracted text is carried on the returned
     * [Attachment], so the transient picker read grant is never needed again and
     * nothing is copied into the app cache. Returns null only when the stream
     * cannot be opened; unreadable/unsupported content throws [DocumentError],
     * which [addAttachments] surfaces as an ERROR bubble on the main thread.
     */
    private fun loadDocument(uri: Uri): Attachment? {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val name = queryDisplayName(resolver, uri)
        val extracted = DocumentParser.extract(bytes, name, resolver.getType(uri))
        return Attachment(
            name = name,
            mimeType = extracted.mimeType,
            size = bytes.size.toLong(),
            text = extracted.text,
            pageCount = extracted.pageCount,
            truncated = extracted.truncated
        )
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri, fallback: String = "document"): String {
        return try {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            }
        } catch (_: Exception) {
            null
        }?.takeIf { it.isNotBlank() } ?: fallback
    }

    fun stopAgent() {
        agentJob?.cancel()
    }

    fun confirmPendingActions(approved: Boolean) {
        _uiState.update { it.copy(pendingConfirmation = null) }
        confirmationGate?.complete(approved)
        confirmationGate = null
    }

    fun submitAnswer(answer: String?) {
        _uiState.update { it.copy(pendingQuestion = null) }
        questionGate?.complete(answer ?: "")
        questionGate = null
    }

    /** Called from the Activity's onStart/onStop so confirmations know if they'd be hidden. */
    fun setForeground(foreground: Boolean) {
        appInForeground = foreground
        // Back in the app on a chat whose task finished: that chat's
        // notification has done its job.
        if (foreground) _uiState.value.activeSessionId?.let(completionNotifier::cancel)
    }

    /** Speak the given text aloud using the configured TTS provider. */
    fun speak(text: String) {
        if (settings.ttsProvider == AudioProvider.NONE) return
        viewModelScope.launch {
            ttsEngine.stop()
            _uiState.update { it.copy(isSpeaking = true) }
            try {
                val language = settings.effectiveVoiceLanguage
                val defaultVoice = _uiState.value.ttsModels
                    .firstOrNull { it.id == settings.ttsApiModel }
                    ?.defaultVoiceFor(language) ?: "af_heart"
                val voice = settings.ttsVoice.ifBlank { defaultVoice }
                ttsEngine.speak(
                    text = text,
                    provider = settings.ttsProvider,
                    apiModel = settings.ttsApiModel,
                    voice = voice,
                    language = language
                )
            } finally {
                _uiState.update { it.copy(isSpeaking = false) }
            }
        }
    }

    /** Stop any ongoing TTS speech output. */
    fun stopSpeaking() {
        ttsEngine.stop()
        _uiState.update { it.copy(isSpeaking = false) }
    }

    /**
     * Speak [language]'s call-started phrase through the shared Android TTS
     * engine (regardless of [AudioProvider] setting) so Settings can verify the
     * installed voice data without spinning up a second TtsEngine instance.
     *
     * Returns true when the requested language was actually used, false when
     * the engine had to fall back to English because Android is missing the
     * voice data. Returns null when TTS isn't configured at all.
     */
    suspend fun testAndroidTts(language: Language): Boolean? {
        if (settings.ttsProvider == AudioProvider.NONE) return null
        ttsEngine.stop()
        ttsEngine.speak(
            text = SpokenPhrases.callStarted(language),
            provider = AudioProvider.ANDROID,
            language = language
        )
        return ttsEngine.lastLanguageUnavailable != language
    }

    /** Start listening for speech input using the configured STT provider. */
    fun startListening() {
        stopSpeaking()
        if (_uiState.value.isListening || _uiState.value.isRecording || _uiState.value.isTranscribing) return
        when {
            settings.sttProvider == AudioProvider.ANDROID -> {
                val perm = android.Manifest.permission.RECORD_AUDIO
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    getApplication(), perm
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    appendUi(
                        MessageKind.ERROR,
                        "Microphone permission not granted. Enable it in Settings → Permissions."
                    )
                    return
                }
                val started = sttEngine.startAndroidListening(settings.effectiveVoiceLanguage)
                if (started) {
                    _uiState.update { it.copy(isListening = true) }
                } else {
                    appendUi(MessageKind.ERROR, "Failed to start speech recognition.")
                }
            }
            settings.sttProvider.isApiBased() -> {
                if (settings.effectiveSttBaseUrl.isBlank()) {
                    appendUi(
                        MessageKind.ERROR,
                        if (settings.sttProvider == AudioProvider.SAMOSA_AI) {
                            "Samosa STT is not configured. Sign in from Settings → Speech."
                        } else {
                            "No STT API URL configured in settings."
                        }
                    )
                    return
                }
                if (settings.sttApiModel.isBlank()) {
                    appendUi(MessageKind.ERROR, "No STT model selected. Refresh audio models in settings.")
                    return
                }
                val started = sttEngine.startRecording()
                if (started) {
                    _uiState.update { it.copy(isRecording = true) }
                } else {
                    appendUi(MessageKind.ERROR, "Failed to start recording.")
                }
            }
            else -> {
                appendUi(MessageKind.ERROR, "No STT provider configured. Enable one in settings.")
            }
        }
    }

    /** Stop an ongoing recording (Android or API) and pass the transcript to the callback. */
    fun stopRecording(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val provider = settings.sttProvider
            _uiState.update {
                it.copy(isRecording = false, isListening = false, isTranscribing = true)
            }
            try {
                var transcript = ""
                when {
                    provider.isApiBased() -> {
                        val audioFile = sttEngine.stopRecording()
                        if (audioFile == null) {
                            appendUi(MessageKind.ERROR, "Failed to record audio.")
                            return@launch
                        }
                        val sttLanguage = settings.sttLanguage.ifBlank {
                            settings.effectiveVoiceLanguage.iso639
                        }
                        transcript = sttEngine.transcribeApi(
                            audioFile, settings.sttApiModel, sttLanguage
                        )
                            .onFailure { e ->
                                appendUi(MessageKind.ERROR, "Transcription failed: ${HumanReadableError.format(e)}")
                            }
                            .getOrDefault("")
                    }
                    provider == AudioProvider.ANDROID -> {
                        transcript = sttEngine.stopAndroidListening()
                    }
                }

                if (transcript.isNotBlank()) {
                    lastInputWasVoice = true
                    // API STT (Whisper-class) output is already punctuated and cased —
                    // cleanText is redundant there and would cost an extra LLM round-trip.
                    val cleaned = if (provider == AudioProvider.ANDROID) {
                        val navModel = settings.navigatorModel.ifEmpty { settings.model }
                        client?.cleanText(transcript, navModel, settings.effectiveVoiceLanguage)
                            ?: transcript
                    } else {
                        transcript
                    }
                    onResult(cleaned)
                }
            } finally {
                _uiState.update { it.copy(isTranscribing = false) }
            }
        }
    }

    /** Refresh available TTS/STT models from the API. */
    fun refreshAudioModels() {
        viewModelScope.launch {
            val ttsModels = ttsEngine.refreshApiModels()
            val sttModels = sttEngine.refreshApiModels()
            _uiState.update { it.copy(ttsModels = ttsModels, sttModels = sttModels) }
        }
    }

    /**
     * The app was brought to the front by the assistive ball. One agent runs at
     * a time, so:
     *  - If a run is active, return to that running chat (never start a new one).
     *  - Else if the current chat is empty (home), default it to Operator, since
     *    ball-initiated chats are Operator by design.
     *  - Else leave the populated chat being viewed as-is.
     */
    fun onOpenedFromAssistiveBall() {
        val running = _uiState.value.runningSessionId
        when {
            running != null -> openSession(running)
            _uiState.value.messages.isEmpty() -> setAgent(AgentMode.OPERATOR)
            else -> { /* keep the current chat */ }
        }
    }

    /** Switch between Monitor (read-only) and Operator (full) agent mode mid-conversation. */
    fun switchAgent() {
        val current = _uiState.value.activeAgent
        val next = when (current) {
            AgentMode.MONITOR -> AgentMode.OPERATOR
            AgentMode.OPERATOR -> AgentMode.MONITOR
        }
        _uiState.update { it.copy(activeAgent = next) }
        // Instruct the LLM ("you" = the agent) and show the user a separate,
        // user-facing notice — the history wording reads wrong in the chat UI.
        val llmMsg = when (next) {
            AgentMode.MONITOR ->
                "[System: Switched to Monitor (read-only). You may now ONLY inspect and observe. " +
                    "No changes to the device are permitted.]"
            AgentMode.OPERATOR -> "[System: Switched to Operator. You are now permitted to make changes to the device.]"
        }
        val uiMsg = when (next) {
            AgentMode.MONITOR ->
                "Switched to Monitor mode — the agent can only observe; it cannot make any changes to your device."
            AgentMode.OPERATOR ->
                "Switched to Operator mode — the agent can now make changes to your device."
        }
        // Only inject into the LLM history when the viewed chat is the engine's;
        // otherwise the mode is applied at the next send (run(engineAgent)).
        if (viewingEngineSession()) {
            agentEngine.history += ChatMessage(role = "system", content = JsonPrimitive(llmMsg))
            engineAgent = next
        }
        appendUi(MessageKind.ASSISTANT, uiMsg)
    }

    /**
     * Set the agent mode directly, without injecting a mid-conversation system
     * message. Used by the home-screen selector before the first message is sent.
     */
    fun setAgent(mode: AgentMode) {
        if (_uiState.value.activeAgent == mode) return
        _uiState.update { it.copy(activeAgent = mode) }
    }

    /**
     * Put the chat about to start into [persona]'s role, or back to a plain chat
     * when null. Creation-time only: the picker is only drawn on an empty chat,
     * and a persona arriving mid-conversation would rewrite the system message
     * the provider has already cached for this session — so a chat that has
     * messages ignores this.
     *
     * The persona's own default mode is applied through [setAgent] (silently,
     * like the selector itself), so the user sees where the persona put them and
     * can still move. Clearing a persona leaves the mode where it is.
     */
    fun setPersona(persona: Persona?) {
        if (_uiState.value.messages.isNotEmpty()) return
        if (_uiState.value.activePersonaId == persona?.id) return
        // While another chat is running the engine stays bound to it; the picked
        // persona rides in UI state and reaches the engine at send time, through
        // bindEngineToViewedSession.
        if (_uiState.value.runningSessionId == null) {
            agentEngine.sessionPersonaId = persona?.id
        }
        _uiState.update { it.copy(activePersonaId = persona?.id) }
        persona?.let { setAgent(it.defaultAgent) }
    }

    override fun onCleared() {
        confirmationOverlay.dismiss()
        super.onCleared()
    }

    /**
     * Start a fresh chat. [defaultAgent] sets the starting mode: Monitor when
     * created from the app UI, Operator when created from the assistive ball.
     *
     * If a run is in progress in another session, this is a VIEW-ONLY switch to a
     * new blank chat — the engine stays bound to the running session. The engine
     * is only re-pointed at the new blank chat when nothing is running.
     */
    fun clearChat(defaultAgent: AgentMode = AgentMode.MONITOR) {
        lastInputWasVoice = false
        currentRunIsVoice = false
        val newId = java.util.UUID.randomUUID().toString()
        val runInProgress = _uiState.value.runningSessionId != null
        nextId = 0
        if (!runInProgress) {
            agentEngine.history.clear()
            agentEngine.sessionId = newId
            agentEngine.tokenCount = 0
            agentEngine.restoreTitle(null)
            // Without this the previous chat's run summaries survive into the
            // new session: the share card would promote the old conversation
            // and saveCurrentSession() would persist the stale list into the
            // new chat file.
            agentEngine.restoreRunSummaries(emptyList())
            agentEngine.sessionIsSample = false
            // A new chat never inherits the previous one's persona: it is picked
            // per chat, on the home screen this call is about to show.
            agentEngine.sessionPersonaId = null
            agentEngine.setupWorkingDir(create = false)
            engineTranscript = emptyList()
            engineAgent = defaultAgent
        }
        _uiState.update {
            it.copy(
                messages = emptyList(),
                activeSessionId = newId,
                activeAgent = defaultAgent,
                activePersonaId = null,
                viewingSample = false
            )
        }
        applyContextUsage(0)
    }

    /**
     * One-time migration: rename any pre-existing UUID-named chat working dirs
     * to the readable "Slug_shortId" scheme. The "done" flag is only persisted
     * once every session was handled, so a partial migration (a rename blocked by
     * a missing storage grant, say) is retried on the next launch instead of
     * leaving UUID-named dirs stranded forever.
     */
    private suspend fun migrateChatDirsIfNeeded() {
        val prefs = settingsRepository.prefs
        if (prefs.getBoolean(MIGRATED_CHAT_DIRS_KEY, false)) return
        val sessions = historyRepository.listSessions()
        val chatsRoot = com.gotcha.data.GotchaStorage.chatsRoot()
        var allMigrated = true
        for (session in sessions) {
            try {
                val rawDir = java.io.File(chatsRoot, session.id)
                if (!rawDir.exists() || !rawDir.isDirectory) continue
                val target = java.io.File(
                    chatsRoot,
                    com.gotcha.data.GotchaStorage.chatDirName(session.title, session.id)
                )
                // An already-migrated target is not a failure: keep the raw dir
                // out of the way rather than clobbering the renamed one.
                if (!rawDir.renameTo(target) && !target.isDirectory) {
                    allMigrated = false
                    android.util.Log.w("Gotcha", "migrateChatDirsIfNeeded: rename failed for ${session.id}")
                }
            } catch (e: Exception) {
                allMigrated = false
                android.util.Log.w("Gotcha", "migrateChatDirsIfNeeded: failed for ${session.id}", e)
            }
        }
        if (allMigrated) prefs.edit().putBoolean(MIGRATED_CHAT_DIRS_KEY, true).apply()
    }

    fun refreshSessions() {
        viewModelScope.launch {
            _sessions.value = historyRepository.listSessions()
        }
    }

    fun openSession(id: String?) {
        id?.let(completionNotifier::cancel)
        lastInputWasVoice = false
        currentRunIsVoice = false
        viewModelScope.launch {
            if (id == null) {
                clearChat()
                return@launch
            }
            // Re-viewing the running session: just show its live engine transcript.
            if (id == agentEngine.sessionId && _uiState.value.runningSessionId == id) {
                nextId = (engineTranscript.maxOfOrNull { it.id }?.plus(1)) ?: 0
                _uiState.update {
                    it.copy(
                        activeSessionId = id,
                        activeAgent = engineAgent,
                        activePersonaId = agentEngine.sessionPersonaId,
                        messages = engineTranscript,
                        viewingSample = _sessions.value.firstOrNull { s -> s.id == id }?.isSample
                            ?: agentEngine.sessionIsSample
                    )
                }
                applyContextUsage(agentEngine.tokenCount)
                return@launch
            }
            val session = historyRepository.loadSession(id) ?: return@launch
            val restoredAgent = session.agentMode
                ?.let { runCatching { AgentMode.valueOf(it) }.getOrNull() }
                ?: AgentMode.MONITOR
            val runInProgress = _uiState.value.runningSessionId != null
            // Only rebind the engine when idle. While a run is active this is a
            // view-only switch so the running session is never disturbed.
            if (!runInProgress) {
                agentEngine.history.clear()
                agentEngine.history.addAll(session.messages)
                agentEngine.sessionId = session.id
                agentEngine.tokenCount = session.tokenCount
                agentEngine.restoreTitle(if (session.isFallbackTitle()) null else session.title)
                agentEngine.restoreRunSummaries(session.runSummaries)
                agentEngine.sessionIsSample = session.isSample
                agentEngine.sessionPersonaId = session.personaId
                agentEngine.setupWorkingDir()
                engineTranscript = session.displayMessages
                engineAgent = restoredAgent
            }
            // Restore the verbatim on-screen transcript so what's shown on reopen
            // matches exactly what was shown live. Fall back to a lossy rebuild
            // only for legacy sessions saved before display messages existed.
            if (session.displayMessages.isNotEmpty()) {
                nextId = (session.displayMessages.maxOf { it.id } + 1)
                _uiState.update {
                    it.copy(
                        activeSessionId = session.id,
                        activeAgent = restoredAgent,
                        activePersonaId = session.personaId,
                        viewingSample = session.isSample,
                        messages = session.displayMessages,
                        // Clear engine-scoped transient UI for the viewed (non-running) chat.
                        activity = null,
                        subAgentRunning = null,
                        subAgentCurrentAction = null
                    )
                }
                applyContextUsage(session.tokenCount)
            } else {
                nextId = 0
                _uiState.update {
                    it.copy(
                        activeSessionId = session.id,
                        activeAgent = restoredAgent,
                        activePersonaId = session.personaId,
                        viewingSample = session.isSample,
                        activity = null,
                        subAgentRunning = null,
                        subAgentCurrentAction = null
                    )
                }
                applyContextUsage(session.tokenCount)
                rebuildUiMessagesFrom(session.messages)
            }
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                com.gotcha.data.GotchaStorage.archiveChatDir(id)
            }
            historyRepository.deleteSession(id)
            localNotificationStore.forgetChat(id)
            if (agentEngine.sessionId == id) {
                clearChat()
            }
            // Drop any live overlay entry for the deleted session so the
            // drawer doesn't keep showing a token count for a chat that no
            // longer exists.
            _liveTokenBySession.update { it - id }
            refreshSessions()
        }
    }

    private fun appendUi(
        kind: MessageKind,
        text: String,
        imageBase64: String? = null,
        subAgentSteps: List<String> = emptyList(),
        reasoningContent: String? = null
    ) {
        _uiState.update {
            it.copy(
                messages = it.messages + UiMessage(
                    nextId++, kind, text, imageBase64, subAgentSteps, subAgentCollapsed = true, reasoningContent = reasoningContent
                )
            )
        }
    }

    /**
     * Append a bubble authored by the ENGINE (agent output). Always records it on
     * [engineTranscript] so the running session's transcript stays complete even
     * while the user browses another chat; mirrors into the visible [_uiState]
     * only when that running session is the one being viewed.
     */
    private fun appendEngineUi(
        kind: MessageKind,
        text: String,
        imageBase64: String? = null,
        attachments: List<ComposerAttachment> = emptyList(),
        subAgentSteps: List<String> = emptyList(),
        reasoningContent: String? = null
    ) {
        val viewing = viewingEngineSession()
        val id = if (viewing) nextId++ else engineNextId++
        if (kind == MessageKind.ERROR) runHadError = true
        val message = UiMessage(
            id = id,
            kind = kind,
            text = text,
            imageBase64 = imageBase64,
            subAgentSteps = subAgentSteps,
            subAgentCollapsed = true,
            reasoningContent = reasoningContent,
            attachments = attachments
        )
        engineTranscript = engineTranscript + message
        if (viewing) {
            _uiState.update { it.copy(messages = it.messages + message) }
        }
    }

    private fun rebuildUiMessagesFrom(source: List<ChatMessage>) {
        val rebuilt = source.mapNotNull { msg ->
            val text = msg.textContent
            when {
                msg.role == "user" && (text.startsWith("[Screen State]") || text.startsWith("Screen text:")) -> {
                    val saved = if (text.contains("Saved to:")) " → " + text.substringAfter("Saved to:").trim() else ""
                    val msgText = if (text.startsWith("Screen text:")) {
                        "[Full-resolution screenshot captured]$saved"
                    } else {
                        "[Screenshot captured for visual context]"
                    }
                    UiMessage(nextId++, MessageKind.ASSISTANT, msgText)
                }
                msg.role == "user" -> {
                    val text = msg.textContent
                    val docPrompt = documentPromptText(text)
                    UiMessage(
                        nextId++,
                        MessageKind.USER,
                        if (docPrompt != null) {
                            docPrompt.ifEmpty { "(document attached)" }
                        } else {
                            text.ifBlank { "(image attached)" }
                        }
                    )
                }
                msg.role == "assistant" && text.isNotBlank() ->
                    UiMessage(nextId++, MessageKind.ASSISTANT, text, reasoningContent = msg.reasoningContent)
                msg.role == "assistant" && !msg.reasoningContent.isNullOrBlank() ->
                    UiMessage(nextId++, MessageKind.ASSISTANT, "", reasoningContent = msg.reasoningContent)
                msg.role == "tool" && text.startsWith("SUBAGENT_STEPS:") -> {
                    val descEnd = text.indexOf('\n', "SUBAGENT_STEPS:".length)
                    val rest = if (descEnd > 0) {
                        text.substring(
                            descEnd + 1
                        )
                    } else {
                        text.substring("SUBAGENT_STEPS:".length)
                    }
                    val stepsMarker = "── Steps ──\n"
                    val resultMarker = "\n── Result ──\n"
                    val steps: List<String>
                    val answer: String
                    if (rest.startsWith(stepsMarker)) {
                        val afterSteps = rest.removePrefix(stepsMarker)
                        val resIdx = afterSteps.indexOf(resultMarker)
                        if (resIdx >= 0) {
                            steps = afterSteps.substring(0, resIdx).split("\n").filter { it.isNotBlank() }
                            answer = afterSteps.substring(resIdx + resultMarker.length)
                        } else {
                            steps = emptyList()
                            answer = afterSteps
                        }
                    } else {
                        steps = emptyList()
                        answer = rest
                    }
                    UiMessage(
                        nextId++,
                        MessageKind.SUBAGENT,
                        answer,
                        subAgentSteps = steps,
                        reasoningContent = msg.reasoningContent
                    )
                }
                msg.role == "tool" -> UiMessage(nextId++, MessageKind.TOOL, text.ifEmpty { "(no result)" })
                else -> null
            }
        }
        _uiState.update { it.copy(messages = rebuilt) }
    }

    fun exportChat() {
        val markdown = ChatMarkdown.export(
            history = agentEngine.history.toList(),
            sessionId = agentEngine.sessionId ?: "unknown",
            title = agentEngine.currentTitle()
        )
        _exportContent.tryEmit(markdown)
    }

    /**
     * Generates the "Share your Gotcha moment" poster for [runs] (one LLM call
     * for the copy + a deterministic on-device render). Returns the finished
     * Bitmap, or a failure message. Safe to call from a coroutine; the render
     * hops to the main thread internally.
     */
    suspend fun generateShareCard(
        runs: List<RunSummary>
    ): Result<Bitmap> = runCatching {
        val client = ShareCardClient(getApplication(), settings)
        val content = client.generate(runs)
        if (!content.eligible) {
            error("Nothing accomplished in this run to showcase yet.")
        }
        val stats = PosterStatsBuilder.from(runs)
        withContext(Dispatchers.Main) {
            PosterRenderer.render(getApplication(), content, stats)
        }
    }

    /**
     * Run summaries for the session currently bound to the engine.
     *
     * Returns the recorded summaries when present. Chats created before the
     * run-summary feature landed have none persisted, so fall back to
     * [synthesizeRunSummariesFromHistory] — the same history [exportChat]
     * reads, which keeps the share card and the export consistent.
     *
     * No memoization: the only caller is the share-card tap handler, not a
     * recomposition, and the synthesis walk only trims text content — cheaper
     * than fingerprinting the history (which would hash vision base64 payloads).
     */
    fun activeSessionRunSummaries(): List<RunSummary> {
        val recorded = agentEngine.runSummaries
        if (recorded.isNotEmpty()) return recorded.toList()
        // Snapshot the history before iterating: the engine coroutine mutates it
        // as it runs, and this is read from the UI thread.
        val snapshot = agentEngine.history.toList()
        return synthesizeRunSummariesFromHistory(snapshot, settings.model, engineAgent.name)
    }

    private companion object {
        const val GATE_TIMEOUT_MS = 120_000L

        /** Set once the one-time notification-permission ask has been shown. */
        const val KEY_NOTIFICATION_PERMISSION_ASKED = "chat_notification_permission_asked"

        const val MIGRATED_CHAT_DIRS_KEY = "migrated_chat_dirs_v1"
    }
}

/**
 * Builds one [RunSummary] per user → assistant exchange in [history], for the
 * "Share your Gotcha moment" card when a chat predates recorded run summaries.
 *
 * A vision request is a plain user message, so text extraction via
 * [ChatMessage.textContent] covers both. Each exchange starts at a "user"
 * message and ends at the next one; the assistant text that lands in between
 * becomes the [RunSummary.finalReply]. Exchanges with no assistant reply are
 * skipped, so an empty or unanswered chat still yields an empty list (the
 * share card correctly reports nothing to share).
 */
internal fun synthesizeRunSummariesFromHistory(
    history: List<ChatMessage>,
    model: String,
    agentMode: String
): List<RunSummary> {
    val summaries = mutableListOf<RunSummary>()
    var pendingPrompt: String? = null
    var pendingReply: String? = null
    // Original exchange times aren't in history, so every synthesized summary
    // shares one synthetic timestamp rather than a distinct per-exchange one.
    val synthesizedAt = System.currentTimeMillis()

    fun flush() {
        val prompt = pendingPrompt
        val reply = pendingReply
        if (!prompt.isNullOrBlank() && !reply.isNullOrBlank()) {
            summaries += RunSummary(
                startedAt = synthesizedAt,
                endedAt = synthesizedAt,
                userPrompt = prompt.take(400),
                finalReply = reply.take(800),
                model = model,
                agentMode = agentMode,
                delegated = false,
                succeeded = true,
                toolCalls = emptyList()
            )
        }
        pendingPrompt = null
        pendingReply = null
    }

    for (msg in history) {
        when (msg.role) {
            "user" -> {
                flush()
                pendingPrompt = msg.textContent.trim()
            }
            "assistant" -> {
                val text = msg.textContent.trim()
                // Only the final assistant text of the exchange matters; tool
                // rounds between the prompt and the answer carry no copy.
                if (text.isNotBlank()) pendingReply = text
            }
        }
    }
    flush()
    return summaries
}
