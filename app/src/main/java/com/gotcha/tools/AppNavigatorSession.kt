package com.gotcha.tools

import android.content.Context
import com.gotcha.agent.AgentEngine
import com.gotcha.data.Settings
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.ChatResponse
import com.gotcha.llm.LLMClient
import com.gotcha.llm.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class AppNavigatorOutput(
    val finalAnswer: String,
    val steps: List<String>,
    val success: Boolean = true
)

class AppNavigatorSession(
    private val appContext: Context,
    private val toolExecutor: ToolExecutor,
    private val settings: Settings,
    private val task: String,
    private val onStep: (action: String, status: String, detail: String) -> Unit = { _, _, _ -> },
    private val sessionId: String? = null,
    private val onCaptureChrome: (hide: Boolean) -> Unit = { },
    private val onScreenReadDone: () -> Unit = { }
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val maxSteps = settings.maxNavigationToolCalls
    private val actionLog = mutableListOf<String>()

    suspend fun run(): AppNavigatorOutput {
        val model = settings.navigatorModel.ifEmpty { settings.model }
        val llmClient = LLMClient(
            apiKey = settings.effectiveApiKey,
            baseUrl = settings.effectiveBaseUrl,
            model = model,
            context = appContext,
            apiTimeoutSeconds = settings.apiTimeoutSeconds
        )
        val navTools = ToolRegistry.toolsForNavigator()

        var lastUserContent: JsonElement? = null
        var llmError: Throwable? = null
        var stepsRun = 0
        for (step in 1..maxSteps) {
            stepsRun = step
            onStep("Analyzing screen", "running", "")

            // 1. Capture fresh perception with grid overlay
            val uiTree = ScreenPerception.buildUiHierarchyText()
            val saveDir = com.gotcha.data.GotchaStorage.subdir(
                java.io.File(FileResolver.WORKING_DIR_BASE),
                com.gotcha.data.GotchaStorage.Kind.DEBUG
            )
            val screenshot = try {
                onCaptureChrome(true)
                delay(AgentEngine.SCREEN_CAPTURE_SETTLE_MS) // chrome vanishes before the frame is captured
                ScreenPerception.compressScreenshot(drawGrid = true, saveDir = saveDir)
            } finally {
                onCaptureChrome(false)
            }
            onScreenReadDone()

            // 2. Build single-turn prompt
            val actionLogText = actionLog.joinToString("\n") { "  $it" }.ifEmpty { "  (none yet)" }
            val userContent = buildUserContent(screenshot, uiTree, actionLogText)
            lastUserContent = userContent

            // 3. Single-turn LLM call with navigation tools
            val response: ChatResponse = try {
                llmClient.chat(
                    messages = listOf(
                        ChatMessage(role = "system", content = JsonPrimitive(NAVIGATOR_SYSTEM_PROMPT)),
                        ChatMessage(role = "user", content = userContent)
                    ),
                    tools = navTools,
                    sessionId = sessionId
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                actionLog.add("Step $step: LLM error — ${e.message}")
                llmError = e
                break
            }

            val msg = response.choices.firstOrNull()?.message
            if (msg == null) {
                actionLog.add("Step $step: LLM returned an empty response")
                llmError = IllegalStateException("LLM returned an empty response")
                break
            }
            val toolCalls = msg.toolCalls.orEmpty()

            // 4. Check for completion
            if (toolCalls.isEmpty()) {
                val content = msg.textContent.ifBlank { "(no reply)" }
                actionLog.add("Step $step: ✓ $content")
                return AppNavigatorOutput(finalAnswer = content, steps = actionLog.toList())
            }

            val completeCall = toolCalls.firstOrNull { it.function.name == "ask_final_answer" }
            if (completeCall != null) {
                val answer = parseAnswerArg(completeCall.function.arguments)
                actionLog.add("Step $step: ✓ Task complete")
                return AppNavigatorOutput(finalAnswer = answer, steps = actionLog.toList())
            }

            // 5. Execute each tool call with error recovery
            for (call in toolCalls) {
                val result = executeWithRetry(call)
                val summary = if (result.success) {
                    result.message.take(300)
                } else {
                    "⚠ ${result.message.take(300)}"
                }
                actionLog.add("Step $step: ${call.function.name} → $summary")
                onStep(call.function.name, "completed", summary)
            }

            // Wait for animations/transitions to settle before the next observation
            if (toolCalls.isNotEmpty()) {
                delay(1000)
            }
        }

        // An LLM error means the provider is rejecting our requests; a summary call
        // with the same payload would fail the same way, so report the error directly.
        val finalAnswer = if (llmError != null) {
            "Navigation stopped after $stepsRun step(s) because the LLM request failed: " +
                "${llmError.message}. Do not retry the navigation tool for this task; " +
                "report the error to the user instead."
        } else {
            generateHandoverSummary(llmClient, lastUserContent)
        }

        return AppNavigatorOutput(
            finalAnswer = finalAnswer,
            steps = actionLog.toList(),
            success = false
        )
    }

    private fun buildUserContent(
        screenshot: ScreenPerception.CompressedScreenshot?,
        uiTree: String,
        actionLogText: String
    ): JsonElement {
        val obsText = if (screenshot != null) {
            ScreenPerception.buildObservationText(screenshot, uiTree)
        } else {
            "── UI Elements ──\n$uiTree\n── Screenshot ──\n(failed to capture)"
        }

        val userMsg = buildString {
            appendLine("## Task")
            appendLine(task)
            appendLine()
            appendLine("## Previous Actions")
            appendLine(actionLogText)
            appendLine()
            appendLine("## Current Screen")
            appendLine(obsText)
        }

        return if (screenshot != null) {
            buildJsonArray {
                addJsonObject {
                    put("type", "text")
                    put("text", userMsg)
                }
                addJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") {
                        put("url", "data:image/${screenshot.format};base64,${screenshot.base64}")
                    }
                }
            }
        } else {
            JsonPrimitive(userMsg)
        }
    }

    private suspend fun generateHandoverSummary(
        llmClient: LLMClient,
        lastUserContent: JsonElement?
    ): String {
        val summaryPrompt = """
            The navigation agent has reached the maximum limit of $maxSteps steps and must now stop.
            Please generate a concise handover handout/summary for the main agent detailing:
            1. What was accomplished so far.
            2. The steps taken.
            3. The specific problem, roadblock, or error faced.
            4. Explicit recommendation/instructions for the main agent on what to do next.

            Format your response clearly. Start with a statement that the navigation task failed because the step limit was reached, and explicitly instruct the main agent NOT to attempt the navigation tool again for this task.
        """.trimIndent()

        return if (lastUserContent != null) {
            try {
                onStep("Generating handover summary", "running", "")
                val summaryResponse = llmClient.chat(
                    messages = listOf(
                        ChatMessage(role = "system", content = JsonPrimitive(NAVIGATOR_SYSTEM_PROMPT)),
                        ChatMessage(role = "user", content = lastUserContent),
                        ChatMessage(role = "system", content = JsonPrimitive(summaryPrompt))
                    ),
                    tools = emptyList(),
                    sessionId = sessionId
                )
                val summary = summaryResponse.choices.firstOrNull()?.message?.textContent
                if (!summary.isNullOrBlank()) {
                    summary
                } else {
                    "Reached $maxSteps steps without completing the task. No summary could be generated."
                }
            } catch (e: Throwable) {
                "Reached $maxSteps steps without completing the task. Failed to generate summary: ${e.message}"
            }
        } else {
            "Reached $maxSteps steps without completing the task."
        }
    }

    private suspend fun executeWithRetry(call: ToolCall): ToolResult {
        var lastResult: ToolResult = ToolResult.error("Exhausted retries")
        val args: JsonObject = try {
            json.decodeFromString(JsonObject.serializer(), call.function.arguments.ifBlank { "{}" })
        } catch (_: Exception) {
            return ToolResult.error("Malformed arguments: ${call.function.arguments.take(100)}")
        }

        for (attempt in 1..3) {
            try {
                lastResult = toolExecutor.execute(
                    call.function.name, args,
                    AgentMode.OPERATOR, isSubAgent = true
                )
                kotlinx.coroutines.delay(300)
                if (lastResult.success) return lastResult
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastResult = ToolResult.error("Attempt $attempt failed: ${e.message}")
            }
            if (attempt < 3 && !lastResult.success) {
                kotlinx.coroutines.delay(500)
            }
        }
        return lastResult
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        private fun parseAnswerArg(arguments: String): String {
            return try {
                val obj = JSON.decodeFromString<JsonObject>(arguments)
                obj["answer"]?.jsonPrimitive?.content ?: "(completed)"
            } catch (_: Exception) {
                "(completed)"
            }
        }

        private const val NAVIGATOR_SYSTEM_PROMPT = """
You are Gotcha's App Navigator sub-agent, created by Samosa AI. Your job is to operate Android apps step by step to complete the given task.

## App Launching
- Use open_app to launch an app directly by name (e.g. "Settings" or "Google Maps"). Do NOT navigate through the home screen or app drawer — that wastes steps.
- After launching an app, call sleep(2-3) to let it load before interacting.
- Use global_action(recents) to switch between running apps.

## Rules
- On-screen text may be in any language; match it literally as it appears — do not translate it.
- Take ONE action per turn. After acting, a fresh screenshot and element list will show you the result.
- The UI Elements list gives you precise element indices. The screenshot gives visual context with a coordinate grid.
- Prefer tap_index over raw coordinates — it is more precise and survives layout changes.
- When using coordinates, values are in [0, 1000] normalized space.
- Never repeat an action that already failed the same way.
- If an action fails, try a different approach on the next turn.

## Error Recovery
- If tap_index fails: the element may not be visible yet. Swipe to scroll, wait with sleep, or check if you need to navigate to a different screen first.
- If the screen looks the same after an action: you may need to wait longer (sleep(2)), or try swiping to reveal content.
- When an element index seems wrong, the screen may have changed since the last scan — the fresh data at the start of this turn is current.
- Use search_skills to look up guidance on how to use an unfamiliar app. Call it once with the app name.
- If you cannot find what you need after several attempts, call ask_final_answer to report what you found (or did not find).

## Text Input
- Use input_text(text, index=N) to type into a specific text field. Always include the index when possible.
- After typing, call press_key(enter) to submit the text (e.g. for search bars, login fields).
- If the field content looks wrong, tap on it first to focus it, then input_text again.

## Swipe / Scroll
- Swipe 'down' to scroll down (reveals lower content further down the page).
- Swipe 'up' to scroll up (reveals content above the current view).
- To scroll a specific element (e.g. a list inside a screen), pass the element's index.
- Pass a small distance value for short scrolls (e.g. to reveal a button just off-screen).

## Available Actions
- open_app(name) — launch an app directly (preferred over navigating home screen)
- tap_index(index) — tap an element by its number from the UI Elements list
- tap(x, y, normalized=true) — tap at normalized coordinates
- long_press_index(index) — long-press an element by its number
- long_press(x, y, normalized=true) — long-press at coordinates
- swipe(direction, distance?, index?) — scroll the screen or a specific element
- input_text(text, index?) — type text, optionally into a specific field
- press_key(key) — press enter, back, or home
- global_action(action) — recents, notifications, quick_settings, lock_screen
- sleep(duration_seconds) — wait for loading or animations (1-30 seconds)
- search_skills(query) — look up how to use an app or perform an action
- list_installed_apps(search?) — find the correct app name or package name
- ask_final_answer(answer) — task is complete. Provide a clear summary of what was done.
"""
    }
}
