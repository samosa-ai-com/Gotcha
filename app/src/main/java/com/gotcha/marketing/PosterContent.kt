package com.gotcha.marketing

import com.gotcha.data.RunSummary
import kotlinx.serialization.Serializable

/**
 * Structured copy produced by the one marketing LLM call, merged with
 * app-computed stats (duration, tool counts) by [PosterRenderer].
 *
 * The LLM never writes the numbers — duration/counts are computed
 * deterministically by the app so they can't be hallucinated. The model
 * identity is intentionally hardcoded in the renderer's branding copy
 * ("Built with Samosa AI") and is not carried on [PosterStats].
 */
@Serializable
data class PosterContent(
    /** False when nothing worth promoting actually happened. */
    val eligible: Boolean = true,
    /** "hero" (single run) or "recap" (multiple runs). */
    val template: String = "hero",
    val headline: String = "",
    val subheadline: String = "",
    val body: String = "",
    val achievements: List<String> = emptyList(),
    val callToAction: String = "Meet your agent.",
    val hashtags: List<String> = emptyList()
)

/**
 * Deterministic stats extracted from the run summaries — never produced by the
 * LLM. Rendered as the poster's stat chips / recap totals.
 */
data class PosterStats(
    val runCount: Int,
    val totalDurationSeconds: Long,
    val toolCount: Int,
    val achievementCount: Int = 0
) {
    val durationDisplay: String
        get() {
            val s = totalDurationSeconds
            return when {
                s < 60 -> "${s}s"
                s < 3600 -> "${s / 60}m ${s % 60}s"
                else -> "${s / 3600}h ${(s % 3600) / 60}m"
            }
        }
}

/** Builds [PosterStats] from one or more runs. */
object PosterStatsBuilder {
    fun from(runs: List<RunSummary>): PosterStats {
        val duration = runs.sumOf { it.endedAt - it.startedAt } / 1000
        val tools = runs.flatMap { it.toolCalls }.distinctBy { it.name }.size
        return PosterStats(
            runCount = runs.size,
            totalDurationSeconds = duration,
            toolCount = tools
        )
    }
}
