package com.gotcha.tools

import android.content.Context
import android.graphics.Bitmap
import com.gotcha.data.Settings
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.ChatResponse
import com.gotcha.llm.LLMClient
import com.gotcha.llm.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

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
    private val sessionId: String? = null
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val maxSteps = 30
    private val actionLog = mutableListOf<String>()

    suspend fun run(): AppNavigatorOutput {
        val model = settings.navigatorModel.ifEmpty { settings.model }
        val llmClient = LLMClient(
            apiKey = settings.apiKey,
            baseUrl = settings.baseUrl,
            model = model,
            context = appContext,
            apiTimeoutSeconds = settings.apiTimeoutSeconds
        )
        val navTools = ToolRegistry.toolsForNavigator()

        for (step in 1..maxSteps) {
            onStep("Analyzing screen", "running", "")

            // 1. Capture fresh perception with grid overlay
            val screenshot = ScreenPerception.compressScreenshot(drawGrid = true)
            val uiTree = ScreenPerception.buildUiHierarchyText()

            // 2. Build single-turn prompt
            val actionLogText = actionLog.joinToString("\n") { "  $it" }.ifEmpty { "  (none yet)" }
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

            // 3. Single-turn LLM call with navigation tools
            val response: ChatResponse = try {
                llmClient.chat(
                    messages = listOf(
                        ChatMessage(role = "system", content = JsonPrimitive(NAVIGATOR_SYSTEM_PROMPT)),
                        ChatMessage(role = "user", content = JsonPrimitive(userMsg))
                    ),
                    tools = navTools,
                    sessionId = sessionId
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actionLog.add("Step $step: LLM error — ${e.message}")
                break
            }

            val msg = response.choices.firstOrNull()?.message ?: break
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
                val summary = if (result.success) result.message.take(100)
                    else "⚠ ${result.message.take(100)}"
                actionLog.add("Step $step: ${call.function.name} → $summary")
                onStep(call.function.name, "completed", summary)
            }
        }

        return AppNavigatorOutput(
            finalAnswer = "Reached $maxSteps steps without completing the task.",
            steps = actionLog.toList(),
            success = false
        )
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
                java.lang.Thread.sleep(300)
                if (lastResult.success) return lastResult
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                lastResult = ToolResult.error("Attempt $attempt failed")
            }
            if (attempt < 3 && !lastResult.success) {
                java.lang.Thread.sleep(500)
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
You are a mobile app navigation agent. Your job is to operate Android apps step by step.

## Rules
- Take ONE action per turn. After acting, a fresh screenshot will show you the result.
- The UI Elements list gives you precise positions. The screenshot gives visual context.
- A coordinate grid is overlaid on the screenshot to help ground coordinates.
- Prefer tap_index over raw coordinates (it's more precise).
- When using coordinates, values are in [0, 1000] normalized space.
- Never repeat an action that already failed.
- Call ask_final_answer when the task is complete — include a clear summary.

## Available Actions
- tap_index(index) — tap an element by its number from the UI Elements list
- tap(x, y, normalized=true) — tap at normalized coordinate (0-1000 space)
- swipe(direction) or swipe(x1,y1,x2,y2, normalized=true) — swipe/scroll
- input_text(text) — type into the focused field
- press_key(key) — back, home, enter
- sleep(ms) — wait briefly (max 3000ms)
- ask_final_answer(answer) — done! Provide the final summary.
"""
    }
}
