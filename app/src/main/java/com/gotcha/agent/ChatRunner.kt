package com.gotcha.agent

import android.app.Application
import com.gotcha.GotchaApp
import com.gotcha.audio.AudioModel
import com.gotcha.audio.AudioProvider
import com.gotcha.audio.CompletionFeedback
import com.gotcha.audio.TtsEngine
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.ChatSession
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.DocumentPart
import com.gotcha.llm.LLMClient
import com.gotcha.llm.attachmentsUserMessage
import com.gotcha.notifications.AttentionKind
import com.gotcha.notifications.AttentionNotifier
import com.gotcha.notifications.ChatCompletionNotifier
import com.gotcha.notifications.LocalNotificationStore
import com.gotcha.notifications.NotificationCategory
import com.gotcha.notifications.NotificationTarget
import com.gotcha.notifications.RunOutcome
import com.gotcha.service.ChatRunService
import com.gotcha.service.RunningChat
import com.gotcha.tools.AgentMode
import com.gotcha.tools.GotchaSettingsUpdate
import com.gotcha.tools.ScreenPerception
import com.gotcha.tools.ToolResult
import com.gotcha.tools.mergeProfileUpdate
import com.gotcha.ui.ConfirmationOverlay
import com.gotcha.ui.ForegroundControlIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the chat screen shows of the session bound to [ChatRunner]'s engine and
 * of the run in progress, if any. [transcript], [activity], the sub-agent
 * fields and [tokenCount] are about [boundSessionId]; the gates and the run
 * fields are shown whichever chat is being viewed.
 */
data class RunState(
    val boundSessionId: String? = null,
    val transcript: List<UiMessage> = emptyList(),
    val isBusy: Boolean = false,
    val runningSessionId: String? = null,
    val runningSessionTitle: String? = null,
    val backgroundHint: String? = null,
    val activity: String? = null,
    val subAgentRunning: String? = null,
    val subAgentCurrentAction: String? = null,
    val pendingConfirmation: PendingConfirmation? = null,
    val pendingForegroundControl: ForegroundControlRequest? = null,
    val foregroundControlStatus: String? = null,
    val pendingQuestion: PendingQuestion? = null,
    val pendingPermission: String? = null,
    val tokenCount: Int = 0,
    val isConfigured: Boolean = false,
    val isSpeaking: Boolean = false
)

/** A chat screen following [ChatRunner]. Called on the thread that changed the state. */
interface ChatRunObserver {
    fun onRunStateChanged(old: RunState, new: RunState)

    /** [ChatRunner.settings] was reloaded, by the screen or by the agent itself. */
    fun onSettingsChanged() {}
}

/** The chat the user is looking at when they send: what a run binds the engine to. */
data class ViewedChat(
    val sessionId: String?,
    val messages: List<UiMessage>,
    val agent: AgentMode,
    val personaId: String?
)

/**
 * Owns the chat agent and the run in progress, for as long as the process lives
 * (issue #111). A run used to live in `ChatViewModel.viewModelScope`, so it ended
 * whenever `MainActivity` finished: swiped out of Recents, or Back on the home
 * screen. The runner is held by [GotchaApp]; `ChatViewModel` binds it to the chat
 * being viewed, starts runs on it and follows its [state], and a ViewModel
 * created later picks up the run where it is.
 *
 * Everything that must keep working with no screen lives here: the engine and
 * its transcript, the question/confirmation/permission/foreground-control gates,
 * the keep-alive service, the task-finished and needs-input notifications, and
 * reading a reply aloud.
 */
@Suppress("TooManyFunctions", "LargeClass")
class ChatRunner(private val app: Application) : AgentEvents {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsRepository = SettingsRepository(app)
    val historyRepository = ChatHistoryRepository(app)
    private val confirmationOverlay = ConfirmationOverlay(app)
    private val foregroundControlIndicator = ForegroundControlIndicator(app)
    private val completionNotifier = ChatCompletionNotifier(app)
    private val attentionNotifier = AttentionNotifier(app)
    private val localNotificationStore = LocalNotificationStore(app)
    private val runMarker = RunInProgressMarker(settingsRepository.prefs)

    var settings: Settings = Settings()
        private set
    var client: LLMClient? = null
        private set

    val ttsEngine: TtsEngine = TtsEngine(
        app,
        settings.effectiveTtsBaseUrl,
        settings.effectiveTtsApiKey,
        onUnauthorized = { scope.launch { onSamosaUnauthorized() } }
    )

    /** The TTS models last fetched by the screen; they name the default voice. */
    @Volatile
    var ttsModels: List<AudioModel> = emptyList()

    /** True when the active LLM run was initiated by voice dictation. */
    @Volatile
    private var currentRunIsVoice = false

    /**
     * Set by the Activity in onStart/onStop; drives whether gates notify and use
     * the overlay. Once the Activity is gone its last onStop left this false.
     */
    @Volatile
    var appInForeground = true
        private set

    internal val engine = AgentEngine(
        appContext = app,
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
            // inside the tool executor's IO context while appendTranscript touches UI state.
            withContext(Dispatchers.Main) {
                appendTranscript(
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
                appendTranscript(
                    MessageKind.TOOL,
                    "Assistant changed settings: " + plan.lines().joinToString("; ") + "."
                )
            }
            ToolResult.ok(GotchaSettingsUpdate.appliedMessage(plan))
        },
        // Persist the ENGINE session's own data, never the viewed session's —
        // the user may be browsing another chat while this run continues.
        displayMessagesProvider = { _state.value.transcript },
        agentModeProvider = { engineAgent }
    )

    private var confirmationGate: CompletableDeferred<Boolean>? = null
    private var questionGate: CompletableDeferred<String>? = null
    private var permissionGate: CompletableDeferred<Boolean>? = null
    private var foregroundControlGate: CompletableDeferred<Boolean>? = null
    private var agentJob: Job? = null

    /** Next UiMessage id in [RunState.transcript]. */
    private var nextTranscriptId = 0L

    /** Agent mode of the session currently bound to [engine]. */
    internal var engineAgent: AgentMode = AgentMode.MONITOR

    /**
     * True when the run in flight has surfaced an error bubble (LLM failure,
     * user interruption, …). Decides whether an arriving reply gets the normal
     * alert or the error buzz, since the engine reports both outcomes through
     * the same `onAssistantReply` path.
     */
    @Volatile
    private var runHadError = false

    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state.asStateFlow()

    private val observers = CopyOnWriteArrayList<ChatRunObserver>()

    /**
     * Live per-session token counts. Updated on every [onTokenCount] so the
     * drawer's per-row readout doesn't lag one round behind the running
     * session. The persisted [ChatSession.tokenCount] on disk catches up
     * through [AgentEngine.saveCurrentSession], so this overlay is read-first,
     * disk-second.
     */
    private val _liveTokenBySession = MutableStateFlow<Map<String, Int>>(emptyMap())
    val liveTokenBySession: StateFlow<Map<String, Int>> = _liveTokenBySession.asStateFlow()

    /**
     * Special-access markers ("special:*") the Activity should deep-link to.
     * Runtime permissions travel as [RunState.pendingPermission] instead —
     * they need an answer, and this is a fire-and-forget signal, dropped when
     * no Activity is there to take it.
     */
    private val _permissionRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val permissionRequests: SharedFlow<String> = _permissionRequests.asSharedFlow()

    /**
     * Bumped when the runner adds an inbox entry of its own (an interrupted
     * run, issue #105), so the bell re-reads its unread count: the Activity's
     * read in onResume can come before the entry exists.
     */
    private val _inboxChanges = MutableStateFlow(0)
    val inboxChanges: StateFlow<Int> = _inboxChanges.asStateFlow()

    /**
     * Once-per-process startup: the chat-directory migration, the sample
     * seeding and the report of a run that died with the last process. Anything
     * that loads a saved chat before the UI is up (a notification tap) waits for
     * it, or the migration could move the chat out from under the load.
     */
    val startup: Job

    init {
        ScreenPerception.appContext = app
        com.gotcha.agent.skills.SkillRegistry.init(app)
        refreshSettings()
        bindFresh(java.util.UUID.randomUUID().toString(), AgentMode.MONITOR)
        startup = scope.launch {
            migrateChatDirsIfNeeded()
            com.gotcha.data.SampleChatSeeder.seedIfNeeded(historyRepository, settingsRepository.prefs)
            reportInterruptedRun()
        }
        // Stop on the ongoing "Gotcha is working…" notification (issue #105),
        // which works with no Activity at all.
        scope.launch { ChatRunService.stopRequests.collect { stop() } }
    }

    fun addObserver(observer: ChatRunObserver) {
        observers += observer
    }

    fun removeObserver(observer: ChatRunObserver) {
        observers -= observer
    }

    private fun setState(transform: (RunState) -> RunState) {
        val old: RunState
        val new: RunState
        synchronized(_state) {
            old = _state.value
            new = transform(old)
            _state.value = new
        }
        if (old != new) observers.forEach { it.onRunStateChanged(old, new) }
    }

    /** True while a run is in flight, or about to be. */
    val isRunning: Boolean get() = _state.value.let { it.isBusy || it.runningSessionId != null }

    // ---- Binding the engine to a chat (only while idle) ----

    /** Points the engine at a new, empty chat [sessionId]. */
    internal fun bindFresh(sessionId: String, agent: AgentMode) {
        engine.history.clear()
        engine.sessionId = sessionId
        engine.tokenCount = 0
        engine.restoreTitle(null)
        // Without this the previous chat's run summaries survive into the
        // new session: the share card would promote the old conversation
        // and saveCurrentSession() would persist the stale list into the
        // new chat file.
        engine.restoreRunSummaries(emptyList())
        engine.sessionIsSample = false
        // A new chat never inherits the previous one's persona: it is picked
        // per chat, on the home screen this call is about to show.
        engine.sessionPersonaId = null
        engine.setupWorkingDir(create = false)
        engineAgent = agent
        bound(sessionId, emptyList(), 0)
    }

    /** Points the engine at the saved chat [session], shown as [transcript]. */
    internal fun bindSaved(session: ChatSession, agent: AgentMode, transcript: List<UiMessage>) {
        engine.history.clear()
        engine.history.addAll(session.messages)
        engine.sessionId = session.id
        engine.tokenCount = session.tokenCount
        engine.restoreTitle(if (session.isFallbackTitle()) null else session.title)
        engine.restoreRunSummaries(session.runSummaries)
        engine.sessionIsSample = session.isSample
        engine.sessionPersonaId = session.personaId
        engine.setupWorkingDir()
        engineAgent = agent
        bound(session.id, transcript, session.tokenCount)
    }

    private fun bound(sessionId: String, transcript: List<UiMessage>, tokens: Int) {
        nextTranscriptId = nextIdAfter(transcript)
        setState {
            it.copy(
                boundSessionId = sessionId,
                transcript = transcript,
                tokenCount = tokens,
                activity = null,
                subAgentRunning = null,
                subAgentCurrentAction = null
            )
        }
    }

    /**
     * Point [engine] at [view] (the session being viewed) so a run operates on
     * it. Only called while nothing else runs, so re-pointing the engine here
     * is safe. Reloads the session's LLM history from disk when the engine had
     * drifted to another session.
     */
    internal suspend fun bindForSend(view: ViewedChat) {
        val viewedId = view.sessionId
        if (viewedId != null && engine.sessionId != viewedId) {
            val saved = historyRepository.loadSession(viewedId)
            engine.history.clear()
            engine.sessionId = viewedId
            if (saved != null) {
                engine.history.addAll(saved.messages)
                engine.tokenCount = saved.tokenCount
                engine.restoreTitle(if (saved.isFallbackTitle()) null else saved.title)
                engine.restoreRunSummaries(saved.runSummaries)
                engine.sessionIsSample = saved.isSample
            } else {
                engine.tokenCount = 0
                engine.restoreTitle(null)
                engine.restoreRunSummaries(emptyList())
                engine.sessionIsSample = false
            }
            engine.setupWorkingDir()
        }
        engineAgent = view.agent
        engine.sessionPersonaId = view.personaId
        bound(engine.sessionId ?: return, view.messages, engine.tokenCount)
    }

    /**
     * Adds a bubble to the bound chat's transcript: the agent's output, or a
     * notice from the screen while the bound chat is the one being viewed.
     */
    internal fun appendTranscript(
        kind: MessageKind,
        text: String,
        imageBase64: String? = null,
        attachments: List<ComposerAttachment> = emptyList(),
        subAgentSteps: List<String> = emptyList(),
        reasoningContent: String? = null
    ) {
        if (kind == MessageKind.ERROR) runHadError = true
        setState {
            val message = UiMessage(
                id = nextTranscriptId++,
                kind = kind,
                text = text,
                imageBase64 = imageBase64,
                subAgentSteps = subAgentSteps,
                subAgentCollapsed = true,
                reasoningContent = reasoningContent,
                attachments = attachments
            )
            it.copy(transcript = it.transcript + message)
        }
    }

    /** Drops [sessionId]'s live token count, e.g. once the chat is deleted. */
    internal fun forgetLiveTokens(sessionId: String) {
        _liveTokenBySession.update { it - sessionId }
    }

    // ---- Runs ----

    /** Sends [text] with [attachments] in [view] and runs the agent on it. */
    internal fun send(view: ViewedChat, text: String, attachments: List<ComposerAttachment>, isVoice: Boolean) {
        currentRunIsVoice = isVoice
        agentJob = scope.launch {
            // Ensure the engine is bound to the session being viewed. After a
            // previous run finished while the user browsed elsewhere, the engine
            // may still point at that older session — reload the viewed one.
            bindForSend(view)
            val msg = buildUserMessage(text, attachments)
            engine.history += msg
            appendTranscript(MessageKind.USER, userDisplayText(text, msg, attachments), attachments = attachments)
            val runningId = engine.sessionId ?: return@launch
            executeRun(engineAgent, runningId)
        }
    }

    /**
     * Replaces the user message [targetId] in [view] with [text] and
     * [attachments] (null keeps the original ones), drops everything after it
     * and runs the agent again.
     */
    internal fun edit(view: ViewedChat, targetId: Long, text: String, attachments: List<ComposerAttachment>?) {
        currentRunIsVoice = false
        // Cancel any in-flight edit/run before overwriting the reference, so a
        // rapid second tap can't leave two coroutines truncating the same history.
        agentJob?.cancel()
        agentJob = scope.launch {
            // Re-check the busy guard inside the coroutine: a second invocation can
            // slip past the caller's synchronous check (isBusy only becomes true once
            // executeRun runs), so refuse rather than interleave two truncations.
            if (isRunning) return@launch
            bindForSend(view)
            val transcript = _state.value.transcript
            val target = transcript.firstOrNull { it.id == targetId && it.kind == MessageKind.USER }
                ?: return@launch
            val k = transcript.takeWhile { it.id != targetId }.count { it.kind == MessageKind.USER }
            // History/transcript desync guard (e.g. after compaction the transcript
            // is reset), so a stale target id bails without partial truncation.
            if (k >= userTurnStarts(engine.history).size) return@launch
            val kept = truncateHistoryAtTurn(engine.history, k, dropTurn = true)
            engine.history.clear()
            engine.history.addAll(kept)
            setState {
                it.copy(
                    transcript = transcript.take(transcript.indexOf(target)),
                    activity = null,
                    subAgentRunning = null,
                    subAgentCurrentAction = null
                )
            }
            // Never promote undone work on the share card.
            engine.restoreRunSummaries(emptyList())
            // A previously-sent document keeps its extracted text (the file grant is
            // long gone); a newly-picked one carries its own.
            val editAttachments = attachments ?: target.attachments
            val msg = buildUserMessage(text, editAttachments)
            engine.history += msg
            appendTranscript(
                MessageKind.USER,
                userDisplayText(text, msg, editAttachments),
                attachments = editAttachments
            )
            executeRun(engineAgent, engine.sessionId ?: return@launch)
        }
    }

    /**
     * Truncates [view] so the user message [targetId] becomes the last message.
     * Returns false when there was nothing to revert.
     */
    internal suspend fun revertTo(view: ViewedChat, targetId: Long): Boolean {
        // Cancel any in-flight edit first so a revert can't interleave with a
        // coroutine that is mid-truncation on the same engine history.
        agentJob?.cancel()
        if (isRunning) return false
        bindForSend(view)
        val transcript = _state.value.transcript
        val target = transcript.firstOrNull { it.id == targetId && it.kind == MessageKind.USER }
            ?: return false
        val k = transcript.takeWhile { it.id != targetId }.count { it.kind == MessageKind.USER }
        // History/transcript desync guard (e.g. after compaction the transcript
        // is reset), so a stale target id bails without partial truncation.
        if (k >= userTurnStarts(engine.history).size) return false
        val kept = truncateHistoryAtTurn(engine.history, k, dropTurn = false)
        engine.history.clear()
        engine.history.addAll(kept)
        // Re-derive the context readout from the truncated history, matching
        // the engine's own estimate units so the meter doesn't lie after revert.
        val tokens = kept.sumOf { it.textContent.length / 4 } + AgentEngine.PROMPT_OVERHEAD_TOKENS
        engine.tokenCount = tokens
        // Keep the drawer's live overlay in sync so the reverted session's
        // row doesn't keep showing the pre-truncation count.
        engine.sessionId?.let { sid -> _liveTokenBySession.update { it + (sid to tokens) } }
        setState {
            it.copy(
                transcript = transcript.take(transcript.indexOf(target) + 1),
                tokenCount = tokens,
                activity = null,
                subAgentRunning = null,
                subAgentCurrentAction = null
            )
        }
        // Never promote undone work on the share card.
        engine.restoreRunSummaries(emptyList())
        engine.saveCurrentSession()
        return true
    }

    fun stop() {
        agentJob?.cancel()
    }

    /** Busy-marking + agent run + NonCancellable cleanup. */
    private suspend fun executeRun(agent: AgentMode, runningId: String) {
        val runningTitle = _state.value.transcript.firstOrNull { it.kind == MessageKind.USER }
            ?.text?.take(30) ?: "New Chat"
        setState {
            it.copy(
                isBusy = true,
                runningSessionId = runningId,
                runningSessionTitle = runningTitle,
                backgroundHint = currentBackgroundHint(),
                foregroundControlStatus = null
            )
        }
        // Issue #105: hold a foreground slot for the run, so Android keeps the
        // process alive if the user leaves mid-task. Started here, while the user
        // who just sent the message is still in Gotcha.
        ChatRunService.start(app, runningChat(runningId))
        // Survives the process: if Android kills it mid-run, the next start says so.
        withContext(Dispatchers.IO) { runMarker.mark(runningId) }
        runHadError = false
        var stopped = false
        try {
            engine.run(agent)
        } catch (_: CancellationException) {
            stopped = true
            appendTranscript(MessageKind.ERROR, "Agent was interrupted by the user.")
            // The interrupt may have orphaned an assistant with tool_calls but no
            // matching tool results. Repair it in NonCancellable before the next
            // turn is built — otherwise the provider 400s every later request.
            withContext(NonCancellable) {
                engine.sanitizeLastOrphanedAssistant()
            }
        } finally {
            withContext(NonCancellable) {
                currentRunIsVoice = false
                // Belt-and-suspenders: an interrupt that slipped past the engine's
                // own sanitize still gets repaired here before persisting.
                engine.sanitizeLastOrphanedAssistant()
                engine.saveCurrentSession()
                notifyRunFinished(
                    sessionId = runningId,
                    outcome = when {
                        stopped -> RunOutcome.STOPPED
                        runHadError -> RunOutcome.FAILED
                        else -> RunOutcome.DONE
                    }
                )
                // After the finished notification is up, so in the background the
                // ongoing one is replaced by it rather than leaving a gap.
                ChatRunService.stop()
                withContext(Dispatchers.IO) { runMarker.clear() }
                setState {
                    it.copy(
                        isBusy = false,
                        runningSessionId = null,
                        runningSessionTitle = null,
                        backgroundHint = null,
                        activity = null,
                        subAgentRunning = null,
                        subAgentCurrentAction = null
                    )
                }
                agentJob = null
            }
        }
    }

    // ---- Screen → runner ----

    /**
     * From the Activity's onStart/onStop, through the ViewModel. [recreating] is
     * an onStop for a rotation or other configuration change, which is back
     * within a moment and should not raise a notification.
     */
    fun setForeground(foreground: Boolean, recreating: Boolean = false) {
        appInForeground = foreground
        // A question or confirmation left on screen is out of sight now; back
        // in the app, its dialog is showing again.
        when {
            foreground -> attentionNotifier.cancel()
            !recreating -> notifyNeedsInput()
        }
    }

    /**
     * The chat screen is gone for good (its ViewModel was cleared) while the run
     * may go on. A runtime-permission dialog needs that screen, so an ask still
     * waiting is answered "not granted" now rather than at its timeout. A
     * question or confirmation keeps waiting: its notification reopens Gotcha.
     */
    fun onUiDetached() {
        setState { it.copy(pendingPermission = null) }
        permissionGate?.complete(false)
        permissionGate = null
    }

    fun confirmPendingActions(approved: Boolean) {
        setState { it.copy(pendingConfirmation = null) }
        confirmationGate?.complete(approved)
        confirmationGate = null
    }

    fun submitAnswer(answer: String?) {
        setState { it.copy(pendingQuestion = null) }
        questionGate?.complete(answer ?: "")
        questionGate = null
    }

    /** The in-app dialog's answer to [awaitForegroundControl]. */
    fun answerForegroundControl(allowed: Boolean) {
        setState { it.copy(pendingForegroundControl = null) }
        foregroundControlGate?.complete(allowed)
        foregroundControlGate = null
    }

    /** The Activity's answer to [awaitPermissionGrant]: the system dialog's outcome. */
    fun onPermissionResult(granted: Boolean) {
        setState { it.copy(pendingPermission = null) }
        permissionGate?.complete(granted)
        permissionGate = null
    }

    /** A notification-permission grant mid-run: the hint on screen can now promise one. */
    fun refreshBackgroundHint() {
        setState { if (it.backgroundHint != null) it.copy(backgroundHint = currentBackgroundHint()) else it }
    }

    /** The screen switched chats: a reply to a voice-started run is no longer read aloud. */
    fun clearVoiceFlag() {
        currentRunIsVoice = false
    }

    // ---- Settings / speech ----

    /** Re-reads settings; call after the settings screen saves. */
    fun refreshSettings() {
        settings = settingsRepository.load()
        client = if (settings.isConfigured) {
            LLMClient(
                apiKey = settings.effectiveApiKey,
                baseUrl = settings.effectiveBaseUrl,
                model = settings.model,
                context = app,
                apiTimeoutSeconds = settings.apiTimeoutSeconds,
                onUnauthorized = { onSamosaUnauthorized() }
            )
        } else {
            null
        }
        ttsEngine.configureApi(settings.effectiveTtsBaseUrl, settings.effectiveTtsApiKey)
        setState { it.copy(isConfigured = settings.isConfigured) }
        observers.forEach { it.onSettingsChanged() }
    }

    /**
     * On a 401 while using Samosa AI, the JWT is expired/blacklisted: drop it so
     * the app returns to the unauthenticated state and prompts sign-in again.
     */
    fun onSamosaUnauthorized() {
        val usingSamosa = settings.provider == LlmProvider.SAMOSA_AI
        if (!usingSamosa) return
        settingsRepository.clearSamosaSession()
        settings = settingsRepository.load()
        client = null
        setState { it.copy(isConfigured = false) }
    }

    /** Speak the given text aloud using the configured TTS provider. */
    fun speak(text: String) {
        if (settings.ttsProvider == AudioProvider.NONE) return
        scope.launch {
            ttsEngine.stop()
            setState { it.copy(isSpeaking = true) }
            try {
                val language = settings.effectiveVoiceLanguage
                val defaultVoice = ttsModels
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
                setState { it.copy(isSpeaking = false) }
            }
        }
    }

    /** Stop any ongoing TTS speech output. */
    fun stopSpeaking() {
        ttsEngine.stop()
        setState { it.copy(isSpeaking = false) }
    }

    // ---- AgentEvents (engine → screen) ----

    override fun onUi(
        kind: MessageKind,
        text: String,
        imageBase64: String?,
        subAgentSteps: List<String>,
        reasoningContent: String?
    ) {
        appendTranscript(
            kind = kind,
            text = text,
            imageBase64 = imageBase64,
            subAgentSteps = subAgentSteps,
            reasoningContent = reasoningContent
        )
    }

    override fun onActivity(activity: String?) {
        setState { it.copy(activity = activity) }
        // Once per round: picks up the chat's title when the first save generates it.
        _state.value.runningSessionId?.let { ChatRunService.update(runningChat(it)) }
    }

    override fun onTokenCount(totalTokens: Int) {
        val engineId = engine.sessionId ?: return
        // Publish live to the drawer so the running session's row updates in
        // the same frame, without waiting for the disk save at end-of-round.
        _liveTokenBySession.update { it + (engineId to totalTokens) }
        setState { it.copy(tokenCount = totalTokens) }
        // Best-effort disk write so a crash mid-run doesn't lose the count.
        scope.launch { engine.saveCurrentSession() }
    }

    override fun onAssistantReply(text: String) {
        signalReplyArrived()
        val shouldRead = currentRunIsVoice ||
            (settings.autoReadReplies && settings.ttsProvider != AudioProvider.NONE)
        if (shouldRead && settings.ttsProvider != AudioProvider.NONE) {
            speak(text)
        }
        currentRunIsVoice = false
    }

    override fun onSubAgentUpdate(running: String?, currentAction: String?) {
        setState { it.copy(subAgentRunning = running, subAgentCurrentAction = currentAction) }
    }

    override fun onPermissionRequest(marker: String) {
        _permissionRequests.tryEmit(marker)
    }

    /**
     * A tool needs a runtime permission right now. The Activity collecting
     * [RunState.pendingPermission] explains why and raises the system dialog,
     * then answers through [onPermissionResult].
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
        setState { it.copy(activity = null, pendingPermission = permission) }

        val granted = withTimeoutOrNull(GATE_TIMEOUT_MS) { gate.await() } ?: false

        setState { it.copy(pendingPermission = null) }
        permissionGate = null
        return granted
    }

    /** Compaction dropped the LLM history; clear the transcript to match. */
    override fun onHistoryReset() {
        nextTranscriptId = 0
        setState { it.copy(transcript = emptyList()) }
    }

    /**
     * Pauses the run on the agent's question. Answering often means leaving
     * Gotcha first (to run a command in Termux, say), so the question waits
     * [QUESTION_TIMEOUT_MS] rather than the other gates' two minutes, and a
     * notification says so whenever Gotcha is out of sight (issue #108).
     */
    override suspend fun awaitQuestionAnswer(question: PendingQuestion): String {
        val gate = CompletableDeferred<String>()
        questionGate = gate
        setState { it.copy(activity = null, pendingQuestion = question) }
        if (!appInForeground) notifyNeedsInput()
        return try {
            withTimeoutOrNull(QUESTION_TIMEOUT_MS) { gate.await() } ?: ""
        } finally {
            attentionNotifier.cancel()
            setState { it.copy(pendingQuestion = null) }
            questionGate = null
        }
    }

    override suspend fun awaitConfirmation(toolNames: List<String>, description: String): Boolean {
        val gate = CompletableDeferred<Boolean>()
        confirmationGate = gate
        setState {
            it.copy(activity = null, pendingConfirmation = PendingConfirmation(toolNames, description))
        }
        if (!appInForeground) notifyNeedsInput()
        return try {
            withTimeoutOrNull(GATE_TIMEOUT_MS) { gate.await() } ?: false
        } finally {
            attentionNotifier.cancel()
            confirmationOverlay.dismiss()
            setState { it.copy(pendingConfirmation = null) }
            confirmationGate = null
        }
    }

    /**
     * The once-per-request ask before Gotcha controls another app (issue #98).
     * Out of the app the in-app dialog can't be seen, so the same question is
     * also drawn over whatever is on screen; either answer counts.
     */
    override suspend fun awaitForegroundControl(request: ForegroundControlRequest): Boolean {
        val gate = CompletableDeferred<Boolean>()
        foregroundControlGate = gate
        setState { it.copy(activity = null, pendingForegroundControl = request) }
        if (!appInForeground && confirmationOverlay.canShow()) {
            confirmationOverlay.show(
                summary = request.promptText(),
                onAllow = { gate.complete(true) },
                onDeny = { gate.complete(false) },
                title = request.title,
                allowLabel = ForegroundControlRequest.ALLOW_LABEL,
                denyLabel = ForegroundControlRequest.DENY_LABEL
            )
        }
        return try {
            withTimeoutOrNull(GATE_TIMEOUT_MS) { gate.await() } ?: false
        } finally {
            confirmationOverlay.dismiss()
            setState { it.copy(pendingForegroundControl = null) }
            foregroundControlGate = null
        }
    }

    override fun onForegroundControlChanged(active: Boolean, appLabel: String?) {
        if (active) {
            val text = ForegroundControlRequest.controllingMessage(appLabel)
            setState { it.copy(foregroundControlStatus = text) }
            foregroundControlIndicator.showControlling(text)
        } else {
            setState { it.copy(foregroundControlStatus = ForegroundControlRequest.DONE_MESSAGE) }
            // In the app the status line says it; outside, the card over their app does.
            if (appInForeground) {
                foregroundControlIndicator.dismiss()
            } else {
                foregroundControlIndicator.showDone(ForegroundControlRequest.DONE_MESSAGE)
            }
        }
    }

    override fun onScreenCaptureChrome(hide: Boolean) {
        foregroundControlIndicator.setCaptureHidden(hide)
    }

    // ---- Notifications ----

    /**
     * Tells the user, out of the app, that the run is paused on a question or
     * confirmation they can't see (issue #108). The foreground-control ask
     * needs none: it is drawn over whatever app is in front.
     */
    private fun notifyNeedsInput() {
        val state = _state.value
        val kind = when {
            state.pendingQuestion != null -> AttentionKind.QUESTION
            state.pendingConfirmation != null -> AttentionKind.CONFIRMATION
            else -> return
        }
        val sessionId = state.runningSessionId ?: engine.sessionId ?: return
        // Issue #100: a chat kept out of notifications is not named, nor is its question.
        val named = mayNameChat(sessionId)
        attentionNotifier.notify(
            sessionId = sessionId,
            kind = kind,
            chatTitle = engine.currentTitle(),
            question = state.pendingQuestion?.question,
            preview = settings.chatCompletionPreview,
            named = named
        )
    }

    /** Issue #100: whether a notification about the engine's chat [sessionId] may name it. */
    private fun mayNameChat(sessionId: String): Boolean =
        settings.notificationsMentionChats &&
            !localNotificationStore.isChatSensitive(sessionId, engine.sessionPersonaId)

    /**
     * The run in [sessionId] as its ongoing notification shows it: the chat's
     * title once one is generated, until then the start of the first message,
     * cut at a word.
     */
    private fun runningChat(sessionId: String): RunningChat {
        val title = engine.generatedTitle
            ?: engine.history.firstOrNull { it.role == "user" }?.textContent?.let(::shortTitle)
        return RunningChat(sessionId, title.takeIf { mayNameChat(sessionId) })
    }

    private fun currentBackgroundHint(): String = backgroundHintText(
        vibrate = settings.notifyVibrationEnabled,
        chime = settings.notifyChimeEnabled,
        notify = settings.chatCompletionNotificationsEnabled && completionNotifier.canPost()
    )

    /**
     * Buzz/chime as a reply lands. The chat screen has no spoken "I'm done"
     * unless auto-read is on, so without this a reply that arrives while the
     * user is elsewhere goes unnoticed.
     */
    private fun signalReplyArrived() {
        if (runHadError) {
            CompletionFeedback.error(app)
        } else {
            CompletionFeedback.replyArrived(
                context = app,
                vibrate = settings.notifyVibrationEnabled,
                chime = settings.notifyChimeEnabled
            )
        }
    }

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
        val reply = _state.value.transcript.lastOrNull {
            it.kind == MessageKind.ASSISTANT || it.kind == MessageKind.ERROR
        }?.text
        // Issue #100: a chat kept out of notifications, or chats not to be named
        // at all, get a notification that says only that a task finished.
        val named = mayNameChat(sessionId)
        val title = if (named) {
            ChatCompletionNotifier.notificationTitle(engine.currentTitle(), outcome)
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
            chatTitle = engine.currentTitle(),
            outcome = outcome,
            reply = reply,
            preview = settings.chatCompletionPreview,
            named = named,
            inboxEntryId = entryId
        )
    }

    // ---- Startup ----

    /**
     * Issue #105: the last run died with the process, most likely killed by
     * Android while Gotcha was in the background. Its chat gets a notice at the
     * end, its history is repaired so the chat can go on, and the inbox gets an
     * entry that opens it. Gotcha still starts on a fresh chat, as always.
     */
    private suspend fun reportInterruptedRun() {
        val sessionId = runMarker.interruptedSession() ?: return
        historyRepository.loadSession(sessionId)?.let { session ->
            // A legacy chat without a saved transcript is rebuilt from its history on
            // open; a lone notice would replace all of it.
            val shown = if (session.displayMessages.isEmpty()) {
                session.displayMessages
            } else {
                val nextId = session.displayMessages.maxOf { it.id } + 1
                session.displayMessages + UiMessage(nextId, MessageKind.ERROR, INTERRUPTED_NOTICE)
            }
            historyRepository.saveSession(
                session.copy(messages = closeOrphanedToolCalls(session.messages), displayMessages = shown),
                touch = false
            )
            val named = settings.notificationsMentionChats &&
                !localNotificationStore.isChatSensitive(sessionId, session.personaId)
            localNotificationStore.addEntry(
                category = NotificationCategory.TASK_FINISHED,
                title = if (named) "Interrupted: ${session.title}" else "Task interrupted",
                body = INTERRUPTED_INBOX_BODY,
                target = NotificationTarget.Chat(sessionId)
            )
            _inboxChanges.update { it + 1 }
        }
        withContext(Dispatchers.IO) { runMarker.clear() }
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

    companion object {
        const val GATE_TIMEOUT_MS = 120_000L

        /** How long a question waits; see [awaitQuestionAnswer]. */
        const val QUESTION_TIMEOUT_MS = 600_000L

        private const val MIGRATED_CHAT_DIRS_KEY = "migrated_chat_dirs_v1"

        /** Ends the chat of a run that died with the process; see [reportInterruptedRun]. */
        private const val INTERRUPTED_NOTICE = "This task was interrupted: Android closed Gotcha while it was " +
            "working, usually to free memory. Send a message to pick up where it left off."
        private const val INTERRUPTED_INBOX_BODY = "Android closed Gotcha while it was working on this task. " +
            "Tap to open the chat and pick up where it left off."

        @Volatile
        private var fallback: ChatRunner? = null

        /** The process's runner: [GotchaApp]'s, or one of its own under another Application. */
        fun of(app: Application): ChatRunner =
            (app as? GotchaApp)?.chatRunner ?: fallback ?: synchronized(this) {
                fallback ?: ChatRunner(app).also { fallback = it }
            }
    }
}

/** The next free id after [messages]. */
private fun nextIdAfter(messages: List<UiMessage>): Long = messages.maxOfOrNull { it.id }?.plus(1) ?: 0L

/**
 * True when [ChatSession.title] is still the legacy truncated-first-message
 * fallback rather than an LLM-generated title, so it's eligible to be
 * (re)generated next time the session is saved.
 */
internal fun ChatSession.isFallbackTitle(): Boolean {
    val fallback = messages.firstOrNull { it.role == "user" }?.textContent?.take(30)
    return title.isBlank() || title == fallback
}

/**
 * Builds the LLM user message for the given prompt and attachments: every
 * document's text joins the prompt in the first text part, and every image
 * follows as its own image part.
 */
internal fun buildUserMessage(text: String, attachments: List<ComposerAttachment>): ChatMessage {
    if (attachments.isEmpty()) return ChatMessage(role = "user", content = JsonPrimitive(text))
    val documents = attachments.filterIsInstance<ComposerAttachment.Document>().map {
        DocumentPart(it.attachment.name, it.attachment.mimeType, it.attachment.text, it.attachment.pageCount)
    }
    val images = attachments.filterIsInstance<ComposerAttachment.Image>().map { it.base64 }
    return attachmentsUserMessage(text, documents, images, imageFormat = "jpeg")
}

/**
 * The transcript label for a sent message: the prompt text, or a placeholder
 * when the message is attachment-only. Messages with documents show the
 * prompt rather than the extracted bodies; image-only messages show a
 * placeholder, never the whitespace text part sent to the model.
 */
internal fun userDisplayText(userText: String, msg: ChatMessage, attachments: List<ComposerAttachment>): String =
    when {
        attachments.isEmpty() -> msg.textContent
        attachments.none { it is ComposerAttachment.Document } -> userText.ifEmpty {
            if (attachments.size == 1) "(image attached)" else "(files attached)"
        }
        else -> userText.ifEmpty {
            if (attachments.size == 1) "(document attached)" else "(files attached)"
        }
    }
