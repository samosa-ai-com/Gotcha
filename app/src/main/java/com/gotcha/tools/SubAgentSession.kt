package com.gotcha.tools

import android.content.Context
import android.util.Log
import com.gotcha.agent.AgentEngine
import com.gotcha.agent.skills.SkillPromptBuilder
import com.gotcha.agent.skills.SkillRegistry
import com.gotcha.connectors.ConnectorRegistry
import com.gotcha.data.Settings
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.LLMClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

data class SubAgentOutput(
    val finalAnswer: String,
    val steps: List<String>
)

/**
 * Runs an independent LLM tool loop for a delegated sub-agent task.
 * Intermediate tool calls and results are reported via [onStep] and
 * returned as part of [SubAgentOutput] alongside the final answer.
 */
class SubAgentSession(
    private val appContext: Context,
    private val toolExecutor: ToolExecutor,
    private val settings: Settings,
    private val description: String,
    private val prompt: String,
    private val onStep: (action: String, status: String, detail: String) -> Unit = { _, _, _ -> },
    private val sessionId: String? = null
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var activeTokenCount = 0
    private val collectedSteps = mutableListOf<String>()

    suspend fun run(): SubAgentOutput {
        val model = settings.subAgentModel.ifBlank { settings.model }
        Log.d(TAG, "Starting sub-agent with model=$model: $description")

        val subLlm = LLMClient(
            apiKey = settings.effectiveApiKey,
            baseUrl = settings.effectiveBaseUrl,
            model = model,
            context = appContext,
            apiTimeoutSeconds = settings.apiTimeoutSeconds
        )

        val history = initialHistory()

        val maxRounds = settings.maxToolRounds
        val hiddenTools = hiddenTools()
        val subAgentTools = ToolRegistry.toolsForSubAgent(hiddenTools)

        for (round in 0 until maxRounds) {
            Log.d(TAG, "Sub-agent round ${round + 1}/$maxRounds")

            val messages = buildList {
                addAll(history)
                addAll(activeSkillsMessages(history, settings.disabledSkills, hiddenTools))
            }

            val response = try {
                subLlm.chat(messages, subAgentTools, sessionId = sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Sub-agent LLM call failed: ${e.message}", e)
                onStep("error", "completed", e.message ?: "LLM call failed")
                return SubAgentOutput("Task failed: ${e.message}", collectedSteps.toList())
            }

            response.usage?.totalTokens?.let { activeTokenCount = it }

            val message = response.choices.firstOrNull()?.message
            if (message == null) {
                return SubAgentOutput("Task failed: empty response from model", collectedSteps.toList())
            }

            val toolCalls = message.toolCalls.orEmpty()

            // Capture any reasoning / text before tool calls
            if (message.hasText && round == 0) {
                val thought = message.textContent.take(120)
                collectedSteps.add("reasoning: $thought")
                onStep("reasoning", "completed", thought)
            }

            if (toolCalls.isEmpty()) {
                history.add(message)
                val text = message.textContent.ifEmpty { "(no output)" }
                Log.d(TAG, "Sub-agent finished (text response): ${text.take(100)}")
                return SubAgentOutput(text, collectedSteps.toList())
            }

            history.add(message)

            // Check for ask_final_answer
            val finalCall = toolCalls.firstOrNull { it.function.name == "ask_final_answer" }
            if (finalCall != null) {
                val answer = try {
                    val args = json.decodeFromString<JsonObject>(finalCall.function.arguments)
                    args["answer"]?.jsonPrimitive?.content ?: "(no answer provided)"
                } catch (e: Exception) {
                    "(failed to parse ask_final_answer: ${e.message})"
                }
                Log.d(TAG, "Sub-agent finished (ask_final_answer)")
                return SubAgentOutput(answer, collectedSteps.toList())
            }

            for (call in toolCalls) {
                val toolName = call.function.name
                onStep(toolName, "running", "")
                collectedSteps.add("$toolName → (running)")

                val args = try {
                    json.decodeFromString<JsonObject>(call.function.arguments.ifBlank { "{}" })
                } catch (_: Exception) {
                    val err = "Malformed args: ${call.function.arguments.take(100)}"
                    history.add(ChatMessage(role = "tool", content = JsonPrimitive(err), toolCallId = call.id))
                    collectedSteps[collectedSteps.lastIndex] = "$toolName → failed: bad arguments"
                    onStep(toolName, "completed", "failed: bad arguments")
                    continue
                }

                val result = try {
                    toolExecutor.execute(
                        call.function.name,
                        args,
                        AgentMode.OPERATOR,
                        isSubAgent = true,
                        hiddenTools = hiddenTools
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    ToolResult.error("Tool '${call.function.name}' failed: ${e.message}")
                }

                val summary = result.message.take(80)
                collectedSteps[collectedSteps.lastIndex] = "$toolName → ${if (result.success) "completed" else "failed"}: $summary"
                onStep(toolName, "completed", summary)

                history.add(
                    ChatMessage(
                        role = "tool",
                        content = JsonPrimitive(result.message),
                        toolCallId = call.id
                    )
                )

                // Report reasoning text that sometimes comes alongside tool calls
                if (message.hasText && collectedSteps.none { it.startsWith("reasoning:") }) {
                    val thought = message.textContent.take(120)
                    if (thought.isNotBlank()) {
                        collectedSteps.add("reasoning: $thought")
                        onStep("reasoning", "completed", thought)
                    }
                }
            }

            if (activeTokenCount > settings.maxContextTokens * 0.8) {
                compactHistory(history, subLlm)
            }
        }

        val msg = "Task exceeded $maxRounds tool rounds without producing a final answer."
        return SubAgentOutput(msg, collectedSteps.toList())
    }

    /** Builds the system + user messages that seed the sub-agent conversation. */
    private fun initialHistory(): MutableList<ChatMessage> {
        val systemMsg = ChatMessage(
            role = "system",
            content = JsonPrimitive(
                "You are Gotcha's General sub-agent, created by Samosa AI running on the user's Android phone. " +
                    "You have access to all device tools. " +
                    "Your job is to complete the task delegated to you. " +
                    "Use the available tools to perform the required steps. " +
                    "When interacting with unfamiliar apps, system settings, or complex " +
                    "workflows, use the search_skills tool to fetch context-aware " +
                    "operational instructions.\n" +
                    "When you have fully completed the task and have the final answer, " +
                    "call the ask_final_answer tool with your complete result. " +
                    "Do NOT call ask_final_answer until all work is actually done.\n\n" +
                    "Respond to the user in ${settings.preferredLanguage}. If the user writes " +
                    "to you in a different language, reply in that language instead.\n" +
                    "ALWAYS use English for tool names, tool arguments, file paths, package names, " +
                    "app names, search queries passed to tools, and shell commands — regardless of " +
                    "the language you are replying in."
            )
        )
        val userMsg = ChatMessage(
            role = "user",
            content = JsonPrimitive(prompt)
        )
        activeTokenCount = (systemMsg.textContent.length + userMsg.textContent.length) / 4 +
            AgentEngine.PROMPT_OVERHEAD_TOKENS
        return mutableListOf(systemMsg, userMsg)
    }

    /**
     * Compacts history using an LLM summarization call (same strategy as
     * ChatViewModel.checkAndCompactHistory). Preserves the system prompt
     * and original user task, replacing intermediate exchanges with a
     * dense summary so no task context is lost.
     */
    private suspend fun compactHistory(
        history: MutableList<ChatMessage>,
        llm: LLMClient
    ) {
        if (history.size <= 3) return

        val sysPrompt = history.first()
        val userTask = history[1]

        // Serialize the intermediate exchanges for the compaction LLM
        val exchangeText = history.subList(2, history.size).joinToString("\n\n") { msg ->
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

        val compactionSystem = ChatMessage(
            role = "system",
            content = JsonPrimitive(
                "You are a Context Compaction Agent for a sub-agent. Compress the following sub-agent " +
                    "tool-call history into a dense summary. You must preserve exactly:\n" +
                    "1. Goal: What is the specific sub-task being attempted?\n" +
                    "2. Attempted: What was tried?\n" +
                    "3. Succeeded: What worked?\n" +
                    "4. Failed: What failed and why?\n" +
                    "5. Discovered Data: Key technical specifics, coordinates, text, or states found.\n" +
                    "Be concise but do not lose technical specifics."
            )
        )
        val compactionUser = ChatMessage(
            role = "user",
            content = JsonPrimitive(
                "Original task: ${userTask.textContent}\n\n" +
                    "Intermediate exchanges:\n$exchangeText"
            )
        )

        try {
            val response = llm.chat(listOf(compactionSystem, compactionUser))
            val summary = response.choices.firstOrNull()?.message?.textContent
            if (!summary.isNullOrBlank()) {
                history.clear()
                history.add(sysPrompt)
                history.add(userTask)
                history.add(
                    ChatMessage(
                        role = "assistant",
                        content = JsonPrimitive("[Context compacted]\n$summary")
                    )
                )
                // Keep the units consistent with what the API would report on the
                // next round: history length plus the static prompt prefix.
                activeTokenCount = history.sumOf { it.textContent.length / 4 } +
                    AgentEngine.PROMPT_OVERHEAD_TOKENS
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Compaction failed — keep full history and continue
            Log.w(TAG, "Sub-agent context compaction failed, keeping full history")
        }
    }

    /**
     * Sub-agents inherit the parent's gating: no point paying for schemas of
     * tools nothing can serve, once per round, again.
     */
    private fun hiddenTools(): Set<String> =
        ConnectorRegistry.hiddenToolNames(settings.disabledConnectors) +
            DeviceCapabilities.hiddenToolNames(appContext)

    private fun activeSkillsMessages(
        history: List<ChatMessage>,
        disabledSkills: Set<String>,
        hiddenTools: Set<String>
    ): List<ChatMessage> {
        val currentPackage = ScreenPerception.getCurrentPackageName() ?: return emptyList()
        val activeSkills = SkillRegistry.getSkillsForPackage(currentPackage, hiddenTools)
            .filter { !disabledSkills.contains(it.id) }
        val communityIds = SkillRegistry.getCommunitySkills().map { it.id }.toSet()
        val message = SkillPromptBuilder.buildFromHistory(
            currentPackage = currentPackage,
            activeSkills = activeSkills,
            communityIds = communityIds,
            history = history
        ) ?: return emptyList()
        return listOf(message)
    }

    private companion object {
        const val TAG = "SubAgentSession"
    }
}
