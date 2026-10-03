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
import com.gotcha.audio.SttEngine
import com.gotcha.data.ChatArchive
import com.gotcha.data.ChatImporter
import com.gotcha.data.ChatMarkdown
import com.gotcha.data.ChatSession
import com.gotcha.data.DuplicateStrategy
import com.gotcha.data.ImportParseResult
import com.gotcha.data.RunSummary
import com.gotcha.data.Settings
import com.gotcha.data.documentPromptText
import com.gotcha.i18n.Language
import com.gotcha.i18n.SpokenPhrases
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.LLMClient
import com.gotcha.marketing.PosterRenderer
import com.gotcha.marketing.PosterStatsBuilder
import com.gotcha.marketing.ShareCardClient
import com.gotcha.notifications.ChatCompletionNotifier
import com.gotcha.notifications.LocalNotificationStore
import com.gotcha.tools.AgentMode
import com.gotcha.tools.DocumentError
import com.gotcha.tools.DocumentParser
import com.gotcha.ui.Persona
import com.gotcha.util.HumanReadableError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
/** [text] on one line, cut at a word to at most [max] characters, with "…" when cut. */
internal fun shortTitle(text: String, max: Int = 40): String {
    val line = text.trim().replace(Regex("\\s+"), " ")
    if (line.length <= max) return line
    val cut = line.take(max)
    val atWord = cut.substringBeforeLast(' ').takeIf { it.length >= max / 2 } ?: cut
    return atWord.trimEnd(',', '.', ';', ':', ' ') + "…"
}

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
    /** The once-per-request "may Gotcha control your apps?" ask on screen (issue #98). */
    val pendingForegroundControl: ForegroundControlRequest? = null,
    /**
     * "Gotcha is controlling …" while the run controls another app, then "Gotcha
     * is done…" once it has let go (issue #98). Cleared when the next run starts.
     */
    val foregroundControlStatus: String? = null,
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
/**
 * The chat screen's state. The agent and the run in progress belong to the
 * app-scoped [ChatRunner] (issue #111), so a run outlives this ViewModel when
 * the Activity finishes; this binds the runner to the chat being viewed, starts
 * runs on it and mirrors its [RunState] into [uiState].
 */
@Suppress("TooManyFunctions", "LargeClass")
class ChatViewModel(application: Application) : AndroidViewModel(application), ChatRunObserver {

    /** `internal` so tests can drive the engine's side of a run directly. */
    internal val runner = ChatRunner.of(application)

    private val settingsRepository = runner.settingsRepository
    private val historyRepository = runner.historyRepository
    private val completionNotifier = ChatCompletionNotifier(application)
    private val localNotificationStore = LocalNotificationStore(application)

    private val settings: Settings get() = runner.settings
    private val client: LLMClient? get() = runner.client

    /** True when the most recent user message was sent via voice (STT). */
    @Volatile
    private var lastInputWasVoice = false

    private val sttEngine: SttEngine = SttEngine(
        getApplication(),
        settings.effectiveSttBaseUrl,
        settings.effectiveSttApiKey,
        onUnauthorized = { viewModelScope.launch { runner.onSamosaUnauthorized() } }
    )

    /** Next UiMessage id for a chat shown while the engine is bound to another one. */
    private var nextId = 0L

    /** True when the session the user is viewing is the one bound to the engine. */
    private fun viewingEngineSession(): Boolean =
        _uiState.value.activeSessionId == runner.state.value.boundSessionId

    /**
     * Startup: waits for the runner's once-per-process startup (the migration,
     * the sample seeding) before the drawer lists chats. Anything that loads a
     * saved chat on the user's behalf before the UI is up (a notification tap)
     * waits for it too.
     */
    private val initJob: Job

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    /** See [ChatRunner.liveTokenBySession]. */
    val liveTokenBySession: StateFlow<Map<String, Int>> = runner.liveTokenBySession

    /** See [ChatRunner.permissionRequests]. */
    val permissionRequests: SharedFlow<String> = runner.permissionRequests

    /** See [ChatRunner.inboxChanges]. */
    val inboxChanges: StateFlow<Int> = runner.inboxChanges

    /** Exported chat markdown content the Activity should share. */
    private val _exportContent = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val exportContent: SharedFlow<String> = _exportContent.asSharedFlow()

    init {
        runner.addObserver(this)
        onSettingsChanged()
        val run = runner.state.value
        if (runner.isRunning && run.boundSessionId != null) {
            // Issue #111: the run outlived the screen that started it; show it.
            showBoundSession(run.boundSessionId)
        } else {
            // Always start on a fresh session so the home screen greets with an
            // empty chat; past sessions remain one tap away in the drawer.
            val newId = java.util.UUID.randomUUID().toString()
            runner.bindFresh(newId, AgentMode.MONITOR)
            _uiState.update { it.copy(activeSessionId = newId) }
        }
        applyRunState(runner.state.value, initial = true)
        updateContextUsage()
        initJob = viewModelScope.launch {
            runner.startup.join()
            refreshSessions()
        }
    }

    // ---- ChatRunObserver (runner → screen) ----

    override fun onRunStateChanged(old: RunState, new: RunState) {
        applyRunState(new, old)
    }

    /**
     * Mirrors [run] into [uiState]. The run and its gates show whichever chat
     * is viewed; the bound chat's transcript, activity and token count only
     * while it is the one on screen.
     */
    private fun applyRunState(run: RunState, old: RunState? = null, initial: Boolean = false) {
        val viewing = _uiState.value.activeSessionId == run.boundSessionId
        _uiState.update {
            it.copy(
                isBusy = run.isBusy,
                runningSessionId = run.runningSessionId,
                runningSessionTitle = run.runningSessionTitle,
                backgroundHint = run.backgroundHint,
                pendingConfirmation = run.pendingConfirmation,
                pendingForegroundControl = run.pendingForegroundControl,
                foregroundControlStatus = run.foregroundControlStatus,
                pendingQuestion = run.pendingQuestion,
                pendingPermission = run.pendingPermission,
                isConfigured = run.isConfigured,
                isSpeaking = run.isSpeaking,
                messages = if (viewing && (initial || old?.transcript !== run.transcript)) {
                    run.transcript
                } else {
                    it.messages
                },
                activity = if (viewing) run.activity else it.activity,
                subAgentRunning = if (viewing) run.subAgentRunning else it.subAgentRunning,
                subAgentCurrentAction = if (viewing) run.subAgentCurrentAction else it.subAgentCurrentAction
            )
        }
        // Pass the new count explicitly: updateContextUsage() re-applies the
        // count already on screen, so the meter would never move mid-run (#71).
        if (viewing && old != null && old.tokenCount != run.tokenCount) applyContextUsage(run.tokenCount)
    }

    override fun onSettingsChanged() {
        sttEngine.configureApi(settings.effectiveSttBaseUrl, settings.effectiveSttApiKey)
        updateContextUsage()
    }

    /** Shows the chat bound to the engine, live: the running one, typically. */
    private fun showBoundSession(id: String) {
        val run = runner.state.value
        _uiState.update {
            it.copy(
                activeSessionId = id,
                activeAgent = runner.engineAgent,
                activePersonaId = runner.engine.sessionPersonaId,
                messages = run.transcript,
                viewingSample = _sessions.value.firstOrNull { s -> s.id == id }?.isSample
                    ?: runner.engine.sessionIsSample,
                activity = run.activity,
                subAgentRunning = run.subAgentRunning,
                subAgentCurrentAction = run.subAgentCurrentAction
            )
        }
        applyContextUsage(runner.engine.tokenCount)
    }

    fun onPermissionResult(granted: Boolean) = runner.onPermissionResult(granted)

    fun answerForegroundControl(allowed: Boolean) = runner.answerForegroundControl(allowed)

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
    fun refreshSettings() = runner.refreshSettings()

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
        val isVoice = isVoiceInput || lastInputWasVoice
        lastInputWasVoice = false
        claimNotificationPermissionAskForRun()
        runner.send(viewedChat(), trimmed, attachments, isVoice)
    }

    /** What a run started now binds the engine to. */
    private fun viewedChat(): ViewedChat = _uiState.value.let {
        ViewedChat(it.activeSessionId, it.messages, it.activeAgent, it.activePersonaId)
    }

    /** Raises the one-time notification-permission ask as a run starts, if it is due. */
    private fun claimNotificationPermissionAskForRun() {
        _uiState.update {
            it.copy(askNotificationPermission = it.askNotificationPermission || claimNotificationPermissionAsk())
        }
    }

    /**
     * True, once per install, when the user should be asked for notification
     * permission: they want task-finished notifications, Android 13+ blocks
     * them, and they are here to see the ask. Claimed as it is shown, so
     * "Not now" is final — the Notifications settings page can still ask.
     */
    private fun claimNotificationPermissionAsk(): Boolean {
        if (!runner.appInForeground || !settings.chatCompletionNotificationsEnabled) return false
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
        _uiState.update { it.copy(askNotificationPermission = false) }
        if (granted) runner.refreshBackgroundHint()
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
        lastInputWasVoice = false
        claimNotificationPermissionAskForRun()
        runner.edit(viewedChat(), targetId, trimmed, attachments)
    }

    /**
     * Truncates the conversation so the user message [targetId] becomes the last
     * message: its own replies and everything after are dropped from both the LLM
     * history and the on-screen transcript, letting the user continue from a
     * clean state. No LLM call, so it works even without a configured API key.
     */
    fun revertTo(targetId: Long) {
        if (_uiState.value.isBusy || _uiState.value.runningSessionId != null) return
        val view = viewedChat()
        viewModelScope.launch {
            if (runner.revertTo(view, targetId)) refreshSessions()
        }
    }

    /**
     * Point the engine at [viewedId] (the session being viewed) so a run
     * operates on it; see [ChatRunner.bindForSend].
     *
     * `internal` so the handoff can be tested directly: it is where a persona
     * picked while another chat was running finally reaches the engine, and
     * [sendMessage] itself can't be driven from a JVM test.
     */
    internal suspend fun bindEngineToViewedSession(viewedId: String?) {
        runner.bindForSend(viewedChat().copy(sessionId = viewedId))
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

    fun stopAgent() = runner.stop()

    fun confirmPendingActions(approved: Boolean) = runner.confirmPendingActions(approved)

    fun submitAnswer(answer: String?) = runner.submitAnswer(answer)

    /**
     * Called from the Activity's onStart/onStop so confirmations know if they'd be hidden.
     * [recreating] is an onStop for a rotation or other configuration change, which is
     * back within a moment and should not raise a notification.
     */
    fun setForeground(foreground: Boolean, recreating: Boolean = false) {
        runner.setForeground(foreground, recreating)
        // Back in the app on a chat whose task finished: that chat's
        // notification and inbox entries have done their job.
        if (foreground) _uiState.value.activeSessionId?.let(::markChatSeen)
    }

    /** The user has seen [sessionId]: its notification and inbox entries have done their job (issue #116). */
    private fun markChatSeen(sessionId: String) {
        completionNotifier.cancel(sessionId)
        runner.markChatRead(sessionId)
    }

    /** Speak the given text aloud using the configured TTS provider. */
    fun speak(text: String) = runner.speak(text)

    /** Stop any ongoing TTS speech output. */
    fun stopSpeaking() = runner.stopSpeaking()

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
        val ttsEngine = runner.ttsEngine
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
            val ttsModels = runner.ttsEngine.refreshApiModels()
            val sttModels = sttEngine.refreshApiModels()
            runner.ttsModels = ttsModels
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
            runner.engine.history += ChatMessage(role = "system", content = JsonPrimitive(llmMsg))
            runner.engineAgent = next
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
            runner.engine.sessionPersonaId = persona?.id
        }
        _uiState.update { it.copy(activePersonaId = persona?.id) }
        persona?.let { setAgent(it.defaultAgent) }
    }

    /**
     * The Activity is gone for good (issue #111). The run, if any, goes on in
     * [runner]; only what needs this screen is let go.
     */
    override fun onCleared() {
        runner.removeObserver(this)
        runner.onUiDetached()
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
        runner.clearVoiceFlag()
        val newId = java.util.UUID.randomUUID().toString()
        val runInProgress = _uiState.value.runningSessionId != null
        nextId = 0
        if (!runInProgress) runner.bindFresh(newId, defaultAgent)
        _uiState.update {
            it.copy(
                messages = emptyList(),
                activeSessionId = newId,
                activeAgent = defaultAgent,
                activePersonaId = null,
                viewingSample = false,
                activity = null,
                subAgentRunning = null,
                subAgentCurrentAction = null
            )
        }
        applyContextUsage(0)
    }

    fun refreshSessions() {
        viewModelScope.launch {
            _sessions.value = historyRepository.listSessions()
        }
    }

    fun openSession(id: String?) {
        id?.let(::markChatSeen)
        lastInputWasVoice = false
        runner.clearVoiceFlag()
        viewModelScope.launch {
            if (id == null) {
                clearChat()
                return@launch
            }
            // Re-viewing the running session: just show its live transcript.
            val run = runner.state.value
            if (id == run.boundSessionId && run.runningSessionId == id) {
                showBoundSession(id)
                return@launch
            }
            val session = historyRepository.loadSession(id) ?: return@launch
            val restoredAgent = session.agentMode
                ?.let { runCatching { AgentMode.valueOf(it) }.getOrNull() }
                ?: AgentMode.MONITOR
            // Restore the verbatim on-screen transcript so what's shown on reopen
            // matches exactly what was shown live. Fall back to a lossy rebuild
            // only for legacy sessions saved before display messages existed.
            val shown = session.displayMessages.ifEmpty { rebuildUiMessagesFrom(session.messages) }
            // Only rebind the engine when idle. While a run is active this is a
            // view-only switch so the running session is never disturbed.
            if (_uiState.value.runningSessionId == null) runner.bindSaved(session, restoredAgent, shown)
            nextId = shown.maxOfOrNull { it.id }?.plus(1) ?: 0
            _uiState.update {
                it.copy(
                    activeSessionId = session.id,
                    activeAgent = restoredAgent,
                    activePersonaId = session.personaId,
                    viewingSample = session.isSample,
                    messages = shown,
                    // Clear engine-scoped transient UI for the viewed (non-running) chat.
                    activity = null,
                    subAgentRunning = null,
                    subAgentCurrentAction = null
                )
            }
            applyContextUsage(session.tokenCount)
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            // First, so a run in this chat is over before its files and record go.
            val wasBound = runner.engine.sessionId == id
            runner.discard(id)
            withContext(Dispatchers.IO) {
                com.gotcha.data.GotchaStorage.archiveChatDir(id)
            }
            historyRepository.deleteSession(id)
            localNotificationStore.forgetChat(id)
            if (_uiState.value.activeSessionId == id) {
                clearChat()
            } else if (wasBound) {
                // Viewing another chat: the engine lets go of the deleted one
                // without moving the screen.
                runner.bindFresh(java.util.UUID.randomUUID().toString(), AgentMode.MONITOR)
            }
            // Drop any live overlay entry for the deleted session so the
            // drawer doesn't keep showing a token count for a chat that no
            // longer exists.
            runner.forgetLiveTokens(id)
            refreshSessions()
        }
    }

    /**
     * Shows a notice from the screen itself. In the chat bound to the engine it
     * joins the runner's transcript, so it is saved with the chat and outlives
     * this ViewModel like the agent's own bubbles.
     */
    private fun appendUi(
        kind: MessageKind,
        text: String,
        imageBase64: String? = null,
        subAgentSteps: List<String> = emptyList(),
        reasoningContent: String? = null
    ) {
        if (viewingEngineSession()) {
            runner.appendTranscript(
                kind = kind,
                text = text,
                imageBase64 = imageBase64,
                subAgentSteps = subAgentSteps,
                reasoningContent = reasoningContent
            )
            return
        }
        _uiState.update {
            it.copy(
                messages = it.messages + UiMessage(
                    nextId++, kind, text, imageBase64, subAgentSteps, subAgentCollapsed = true, reasoningContent = reasoningContent
                )
            )
        }
    }

    /** A legacy chat saved without its on-screen transcript, rebuilt from its history. */
    private fun rebuildUiMessagesFrom(source: List<ChatMessage>): List<UiMessage> {
        var rebuiltId = 0L
        return source.mapNotNull { msg ->
            val text = msg.textContent
            when {
                msg.role == "user" && (text.startsWith("[Screen State]") || text.startsWith("Screen text:")) -> {
                    val saved = if (text.contains("Saved to:")) " → " + text.substringAfter("Saved to:").trim() else ""
                    val msgText = if (text.startsWith("Screen text:")) {
                        "[Full-resolution screenshot captured]$saved"
                    } else {
                        "[Screenshot captured for visual context]"
                    }
                    UiMessage(rebuiltId++, MessageKind.ASSISTANT, msgText)
                }
                msg.role == "user" -> {
                    val text = msg.textContent
                    val docPrompt = documentPromptText(text)
                    UiMessage(
                        rebuiltId++,
                        MessageKind.USER,
                        if (docPrompt != null) {
                            docPrompt.ifEmpty { "(document attached)" }
                        } else {
                            text.ifBlank { "(image attached)" }
                        }
                    )
                }
                msg.role == "assistant" && text.isNotBlank() ->
                    UiMessage(rebuiltId++, MessageKind.ASSISTANT, text, reasoningContent = msg.reasoningContent)
                msg.role == "assistant" && !msg.reasoningContent.isNullOrBlank() ->
                    UiMessage(rebuiltId++, MessageKind.ASSISTANT, "", reasoningContent = msg.reasoningContent)
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
                        rebuiltId++,
                        MessageKind.SUBAGENT,
                        answer,
                        subAgentSteps = steps,
                        reasoningContent = msg.reasoningContent
                    )
                }
                msg.role == "tool" -> UiMessage(rebuiltId++, MessageKind.TOOL, text.ifEmpty { "(no result)" })
                else -> null
            }
        }
    }

    fun exportChat() {
        viewModelScope.launch {
            val markdown = viewedSessionSnapshot()?.let { chat ->
                ChatMarkdown.export(history = chat.history, sessionId = chat.id, title = chat.title)
            } ?: return@launch
            _exportContent.tryEmit(markdown)
        }
    }

    /** What [exportChat] and the share card read: the chat on screen, as saved or live. */
    private class ViewedSessionSnapshot(
        val id: String,
        val title: String,
        val history: List<ChatMessage>,
        val runSummaries: List<RunSummary>,
        val agentName: String
    )

    /**
     * The chat on screen, which is not always the one bound to the engine: while
     * a run goes on, and after it until the next send, other chats are opened
     * view-only. Reading the engine then would export or share the running chat
     * instead of the one the user is looking at.
     */
    private suspend fun viewedSessionSnapshot(): ViewedSessionSnapshot? {
        val id = _uiState.value.activeSessionId ?: return null
        if (id == runner.engine.sessionId) {
            // Snapshot the history before iterating: the engine coroutine mutates
            // it as it runs, and this is read from the UI thread.
            return ViewedSessionSnapshot(
                id = id,
                title = runner.engine.currentTitle(),
                history = runner.engine.history.toList(),
                runSummaries = runner.engine.runSummaries.toList(),
                agentName = runner.engineAgent.name
            )
        }
        // A chat opened view-only is on disk; one that isn't is a new, empty one.
        val session = historyRepository.loadSession(id)
            ?: return ViewedSessionSnapshot(id, "New Chat", emptyList(), emptyList(), _uiState.value.activeAgent.name)
        return ViewedSessionSnapshot(
            id = session.id,
            title = session.title,
            history = session.messages,
            runSummaries = session.runSummaries,
            agentName = session.agentMode ?: _uiState.value.activeAgent.name
        )
    }

    // ---- Chat backup and import (issue #83) ----

    private val chatImporter = ChatImporter(historyRepository)

    private val _chatTransfer = MutableStateFlow<ChatTransferState>(ChatTransferState.Idle)
    val chatTransfer: StateFlow<ChatTransferState> = _chatTransfer.asStateFlow()

    /** Set just before the "save backup" picker opens, consumed by [writeBackup]. */
    private var pendingBackup: BackupRequest? = null

    /**
     * Holds [request] for the file picker the caller is about to open, and
     * returns the file name to suggest in it.
     */
    fun prepareBackup(request: BackupRequest): String {
        pendingBackup = request
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val name = request.sessionId
            ?.let { id -> _sessions.value.firstOrNull { it.id == id }?.title }
            ?.let { title -> title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40) }
            ?.takeIf { it.isNotEmpty() }
            ?: if (request.sessionId == null) "chats" else "chat"
        return "gotcha-$name-$date${ChatArchive.FILE_SUFFIX}"
    }

    /** Writes the [prepareBackup] request to [uri]; a null [uri] means the picker was cancelled. */
    fun writeBackup(uri: Uri?) {
        val request = pendingBackup ?: return
        pendingBackup = null
        if (uri == null) return
        _chatTransfer.value = ChatTransferState.Working("Saving backup…")
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val sessions = request.sessionId
                        ?.let { listOfNotNull(historyRepository.loadSession(it)) }
                        ?: historyRepository.listSessions()
                    check(sessions.isNotEmpty()) { "There are no saved chats to back up yet." }
                    val archive = ChatArchive(
                        exportedAt = System.currentTimeMillis(),
                        appVersion = com.gotcha.BuildConfig.VERSION_NAME,
                        includesImages = request.includeImages,
                        sessions = if (request.includeImages) sessions else sessions.map(ChatArchive::withoutImages)
                    )
                    val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?: error("The chosen location can't be written to.")
                    stream.use { it.write(ChatArchive.encode(archive).toByteArray(Charsets.UTF_8)) }
                    sessions.size
                }
            }
            _chatTransfer.value = outcome.fold(
                onSuccess = { count ->
                    ChatTransferState.Report(
                        "Backup saved",
                        if (count == 1) "Saved 1 chat." else "Saved $count chats."
                    )
                },
                onFailure = { e ->
                    ChatTransferState.Report("Backup failed", e.message ?: "The backup couldn't be saved.")
                }
            )
        }
    }

    /** Reads the file the user picked to import and shows what it holds; null means the picker was cancelled. */
    fun readImport(uri: Uri?) {
        if (uri == null) return
        _chatTransfer.value = ChatTransferState.Working("Reading file…")
        viewModelScope.launch {
            val bytes = runCatching {
                withContext(Dispatchers.IO) { readAtMost(uri, ChatImporter.MAX_BYTES) }
            }.getOrNull()
            _chatTransfer.value = when {
                bytes == null -> ChatTransferState.Report("Import failed", "The file couldn't be opened.")
                bytes.size > ChatImporter.MAX_BYTES -> ChatTransferState.Report(
                    "Import failed",
                    "The file is larger than ${ChatImporter.MAX_BYTES / (1024 * 1024)} MB, the most Gotcha imports at once."
                )
                else -> when (val read = chatImporter.preview(bytes)) {
                    is ImportParseResult.Ready -> ChatTransferState.Previewing(read.preview)
                    is ImportParseResult.Failed -> ChatTransferState.Report("Import failed", read.message)
                }
            }
        }
    }

    /** Imports the previewed file, resolving clashes with existing chats by [strategy]. */
    fun confirmImport(strategy: DuplicateStrategy) {
        val preview = (_chatTransfer.value as? ChatTransferState.Previewing)?.preview ?: return
        _chatTransfer.value = ChatTransferState.Working("Importing…")
        viewModelScope.launch {
            val result = chatImporter.commit(
                preview,
                strategy,
                protectedIds = setOfNotNull(_uiState.value.runningSessionId)
            )
            refreshSessions()
            // An open chat that was just replaced would otherwise keep showing,
            // and on the next turn saving, the copy that was imported over.
            val open = _uiState.value.activeSessionId
            if (open != null && open in result.writtenIds) openSession(open)

            val counts = listOfNotNull(
                "Imported ${result.imported}",
                result.replaced.takeIf { it > 0 }?.let { "replaced $it" },
                result.skipped.takeIf { it > 0 }?.let { "skipped $it" },
                result.failed.size.takeIf { it > 0 }?.let { "$it failed" }
            )
            _chatTransfer.value = ChatTransferState.Report(
                title = if (result.imported + result.replaced > 0) "Import finished" else "Nothing imported",
                summary = counts.joinToString(" · ") + ".",
                details = result.failed.map { "${it.title}: ${it.reason}" } + preview.warnings
            )
        }
    }

    fun dismissChatTransfer() {
        _chatTransfer.value = ChatTransferState.Idle
    }

    /** Up to [limit] + 1 bytes of [uri], so an oversized file is caught without reading all of it. */
    private fun readAtMost(uri: Uri, limit: Int): ByteArray? {
        val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: return null
        return input.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (out.size() <= limit) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
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
     * Run summaries for the chat on screen (see [viewedSessionSnapshot]).
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
    suspend fun activeSessionRunSummaries(): List<RunSummary> {
        val chat = viewedSessionSnapshot() ?: return emptyList()
        if (chat.runSummaries.isNotEmpty()) return chat.runSummaries
        return synthesizeRunSummariesFromHistory(chat.history, settings.model, chat.agentName)
    }

    private companion object {
        /** Set once the one-time notification-permission ask has been shown. */
        const val KEY_NOTIFICATION_PERMISSION_ASKED = "chat_notification_permission_asked"
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
