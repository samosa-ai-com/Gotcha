package com.gotcha.marketing

import android.content.Context
import com.gotcha.data.RunSummary
import com.gotcha.data.Settings
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.LLMClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one LLM call behind the "Share your Gotcha moment" poster.
 *
 * Input: a compact, sanitized digest of one or more run summaries — the user's
 * request, the successful tool actions, and the final outcome. Deliberately
 * NOT the full transcript, and deliberately only *successful* tool executions
 * so an intermediate failure that was later recovered never reaches the copy.
 *
 * Output: [PosterContent] parsed from the model's JSON reply. A tolerant parse
 * with a [fallback] poster means the feature degrades gracefully when the model
 * returns garbage — it never crashes.
 *
 * The stats (duration, tool counts) are computed by [PosterStatsBuilder],
 * never by the LLM, so the numbers on the poster can't be hallucinated.
 */
@OptIn(ExperimentalSerializationApi::class)
class ShareCardClient(
    context: Context,
    private val settings: Settings
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    private val llm: LLMClient by lazy {
        LLMClient(
            apiKey = settings.effectiveApiKey,
            baseUrl = settings.effectiveBaseUrl,
            model = settings.model,
            context = context,
            apiTimeoutSeconds = settings.apiTimeoutSeconds
        )
    }

    /**
     * Generates poster copy for [runs]. Returns a [PosterContent] that may have
     * [PosterContent.eligible] == false when [runs] is empty, or fall back to
     * [fallback] if the model's JSON is unparseable or the model judged the
     * digest not worth promoting.
     *
     * A model "eligible": false is treated like a parse failure, not a verdict:
     * the fallback poster is deterministic and stays true to the digest, so a
     * non-deterministic LLM can't make the share card appear to work one tap
     * and fail the next.
     */
    suspend fun generate(runs: List<RunSummary>): PosterContent {
        if (runs.isEmpty()) return PosterContent(eligible = false)
        val content = try {
            llm.chat(
                messages = buildMessages(runs),
                temperature = MARKETING_TEMPERATURE,
                sessionId = "marketing_share_card"
            ).choices.firstOrNull()?.message?.textContent ?: ""
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        val parsed = parse(content)
        return if (parsed?.eligible == true) parsed else fallback(runs)
    }

    private fun buildMessages(runs: List<RunSummary>): List<ChatMessage> {
        val digest = buildString {
            runs.forEach { run ->
                appendLine("--- Run ---")
                appendLine("User asked: ${run.userPrompt.trim()}")
                val done = run.toolCalls.filter { it.success }
                if (done.isNotEmpty()) {
                    appendLine("Completed:")
                    done.forEach { t ->
                        appendLine("- ${t.name}: ${t.result.trim().take(140)}")
                    }
                }
                appendLine("Outcome: ${run.finalReply.trim().take(300)}")
            }
        }
        return listOf(
            ChatMessage(
                role = "system",
                content = JsonPrimitive(SYSTEM_PROMPT)
            ),
            ChatMessage(
                role = "user",
                content = JsonPrimitive(
                    "Here is what actually happened:\n\n$digest\n\n" +
                        "Write the poster copy now. JSON only."
                )
            )
        )
    }

    /** Extracts a [PosterContent] from the model's text, stripping code fences. */
    internal fun parse(raw: String): PosterContent? {
        val text = raw.trim()
        if (text.isBlank()) return null
        // Strip ```json ... ``` fences if present, then find the JSON object.
        val noFences = text.replace(Regex("(?s)^```(?:json)?\\s*|```\\s*$"), "").trim()
        val start = noFences.indexOf('{')
        val end = noFences.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val obj = json.parseToJsonElement(noFences.substring(start, end + 1))
            json.decodeFromJsonElement(PosterContent.serializer(), obj)
        } catch (_: Exception) {
            null
        }
    }

    /** Deterministic fallback poster when the model fails: no fabrication, just facts. */
    internal fun fallback(runs: List<RunSummary>): PosterContent {
        val run = runs.last()
        val headline = if (runs.size == 1) {
            "Gotcha handled that for me."
        } else {
            "Gotcha handled ${runs.size} things for me today."
        }
        return PosterContent(
            eligible = true,
            template = if (runs.size == 1) "hero" else "recap",
            headline = headline,
            subheadline = run.finalReply.trim().take(120).ifEmpty { run.userPrompt.trim().take(120) },
            body = "Powered by Gotcha — your AI agent on Android.",
            achievements = runs.takeLast(5).map { it.finalReply.trim().take(90) }.filter { it.isNotBlank() },
            callToAction = "Meet Gotcha.",
            hashtags = listOf("#Gotcha", "#AIAgent", "#AndroidAI")
        )
    }

    private companion object {
        const val MARKETING_TEMPERATURE = 0.7f

        val SYSTEM_PROMPT = """
            You write short, promotional poster copy for Gotcha, an AI assistant
            that runs on a user's Android phone and does real tasks for them.

            You are given a factual digest of what Gotcha actually accomplished.
            Write copy that:
            - Celebrates Gotcha from a first-person user voice ("I asked Gotcha…",
              "Gotcha handled…"). Always positive, promotional, excited.
            - Is TRUE to the digest. Never invent an action the digest does not
              describe. You may present it positively, but not fabricate it.
            - NEVER mentions failures, retries, errors, or permission issues.
            - Matches the user's tone if their request was playful, else stays
              bright and confident.
            - Uses short punchy sentences. No markdown.

            Respond with a single JSON object (no code fences), keys:
            {
              "eligible": true,
              "template": "hero" | "recap",
              "headline": "≤8 words, the hook, e.g. "I asked Gotcha to plan my Goa trip"",
              "subheadline": "one line completing the thought, e.g. "…and it nailed it in 42 seconds."",
              "body": "one short sentence of detail",
              "achievements": ["short positive line", "…"] (recap only; up to 5; empty for hero),
              "callToAction": "≤4 words, e.g. "Meet your agent."",
              "hashtags": ["#Gotcha", "…"] (2-3 tags)
            }
            Use template "hero" for a single run, "recap" for multiple runs.
            If the digest shows nothing actually got done, return {"eligible": false}.
        """.trimIndent()
    }
}
