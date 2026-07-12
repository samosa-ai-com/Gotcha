package com.gotcha.tools

import android.content.Context
import android.util.Log
import com.gotcha.data.Settings
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.LLMClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Runs an independent LLM tool loop for a delegated sub-agent task.
 * Intermediate tool calls and results stay internal — only the final
 * answer (from [ask_final_answer] or a text-only response) is returned.
 */
class SubAgentSession(
    private val appContext: Context,
    private val toolExecutor: ToolExecutor,
    private val settings: Settings,
    private val description: String,
    private val prompt: String
) {
    private val TAG = "SubAgentSession"
    private val json = Json { ignoreUnknownKeys = true }

    /** Sub-agent session token tracking for model-appropriate trim. */
    private var activeTokenCount = 0

    suspend fun run(): String {
        val model = settings.subAgentModel.ifBlank { settings.model }
        Log.d(TAG, "Starting sub-agent with model=$model: $description")

        val subLlm = LLMClient(
            apiKey = settings.apiKey,
            baseUrl = settings.baseUrl,
            model = model,
            context = appContext,
            apiTimeoutSeconds = settings.apiTimeoutSeconds
        )

        val systemMsg = ChatMessage(
            role = "system",
            content = JsonPrimitive(
                "You are General, a general-purpose AI agent running on the user's Android phone. " +
                "You have access to all device tools. " +
                "Your job is to complete the task delegated to you. " +
                "Use the available tools to perform the required steps. " +
                "When you have fully completed the task and have the final answer, " +
                "call the ask_final_answer tool with your complete result. " +
                "Do NOT call ask_final_answer until all work is actually done. " +
                "Keep intermediate tool calls focused; only the final answer will be delivered."
            )
        )

        val userMsg = ChatMessage(
            role = "user",
            content = JsonPrimitive(prompt)
        )

        val history = mutableListOf(systemMsg, userMsg)
        activeTokenCount = (systemMsg.textContent.length + userMsg.textContent.length) / 4

        val maxRounds = settings.maxToolRounds
        val subAgentTools = ToolRegistry.toolsForSubAgent()

        for (round in 0 until maxRounds) {
            Log.d(TAG, "Sub-agent round ${round + 1}/$maxRounds")

            val response = try {
                subLlm.chat(history.toList(), subAgentTools)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Sub-agent LLM call failed: ${e.message}")
                return "Task failed: ${e.message}"
            }

            response.usage?.totalTokens?.let { activeTokenCount = it }

            val message = response.choices.firstOrNull()?.message
            if (message == null) {
                return "Task failed: empty response from model"
            }

            val toolCalls = message.toolCalls.orEmpty()

            if (toolCalls.isEmpty()) {
                history.add(message)
                val text = message.textContent.ifEmpty { "(no output)" }
                Log.d(TAG, "Sub-agent finished (text response): ${text.take(100)}")
                return text
            }

            history.add(message)

            val finalCall = toolCalls.firstOrNull { it.function.name == "ask_final_answer" }
            if (finalCall != null) {
                val answer = try {
                    val args = json.decodeFromString<JsonObject>(finalCall.function.arguments)
                    args["answer"]?.jsonPrimitive?.content ?: "(no answer provided)"
                } catch (e: Exception) {
                    "(failed to parse ask_final_answer arguments: ${e.message})"
                }
                Log.d(TAG, "Sub-agent finished (ask_final_answer): ${answer.take(100)}")
                return answer
            }

            for (call in toolCalls) {
                val args = try {
                    json.decodeFromString<JsonObject>(call.function.arguments.ifBlank { "{}" })
                } catch (e: Exception) {
                    history.add(
                        ChatMessage(
                            role = "tool",
                            content = JsonPrimitive("Malformed arguments: ${call.function.arguments.take(200)}"),
                            toolCallId = call.id
                        )
                    )
                    continue
                }

                val result = try {
                    toolExecutor.execute(call.function.name, args, AgentMode.OPERATOR, isSubAgent = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ToolResult.error("Tool '${call.function.name}' failed: ${e.message}")
                }

                history.add(
                    ChatMessage(
                        role = "tool",
                        content = JsonPrimitive(result.message),
                        toolCallId = call.id
                    )
                )
            }

            // Compact history if it exceeds 80% of context limit
            if (activeTokenCount > settings.maxContextTokens * 0.8) {
                trimHistory(history)
            }
        }

        return "Task exceeded $maxRounds tool rounds without producing a final answer."
    }

    private fun trimHistory(history: MutableList<ChatMessage>) {
        // Keep system prompt + last ~10 messages
        val sysPrompt = history.firstOrNull { it.role == "system" }
        history.clear()
        if (sysPrompt != null) history.add(sysPrompt)
        activeTokenCount = history.sumOf { it.textContent.length / 4 }
    }
}
