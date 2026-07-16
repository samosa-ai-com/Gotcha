package com.gotcha.agent

import android.content.Context
import android.util.Log
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.ChatSession
import com.gotcha.data.Settings
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.LLMClient
import com.gotcha.llm.ToolCall
import com.gotcha.llm.visionUserMessage
import com.gotcha.tools.AgentMode
import com.gotcha.tools.AppNavigatorSession
import com.gotcha.tools.FileResolver
import com.gotcha.tools.ScreenPerception
import com.gotcha.tools.SubAgentSession
import com.gotcha.tools.ToolExecutor
import com.gotcha.tools.ToolRegistry
import com.gotcha.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Outcome of the sensitive-action confirmation step. */
private enum class ConfirmDecision { APPROVED, DENIED, TIMED_OUT }

/** Maps API/network failures to a short, user-readable message. */
internal fun friendlyAgentError(e: Exception): String = when {
    e is retrofit2.HttpException && e.code() == 401 ->
        "The API rejected the key (401). Check your API key in settings."
    e is retrofit2.HttpException ->
        "The API returned an error (HTTP ${e.code()}). ${e.message()}"
    e is java.io.IOException ->
        "Network problem: ${e.message ?: "could not reach the API"}. Check your connection."
    else -> "Something went wrong: ${e.message}"
}

/**
 * The core agent loop, extracted from ChatViewModel so it can run both inside
 * the in-app chat (ViewModel host) and inside a background service (voice-call
 * host). Owns the LLM-shaped history, session identity, tool execution and
 * special tool-result handling, history compaction, and session persistence.
 * All UI concerns flow through [AgentEvents].
 */
@Suppress("LargeClass", "TooManyFunctions")
class AgentEngine(
    private val appContext: Context,
    private val events: AgentEvents,
    private val historyRepository: ChatHistoryRepository,
    private val settingsProvider: () -> Settings,
    private val clientProvider: () -> LLMClient?,
    private val workingDirRoot: String = "/storage/emulated/0/Gotcha/chats",
    /** Supplies the current on-screen transcript to persist alongside history. */
    private val displayMessagesProvider: () -> List<UiMessage> = { emptyList() },
    /** Supplies the active agent mode to persist so it survives restarts. */
    private val agentModeProvider: () -> AgentMode? = { null }
) {

    /** LLM-shaped history (excludes the system prompt, which is prepended per call). */
    val history = mutableListOf<ChatMessage>()
    var sessionId: String? = null
    var tokenCount: Int = 0

    lateinit var toolExecutor: ToolExecutor
        private set

    private val json = Json { ignoreUnknownKeys = true }

    private val settings: Settings get() = settingsProvider()

    init {
        ScreenPerception.appContext = appContext
        toolExecutor = ToolExecutor(
            appContext,
            onTask = { description, prompt ->
                events.onSubAgentUpdate(description, "starting…")
                val steps = mutableListOf<SubAgentStepUi>()
                val subSession = SubAgentSession(
                    appContext = appContext,
                    toolExecutor = toolExecutor,
                    settings = settings,
                    description = description,
                    prompt = prompt,
                    onStep = { action, status, detail ->
                        steps.add(SubAgentStepUi(action, status, detail))
                        events.onSubAgentUpdate(
                            description,
                            when (status) {
                                "running" -> "→ $action"
                                "completed" -> if (detail.isNotBlank()) {
                                    "✓ $action → ${detail.take(60)}"
                                } else {
                                    "✓ $action"
                                }
                                else -> "$action: ${detail.take(60)}"
                            }
                        )
                    },
                    sessionId = sessionId?.let { "${it}_task" }
                )
                val output = subSession.run()
                val stepsEncoded = output.steps.joinToString("\n")
                events.onSubAgentUpdate(null, null)
                if (output.finalAnswer.startsWith("Task failed") || output.finalAnswer.startsWith("Task exceeded")) {
                    ToolResult.error(output.finalAnswer)
                } else {
                    ToolResult.ok("TASK_RESULT:$description:$stepsEncoded\n|||\n${output.finalAnswer}")
                }
            },
            onNavigateApp = { task ->
                events.onSubAgentUpdate("App Navigation", "starting…")
                val steps = mutableListOf<SubAgentStepUi>()
                val session = AppNavigatorSession(
                    appContext = appContext,
                    toolExecutor = toolExecutor,
                    settings = settings,
                    task = task,
                    onStep = { action, status, detail ->
                        steps.add(SubAgentStepUi(action, status, detail))
                        events.onSubAgentUpdate(
                            "App Navigation",
                            when (status) {
                                "running" -> "→ $action"
                                "completed" -> if (detail.isNotBlank()) {
                                    "✓ $action → ${detail.take(60)}"
                                } else {
                                    "✓ $action"
                                }
                                else -> "$action: ${detail.take(60)}"
                            }
                        )
                    },
                    sessionId = sessionId?.let { "${it}_nav" }
                )
                val output = session.run()
                val stepsEncoded = output.steps.joinToString("\n")
                events.onSubAgentUpdate(null, null)
                // Auto-return to our app after navigation completes
                try {
                    val pkg = appContext.packageName
                    val launchIntent = appContext.packageManager.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        )
                        appContext.startActivity(launchIntent)
                    }
                } catch (_: Exception) { }
                if (!output.success) {
                    ToolResult.error(output.finalAnswer)
                } else {
                    ToolResult.ok("TASK_RESULT:App Navigation:$stepsEncoded\n|||\n${output.finalAnswer}")
                }
            }
        )
    }

    fun workingDir(): java.io.File = java.io.File(workingDirRoot, sessionId ?: "unknown")

    /**
     * Sets up the per-chat working directory at {workingDirRoot}/{sessionId}/.
     *
     * Updates [FileResolver.WORKING_DIR_BASE] so file tools resolve relative
     * paths against this directory.
     */
    fun setupWorkingDir() {
        val dir = workingDir()
        if (!dir.exists()) dir.mkdirs()
        FileResolver.WORKING_DIR_BASE = dir.absolutePath
        Log.d(TAG, "setupWorkingDir: ${dir.absolutePath}")
    }

    suspend fun saveCurrentSession() {
        val id = sessionId ?: return
        val title = history.firstOrNull { it.role == "user" }?.textContent?.take(30) ?: "New Chat"
        historyRepository.saveSession(
            ChatSession(
                id = id,
                title = title,
                lastModified = System.currentTimeMillis(),
                messages = history.toList(),
                tokenCount = tokenCount,
                displayMessages = displayMessagesProvider(),
                agentMode = agentModeProvider()?.name
            )
        )
    }

    private suspend fun checkAndCompactHistory(llm: LLMClient) {
        val threshold = (settings.maxContextTokens * 0.8).toInt()
        if (tokenCount <= threshold) return

        // Pop the latest user message so it isn't lost in the compaction summary
        val lastMessage = history.lastOrNull()
        val preserveLast = if (lastMessage?.role == "user") {
            history.removeLast()
            lastMessage
        } else {
            null
        }

        events.onActivity("Compacting history…")
        val compactionSystem = ChatMessage(
            role = "system",
            content = JsonPrimitive(
                "You are an advanced context compaction agent. Your task is to compress the preceding " +
                    "conversation history into a highly dense, structured continuation summary. You must " +
                    "preserve critical context, decisions, and codebase states while eliminating " +
                    "conversational filler and repetitive tool logs.\n\n" +
                    "Generate a structured summary containing exactly the following sections:\n" +
                    "1. **Goal**: What is the ultimate objective of this engineering session?\n" +
                    "2. **Instructions & Constraints**: What specific guidelines, patterns, user " +
                    "preferences, or technical limitations have been established?\n" +
                    "3. **Discoveries & Architecture**: What have we learned about the codebase? Detail " +
                    "any symbol mappings, logic structures, or debugging conclusions.\n" +
                    "4. **Accomplished**: What changes have already been completely implemented, " +
                    "verified, or fixed?\n" +
                    "5. **Relevant Files**: Which files are currently being modified or are active in " +
                    "the workspace?\n\n" +
                    "CRITICAL: Do not lose technical specifics, user-stated constraints, or deep " +
                    "investigation states.\n\n" +
                    "Continue if you have next steps, or stop and ask for clarification if you are " +
                    "unsure how to proceed."
            )
        )

        val historyText = trimmedHistory().joinToString("\n\n") { msg ->
            val role = msg.role.uppercase()
            val text = msg.textContent.ifEmpty {
                if (!msg.toolCalls.isNullOrEmpty()) {
                    "Called tools: " + msg.toolCalls.joinToString(", ") { it.function.name }
                } else {
                    ""
                }
            }
            "[$role]: $text"
        }

        val requestMessage = ChatMessage(
            role = "user",
            content = JsonPrimitive(
                "Please summarize the following conversation history according to the system prompt instructions:\n\n$historyText"
            )
        )

        try {
            val response = llm.chat(listOf(compactionSystem, requestMessage))
            val summary = response.choices.firstOrNull()?.message?.textContent
            if (!summary.isNullOrBlank()) {
                history.clear()
                history.add(ChatMessage(role = "assistant", content = JsonPrimitive(summary)))
                if (preserveLast != null) {
                    history.add(preserveLast)
                }
                // The new token count is roughly the size of the summary + preserved message
                val newTokensApprox = (summary.length / 4) + ((preserveLast?.textContent?.length ?: 0) / 4)
                tokenCount = newTokensApprox
                events.onTokenCount(newTokensApprox)
                // Drop the pre-compaction on-screen transcript, then show the
                // compaction summary as the first bubble of the fresh transcript.
                events.onHistoryReset()
                // Show the compacted message in the chat UI as an assistant message
                events.onUi(MessageKind.ASSISTANT, "[System: History Compacted]\n$summary")
            } else if (preserveLast != null) {
                history.add(preserveLast)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // If compaction fails, restore the user message
            if (preserveLast != null) {
                history.add(preserveLast)
            }
        }
    }

    // The core agent loop: LLM call → tool dispatch → confirmation gates → repeat.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    suspend fun run(agent: AgentMode) {
        val llm = clientProvider() ?: return
        val sessionId = this.sessionId ?: "unknown"
        // KNOWN LIMITATION: WORKING_DIR_BASE is a process-wide global. We
        // re-assert it at run start and after every tool round so sequential
        // runs (in-app chat, voice call) always see their own directory, but
        // two agents running tool rounds concurrently race last-writer-wins.
        // The full fix is threading a cwd through ToolExecutor into the file
        // tools (FileResolver already accepts one); deferred for now.
        FileResolver.WORKING_DIR_BASE = workingDir().absolutePath
        checkAndCompactHistory(llm)
        // Anti-loop guard: if consecutive tool rounds produce the byte-identical
        // set of tool-call names + results (e.g. a tool that keeps returning the
        // same "service not running" error), the model is stuck retrying with no
        // new information. Break after [MAX_REPEATED_TOOL_ROUNDS] such rounds.
        var lastRoundSignature: String? = null
        var repeatedRoundCount = 0
        repeat(settings.maxToolRounds) { iteration ->
            if (iteration > 0) delay(INTER_CALL_DELAY_MS)
            events.onActivity("Thinking…")

            // Build message array optimized for prompt caching:
            //   1. Base environment block (fully static — no volatile fields)
            //   2. Full conversation history (images culled non-mutatively)
            //   3. Current timestamp (volatile — placed after cacheable prefix)
            //   4. Agent instructions at the tail
            // The [env block + history] prefix stays byte-identical across
            // iterations, maximising server-side KV-cache hits.
            val messages = buildList {
                add(baseEnvironmentBlock(agent))
                addAll(cullOldImages(trimmedHistory()))
                add(currentTimestampMessage())
                addAll(agentInstructionMessages(agent))
            }
            val response = try {
                llm.chat(messages, ToolRegistry.toolsForAgent(agent), sessionId = sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                events.onUi(MessageKind.ERROR, friendlyAgentError(e))
                return
            }

            response.usage?.totalTokens?.let {
                tokenCount = it
                events.onTokenCount(it)
            }

            val message = response.choices.firstOrNull()?.message
            if (message == null) {
                events.onUi(MessageKind.ERROR, "The model returned an empty response.")
                return
            }

            val toolCalls = message.toolCalls.orEmpty()
            if (toolCalls.isEmpty()) {
                val content = message.textContent
                // Preserve the real (possibly empty) content and carry any reasoning
                // through so the UI can render a reasoning-only bubble without the
                // placeholder "(no reply)" text.
                history += ChatMessage(
                    role = "assistant",
                    content = JsonPrimitive(content),
                    reasoningContent = message.reasoningContent
                )
                if (content.isNotEmpty() || !message.reasoningContent.isNullOrBlank()) {
                    events.onUi(
                        MessageKind.ASSISTANT,
                        content,
                        reasoningContent = message.reasoningContent
                    )
                }
                if (content.isNotEmpty()) {
                    events.onAssistantReply(content)
                }
                return
            }

            history += message
            if (message.hasText || !message.reasoningContent.isNullOrBlank()) {
                events.onUi(
                    MessageKind.ASSISTANT,
                    message.textContent,
                    reasoningContent = message.reasoningContent
                )
            }

            val decision = requestConfirmation(toolCalls)
            suspend fun executeToolCalls() {
                for (call in toolCalls) {
                    val result = when (decision) {
                        ConfirmDecision.APPROVED -> executeCall(call, agent)
                        ConfirmDecision.DENIED ->
                            ToolResult.error("The user declined to run '${call.function.name}'. Do not retry.")
                        ConfirmDecision.TIMED_OUT -> ToolResult.error(
                            "Confirmation for '${call.function.name}' timed out with no response. " +
                                "Do not retry automatically; tell the user to ask again when ready."
                        )
                    }

                    // Special-access markers: emit the marker and continue.
                    // Runtime permissions are pre-configured in Settings — the tool
                    // already returned an error message with guidance if one is missing.
                    val perm = result.needsPermission
                    if (perm != null && perm.startsWith("special:")) {
                        events.onPermissionRequest(perm)
                    }

                    if (result.success && result.message.startsWith("IMAGE_DATA:")) {
                        handleImageResult(call, result)
                    } else if (result.success && result.message.startsWith("BINARY_DATA:")) {
                        handleBinaryResult(call, result)
                    } else if (result.success && result.message.startsWith("QUESTION:")) {
                        handleQuestionResult(call, result)
                    } else if (result.success && result.message.startsWith("TASK_RESULT:")) {
                        handleTaskResult(call, result)
                    } else if (result.success && result.message.startsWith("CONFIRM_UNINSTALL:")) {
                        handleUninstallConfirm(call, result)
                    } else if (result.success && result.message.startsWith("CONFIRM_DELETE_ALARM:")) {
                        handleDeleteConfirm("CONFIRM_DELETE_ALARM:", "alarm", call, result)
                    } else if (result.success && result.message.startsWith("CONFIRM_DELETE_TIMER:")) {
                        handleDeleteConfirm("CONFIRM_DELETE_TIMER:", "timer", call, result)
                    } else if (result.success && result.message.startsWith("CONFIRM_DELETE_CALENDAR_EVENT:")) {
                        handleDeleteConfirm("CONFIRM_DELETE_CALENDAR_EVENT:", "calendar_event", call, result)
                    } else {
                        history += ChatMessage(
                            role = "tool",
                            content = JsonPrimitive(result.message),
                            toolCallId = call.id
                        )
                        events.onUi(
                            if (result.success) MessageKind.TOOL else MessageKind.ERROR,
                            "${call.function.name}: ${result.message}"
                        )
                    }
                    // Automatic screenshot injection: when read_screen succeeds,
                    // capture a compressed JPEG screenshot + UI hierarchy + coordinate
                    // contract and inject them as a vision user message so the LLM
                    // can "see" the screen alongside structured element data.
                    if (result.success && call.function.name == "read_screen") {
                        injectReadScreenObservation()
                    }
                    // read_screen_raw: full-resolution PNG screenshot for visual detail.
                    // Also saves the raw bytes to the working directory as a file.
                    if (result.success && call.function.name == "read_screen_raw") {
                        injectFullResScreenshot(result)
                    }
                    // Special-access markers: emit and continue (no wait needed since they open Settings)
                    if (perm != null && perm.startsWith("special:")) {
                        events.onPermissionRequest(perm)
                    }
                }
            }
            val historySizeBeforeTools = history.size
            executeToolCalls()
            saveCurrentSession()
            // Re-assert after each round; a concurrent engine may have moved it.
            FileResolver.WORKING_DIR_BASE = workingDir().absolutePath

            // Anti-loop guard: signature = tool-call names + the tool result text
            // appended this round. Identical signatures across consecutive rounds
            // mean the model is retrying the same failing action with no progress.
            val roundToolResults = history.drop(historySizeBeforeTools)
                .filter { it.role == "tool" }
                .joinToString("\n") { it.textContent }
            val roundSignature = toolCalls.joinToString(",") { "${it.function.name}:${it.function.arguments}" } + "|" + roundToolResults
            if (roundSignature == lastRoundSignature) {
                repeatedRoundCount++
                if (repeatedRoundCount >= MAX_REPEATED_TOOL_ROUNDS) {
                    events.onUi(
                        MessageKind.ERROR,
                        "Stopped: the same tool action kept returning the same result " +
                            "($repeatedRoundCount times in a row) with no progress. " +
                            "Check that the required service/permission is available, then try again."
                    )
                    return
                }
            } else {
                repeatedRoundCount = 0
                lastRoundSignature = roundSignature
            }
        }
        events.onUi(
            MessageKind.ERROR,
            "Stopped after ${settings.maxToolRounds} tool rounds to avoid an infinite loop."
        )
    }

    /**
     * Sensitive-action confirmation is disabled. Permissions are pre-configured
     * in Settings → Permissions. Only destructive tools (e.g. uninstall_app)
     * have their own dedicated confirmation flow via [executeCall].
     */
    private fun requestConfirmation(@Suppress("UNUSED_PARAMETER") toolCalls: List<ToolCall>): ConfirmDecision {
        return ConfirmDecision.APPROVED
    }

    /**
     * Handles a tool result that contains an image (prefixed with IMAGE_DATA:).
     * Injects the image as a vision user message so the LLM can "see" it,
     * and stores a clean text-only tool result in history for display.
     *
     * Format: IMAGE_DATA:<mime>:<width>x<height>:<bytes>:<filename>:<base64>
     */
    private fun handleImageResult(call: ToolCall, result: ToolResult) {
        val parts = result.message.split(":", limit = 6)
        if (parts.size < 6) {
            history += ChatMessage(
                role = "tool", content = JsonPrimitive("Failed to parse image data."),
                toolCallId = call.id
            )
            events.onUi(MessageKind.ERROR, "${call.function.name}: Failed to parse image data.")
            return
        }
        val mime = parts[1]
        val dimensions = parts[2]
        val bytesStr = parts[3]
        val filename = parts[4]
        val base64 = parts[5]
        val fileSize = try { bytesStr.toLong() } catch (_: Exception) { 0L }
        val sizeDisplay = when {
            fileSize >= 1024 * 1024 -> "${fileSize / (1024 * 1024)} MB"
            fileSize >= 1024 -> "${fileSize / 1024} KB"
            else -> "$fileSize B"
        }

        // Inject vision user message so the model "sees" the image
        val format = mime.substringAfter("image/")
        val visionMsg = visionUserMessage(
            "The assistant read an image file: $filename ($dimensions, $sizeDisplay).",
            base64,
            format
        )
        history.add(visionMsg)

        // Store a clean text-only tool result for display/conversation context
        history += ChatMessage(
            role = "tool",
            content = JsonPrimitive("Read image: $filename ($dimensions, $sizeDisplay)"),
            toolCallId = call.id
        )
        events.onUi(MessageKind.TOOL, "${call.function.name}: Read image $filename ($dimensions)")
    }

    /**
     * Handles a tool result that contains binary data (prefixed with BINARY_DATA:).
     * Format: BINARY_DATA:<mime>:<bytes>:<filename>:<base64>
     * Stores a text summary in history; the raw base64 is available if needed.
     */
    private fun handleBinaryResult(call: ToolCall, result: ToolResult) {
        val body = result.message.removePrefix("BINARY_DATA:")
        val parts = body.split(":", limit = 4)
        if (parts.size < 4) {
            history += ChatMessage(
                role = "tool", content = JsonPrimitive("Failed to parse binary data."),
                toolCallId = call.id
            )
            return
        }
        val mime = parts[0]
        val bytesStr = parts[1]
        val filename = parts[2]
        val fileSize = try { bytesStr.toLong() } catch (_: Exception) { 0L }
        val sizeDisplay = when {
            fileSize >= 1024 * 1024 -> "${fileSize / (1024 * 1024)} MB"
            fileSize >= 1024 -> "${fileSize / 1024} KB"
            else -> "$fileSize B"
        }
        history += ChatMessage(
            role = "tool",
            content = JsonPrimitive("Read binary file: $filename ($mime, $sizeDisplay)"),
            toolCallId = call.id
        )
        events.onUi(MessageKind.TOOL, "${call.function.name}: Read $filename ($mime)")
    }

    /**
     * Handles a tool result that contains a question (prefixed with QUESTION:).
     * Delegates to the host via [AgentEvents.awaitQuestionAnswer] and stores
     * the answer as the tool result.
     *
     * Format: QUESTION:<question>\n--OPTIONS--\nopt1\nopt2\n--ALLOW_CUSTOM--\ntrue
     */
    private suspend fun handleQuestionResult(call: ToolCall, result: ToolResult) {
        val pending = parseQuestionBody(result.message.removePrefix("QUESTION:"))
        val answer = events.awaitQuestionAnswer(pending)

        val displayAnswer = answer.ifBlank { "(no response)" }
        events.onUi(MessageKind.TOOL, "${call.function.name}: User answered: $displayAnswer")
        history += ChatMessage(
            role = "tool",
            content = JsonPrimitive("The user answered: $displayAnswer"),
            toolCallId = call.id
        )
    }

    /**
     * Handles a tool result prefixed with CONFIRM_UNINSTALL:label:package.
     * Asks the host for confirmation, and if approved, executes the actual uninstall.
     */
    private suspend fun handleUninstallConfirm(call: ToolCall, result: ToolResult) {
        val body = result.message.removePrefix("CONFIRM_UNINSTALL:")
        val parts = body.split(":", limit = 2)
        if (parts.size < 2) {
            history += ChatMessage(
                role = "tool", content = JsonPrimitive("Failed to parse uninstall confirmation."),
                toolCallId = call.id
            )
            events.onUi(MessageKind.ERROR, "${call.function.name}: Failed to parse uninstall confirmation.")
            return
        }
        val label = parts[0]
        val pkg = parts[1]
        Log.d(TAG, "handleUninstallConfirm: label=$label pkg=$pkg")
        val description = "Request to uninstall \"$label\". After you approve, a system dialog will " +
            "open — you must tap \"OK\" in that dialog to complete the removal. This action is irreversible."
        val approved = events.awaitConfirmation(listOf("uninstall_app"), description)

        if (approved) {
            val actualResult = toolExecutor.executeUninstall(pkg)
            history += ChatMessage(
                role = "tool",
                content = JsonPrimitive(actualResult.message),
                toolCallId = call.id
            )
            events.onUi(
                if (actualResult.success) MessageKind.TOOL else MessageKind.ERROR,
                "Uninstalling $label: ${actualResult.message}"
            )
        } else {
            val msg = "User declined to uninstall $label."
            history += ChatMessage(
                role = "tool",
                content = JsonPrimitive(msg),
                toolCallId = call.id
            )
            events.onUi(MessageKind.TOOL, "${call.function.name}: $msg")
        }
    }

    private suspend fun handleDeleteConfirm(confirmPrefix: String, kind: String, call: ToolCall, result: ToolResult) {
        val body = result.message.removePrefix(confirmPrefix)
        val parts = body.split(":", limit = 2)
        val id = parts[0]
        val label = parts.getOrElse(1) { kind }
        val description = "Request to delete $kind \"$label\" (#$id). This action is irreversible."
        val approved = events.awaitConfirmation(listOf("delete_$kind"), description)

        val actualResult = if (approved) {
            when (kind) {
                "alarm" -> toolExecutor.executeDeleteAlarm(id.toLong())
                "timer" -> toolExecutor.executeDeleteTimer(id.toLong())
                "calendar_event" -> toolExecutor.executeDeleteCalendarEvent(id.toLong())
                else -> return
            }
        } else {
            null
        }

        val msg = if (actualResult != null) {
            actualResult.message
        } else {
            "User declined to delete $kind $label."
        }
        history += ChatMessage(role = "tool", content = JsonPrimitive(msg), toolCallId = call.id)
        events.onUi(
            if (actualResult?.success != false) MessageKind.TOOL else MessageKind.ERROR,
            "${call.function.name}: $msg"
        )
    }

    /**
     * Handles a tool result prefixed with TASK_RESULT:description:steps|||answer.
     * Shows a collapsible SUBAGENT bubble with intermediate steps and renders
     * the final answer with markdown.
     */
    private fun handleTaskResult(call: ToolCall, result: ToolResult) {
        val body = result.message.removePrefix("TASK_RESULT:")
        val colon = body.indexOf(':')
        if (colon < 0) return
        val description = body.substring(0, colon)
        val payload = body.substring(colon + 1)
        val marker = "\n|||\n"
        val splitIdx = payload.indexOf(marker)
        val steps: List<String>
        val answer: String
        if (splitIdx >= 0) {
            steps = payload.substring(0, splitIdx).split("\n").filter { it.isNotBlank() }
            answer = payload.substring(splitIdx + marker.length)
        } else {
            steps = emptyList()
            answer = payload
        }
        // Show as a collapsible SUBAGENT bubble in the UI
        events.onUi(MessageKind.SUBAGENT, answer, subAgentSteps = steps)
        // Store in LLM history with steps embedded for persistence
        val stepsBlock = if (steps.isNotEmpty()) {
            "\n── Steps ──\n" + steps.joinToString("\n") + "\n── Result ──\n"
        } else {
            ""
        }
        history += ChatMessage(
            role = "tool",
            content = JsonPrimitive(
                "SUBAGENT_STEPS:$description" +
                    stepsBlock +
                    answer
            ),
            toolCallId = call.id
        )
    }

    private suspend fun executeCall(call: ToolCall, agent: AgentMode): ToolResult {
        events.onActivity("Running: ${call.function.name}…")
        val args: JsonObject = try {
            json.decodeFromString(JsonObject.serializer(), call.function.arguments.ifBlank { "{}" })
        } catch (_: Exception) {
            return ToolResult.error("Malformed tool arguments: ${call.function.arguments.take(200)}")
        }
        return toolExecutor.execute(call.function.name, args, agent)
    }

    /**
     * Environment block sent as the first system message.
     * Fully static within a session — no volatile fields like timestamps.
     * Only "Active agent: X" differs between Monitor and Operator.
     * The server KV cache for this prefix is preserved across all calls.
     */
    private fun baseEnvironmentBlock(agent: AgentMode): ChatMessage {
        return ChatMessage(role = "system", content = JsonPrimitive(buildEnvironmentString(agent)))
    }

    /**
     * A separate system message carrying only the current date/time.
     * Placed after the conversation history so the [baseEnvironmentBlock] +
     * history prefix stays in the KV cache while the timestamp is always fresh.
     */
    private fun currentTimestampMessage(): ChatMessage {
        val dateTime = java.text.SimpleDateFormat(
            "EEE MMM dd yyyy, HH:mm:ss 'UTC'Z",
            java.util.Locale.US
        ).apply { timeZone = java.util.TimeZone.getDefault() }.format(java.util.Date())
        return ChatMessage(role = "system", content = JsonPrimitive("Current date/time: $dateTime"))
    }

    /**
     * Agent-specific core prompt + system-reminder, sent at the tail of the
     * message array (right before the latest user message).  Placing these
     * after the conversation history ensures the [baseEnvironmentBlock] +
     * history prefix stays in the KV cache when the user switches agents.
     */
    private fun agentInstructionMessages(agent: AgentMode): List<ChatMessage> {
        val core = when (agent) {
            AgentMode.MONITOR ->
                "You are Monitor, a read-only AI assistant running on the user's Android phone. " +
                    "You can inspect, read, and query the device, but you CANNOT create, modify, or " +
                    "delete anything. You control the device only through the provided tools; never " +
                    "invent tool names or capabilities. If a tool reports a missing permission, " +
                    "explain what to grant and ask again. Use the sleep tool to pause and wait " +
                    "between operations. Keep replies short and conversational."
            AgentMode.OPERATOR ->
                "You are Operator, an AI assistant running on the user's Android phone. " +
                    "You can inspect, read, query, create, modify, and delete on the device. " +
                    "You control the device only through the provided tools; never invent tool " +
                    "names or capabilities. If a tool reports a missing permission, explain what " +
                    "to grant and ask again. After changing device state, confirm what was done. " +
                    "Use the sleep tool to pause and wait between operations (e.g. wait for " +
                    "a recording to finish before reading it). " +
                    "Use the task tool to delegate complex multi-step operations to the General " +
                    "sub-agent. Prefer delegation whenever a task involves many tool calls whose " +
                    "intermediate steps are not important — only the final result matters. " +
                    "For example: organizing files, gathering information from multiple sources, " +
                    "or performing a series of device checks. The sub-agent runs in the foreground; " +
                    "you will receive its complete report when done. " +
                    "Use the navigate_app tool to delegate app interaction tasks to the App " +
                    "Navigator sub-agent. For example: opening an app, searching for something, " +
                    "scrolling through results, or reading on-screen information. The navigator " +
                    "will look at the screen, tap, swipe, and type step by step. " +
                    "You will receive its complete report when done. " +
                    "Keep replies short and conversational. Be careful with destructive actions.\n" +
                    "If the accessibility service is enabled, you have the ability to control any app on the device.\n" +
                    "When using uninstall_app: after calling it, the system will open a dialog. " +
                    "Tell the user to tap OK in the system dialog to finish the uninstall. " +
                    "Do NOT attempt to uninstall via shell commands (run_command, run_root_command) — " +
                    "use uninstall_app only."
        }
        val reminder = when (agent) {
            AgentMode.MONITOR ->
                "\n\n<system-reminder>\n" +
                    "You are in MONITOR (read-only) mode. You are STRICTLY FORBIDDEN from making " +
                    "any changes to the device — no writing files, no calling, no sending messages, " +
                    "no dismissing notifications, no UI automation (tap/swipe/input), no device " +
                    "admin actions, no firewall changes, and no shell/root commands. You may ONLY " +
                    "inspect, list, read, and observe. This constraint overrides all other instructions.\n" +
                    "</system-reminder>"
            AgentMode.OPERATOR ->
                "\n\n<system-reminder>\n" +
                    "You are in OPERATOR mode. You are permitted to inspect, create, modify, and " +
                    "delete on the device using the provided tools. Be careful with destructive " +
                    "actions — confirm with the user when the result seems significant.\n" +
                    "</system-reminder>"
        }
        return listOf(
            ChatMessage(role = "system", content = JsonPrimitive(core + reminder))
        )
    }

    /**
     * Builds the environment info string with device info, agent mode,
     * and service statuses (PRD §14.1).
     *
     * The date/time is intentionally excluded — it is provided via
     * [currentTimestampMessage] placed after the conversation history
     * so that this block stays fully static and the server-side KV cache
     * for the [env block + history] prefix is preserved across calls.
     */
    private fun buildEnvironmentString(agent: AgentMode): String {
        val app = appContext
        val pm = app.packageManager
        val versionName = try {
            pm.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }

        val accEnabled = try {
            val expected = "${app.packageName}/com.gotcha.service.GotchaAccessibilityService"
            val enabled = android.provider.Settings.Secure.getString(
                app.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabled.contains(expected, ignoreCase = true)
        } catch (_: Exception) { false }

        val notifEnabled = try {
            val expected = app.packageName
            val enabled = android.provider.Settings.Secure.getString(
                app.contentResolver, "enabled_notification_listeners"
            ) ?: ""
            enabled.contains(expected, ignoreCase = true)
        } catch (_: Exception) { false }

        val deviceAdmin = try {
            val dpm = app.getSystemService(android.app.admin.DevicePolicyManager::class.java)
            dpm?.isAdminActive(
                android.content.ComponentName(app, com.gotcha.service.GotchaDeviceAdminReceiver::class.java)
            ) ?: false
        } catch (_: Exception) { false }

        val vpnActive = try {
            val cm = app.getSystemService(
                android.content.Context.CONNECTIVITY_SERVICE
            ) as? android.net.ConnectivityManager
            cm?.getNetworkCapabilities(cm.activeNetwork)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) ?: false
        } catch (_: Exception) { false }

        return buildString {
            appendLine("You are powered by the model named ${settings.model}.")
            appendLine("Here is some useful information about the environment you are running in:")
            appendLine("<env>")
            appendLine("  Device model: ${android.os.Build.MODEL} (${android.os.Build.MANUFACTURER})")
            appendLine(
                "  Android version: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
            )
            appendLine("  Active agent: ${agent.name}")
            appendLine("  Accessibility service enabled: ${if (accEnabled) "yes" else "no"}")
            appendLine("  Notification listener enabled: ${if (notifEnabled) "yes" else "no"}")
            appendLine("  Device admin active: ${if (deviceAdmin) "yes" else "no"}")
            appendLine("  VPN active: ${if (vpnActive) "yes" else "no"}")
            appendLine("  App version: $versionName")
            appendLine("  Working directory: ${FileResolver.WORKING_DIR_BASE}")
            appendLine("  (Relative paths in read_file/write_file/list_files resolve against the working directory.)")
            append("</env>")
        }
    }

    /** Trims history just in case compaction failed and we strictly need to fit it without splitting tool pairs. */
    private fun trimmedHistory(): List<ChatMessage> {
        // Fallback approximation since exact token counts of subsets are unknown
        val maxTokens = settings.maxContextTokens
        var currentTokens = 0
        var start = history.size - 1

        while (start >= 0) {
            currentTokens += history[start].textContent.length / 4
            if (currentTokens > maxTokens) {
                start++
                break
            }
            start--
        }

        if (start < 0) start = 0

        // Skip leading tool messages so we don't have an orphaned tool result
        while (start < history.size && history[start].role == "tool") start++

        val trimmed = history.subList(start, history.size)
        // Many LLM APIs strictly require history to start with a user message.
        // If our truncation or compaction leaves an assistant message first, prepend a dummy user message.
        if (trimmed.isNotEmpty() && trimmed.first().role == "assistant") {
            val note = ChatMessage(
                role = "user",
                content = JsonPrimitive("[System Note: Conversation context restored from previous turn]")
            )
            return listOf(note) + trimmed
        }
        return trimmed
    }

    /**
     * Capture a screenshot via the `screencap -p` shell command, compressed as JPEG.
     * Returns a [ScreenPerception.CompressedScreenshot] with base64 data, or null on failure.
     */
    private suspend fun captureCompressedScreenshot(
        drawGrid: Boolean = true
    ): ScreenPerception.CompressedScreenshot? {
        val saveDir = java.io.File(FileResolver.WORKING_DIR_BASE, "debug_screenshots")
        return ScreenPerception.compressScreenshot(drawGrid = drawGrid, saveDir = saveDir)
    }

    /**
     * Capture a full-resolution screenshot (uncompressed PNG).
     * Used by read_screen_raw for maximum visual detail.
     */
    private suspend fun captureFullResScreenshot(): ScreenPerception.CompressedScreenshot? {
        val saveDir = java.io.File(FileResolver.WORKING_DIR_BASE, "debug_screenshots")
        return ScreenPerception.compressScreenshot(
            maxDimension = 0,
            drawGrid = false,
            format = android.graphics.Bitmap.CompressFormat.PNG,
            quality = 100,
            saveDir = saveDir
        )
    }

    /**
     * Captures a compressed screenshot + UI hierarchy after a successful
     * read_screen call and injects them as a vision user message so the LLM
     * can "see" the screen alongside structured element data.
     */
    private suspend fun injectReadScreenObservation() {
        android.util.Log.d(
            "ScreenCapture",
            "read_screen auto-injection: calling captureCompressedScreenshot()"
        )
        val uiTree = ScreenPerception.buildUiHierarchyText()
        val screenshot = captureCompressedScreenshot()
        android.util.Log.d(
            "ScreenCapture",
            "read_screen auto-injection: screenshot=${screenshot != null}"
        )
        if (screenshot != null) {
            val observationText = ScreenPerception.buildObservationText(screenshot, uiTree)
            val visionMsg = visionUserMessage(observationText, screenshot.base64, "jpeg")
            history.add(visionMsg)
            events.onUi(MessageKind.ASSISTANT, "[Screenshot captured for visual context]")
            android.util.Log.d(
                "ScreenCapture",
                "read_screen auto-injection: vision message added, base64 length=${screenshot.base64.length}"
            )
        } else {
            android.util.Log.e(
                "ScreenCapture",
                "read_screen auto-injection: screenshot was null — no vision message added"
            )
            // If MediaProjection consent hasn't been granted, request it
            if (ScreenPerception.mediaProjectionResultData == null) {
                events.onPermissionRequest("special:screenshot_consent")
            }
        }
    }

    /**
     * Captures a full-resolution PNG screenshot after read_screen_raw,
     * saves it to the working directory, and injects it as a vision message.
     */
    private suspend fun injectFullResScreenshot(result: ToolResult) {
        val screenshot = captureFullResScreenshot() ?: return
        val screenText = result.message.removePrefix("read_screen_raw:").take(500)
        // Save screenshot to working directory
        val savedPath = try {
            val ts = java.text.SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                java.util.Locale.US
            ).format(java.util.Date())
            val dir = java.io.File(FileResolver.WORKING_DIR_BASE)
            if (!dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, "screenshot_$ts.png")
            val rawBytes = android.util.Base64.decode(
                screenshot.base64,
                android.util.Base64.DEFAULT
            )
            file.writeBytes(rawBytes)
            file.absolutePath
        } catch (_: Exception) {
            null
        }
        val pathNote = if (savedPath != null) "\n\nSaved to: $savedPath" else ""
        val visionMsg = visionUserMessage(
            "Screen text:\n$screenText\n\nThe assistant captured a " +
                "full-resolution screenshot for visual detail.$pathNote",
            screenshot.base64,
            screenshot.format
        )
        history.add(visionMsg)
        events.onUi(
            MessageKind.ASSISTANT,
            "[Full-resolution screenshot captured]${if (savedPath != null) " → $savedPath" else ""}"
        )
    }

    /**
     * Returns a copy of [messages] with old vision messages replaced by
     * text-only versions. Only the 2 most recent vision messages keep their
     * image data. The original list (and [history]) is NOT modified,
     * so the prefix sent in previous iterations stays cache-stable.
     */
    private fun cullOldImages(messages: List<ChatMessage>): List<ChatMessage> {
        val imageMsgIndices = messages.indices.filter { i ->
            val content = messages[i].content
            content is JsonArray && content.any { part ->
                part.jsonObject["type"]?.jsonPrimitive?.content == "image_url"
            }
        }
        val toCull = imageMsgIndices.dropLast(2).toSet()
        if (toCull.isEmpty()) return messages

        return messages.mapIndexed { idx, msg ->
            if (idx in toCull) {
                msg.copy(
                    content = JsonPrimitive(
                        "[Previous screen observation removed to save context. Only the 2 most recent are retained.]"
                    )
                )
            } else {
                msg
            }
        }
    }

    companion object {
        private const val TAG = "Gotcha"
        private const val INTER_CALL_DELAY_MS = 400L

        /**
         * How many consecutive tool rounds with a byte-identical
         * (tool names + results) signature are tolerated before the agent loop
         * bails out. Catches "service not running" / dead-URL retry storms.
         */
        private const val MAX_REPEATED_TOOL_ROUNDS = 3

        /**
         * Parses the body of a QUESTION: tool result into a [PendingQuestion].
         * Format: <question>\n--OPTIONS--\nopt1\nopt2\n--ALLOW_CUSTOM--\ntrue
         */
        fun parseQuestionBody(body: String): PendingQuestion {
            val question: String
            val options: List<String>
            val allowCustom: Boolean

            val optionsMarker = "\n--OPTIONS--\n"
            val customMarker = "\n--ALLOW_CUSTOM--\n"

            if (body.contains(optionsMarker)) {
                val before = body.substringBefore(optionsMarker)
                val after = body.substringAfter(optionsMarker)
                question = before
                if (after.contains(customMarker)) {
                    options = after.substringBefore(customMarker).split("\n").filter { it.isNotBlank() }
                    allowCustom = after.substringAfter(customMarker).trim().toBooleanStrictOrNull() ?: true
                } else {
                    options = after.split("\n").filter { it.isNotBlank() }
                    allowCustom = true
                }
            } else {
                question = body.substringBefore(customMarker)
                allowCustom = body.substringAfter(customMarker).trim().toBooleanStrictOrNull() ?: true
                options = emptyList()
            }
            return PendingQuestion(question, options, allowCustom)
        }
    }
}
