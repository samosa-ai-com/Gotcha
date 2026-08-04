package com.gotcha.tools

import android.content.Context

/**
 * Answers questions about Samosa AI — the company behind Gotcha, its other
 * products, pricing, and how to reach the developers.
 *
 * The content is bundled as an asset rather than fetched, so the agent can
 * answer offline and the answer never drifts with the network. The same
 * document backs the Settings > About Us page, which reads it directly.
 */
class CompanyInfoTool(private val context: Context) {

    fun aboutSamosaAi(): ToolResult = try {
        val text = context.assets.open(ABOUT_ASSET)
            .use { it.readBytes().toString(Charsets.UTF_8) }
        ToolResult.ok(text)
    } catch (e: Exception) {
        ToolResult.error("Could not read Samosa AI info: ${e.message}")
    }

    companion object {
        const val ABOUT_ASSET = "company/about-samosa-ai.md"
    }
}
