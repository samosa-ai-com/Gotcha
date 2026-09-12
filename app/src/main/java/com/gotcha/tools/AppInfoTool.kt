package com.gotcha.tools

import android.content.Context

/**
 * Answers questions about Gotcha itself — what it can do, where each setting
 * lives, and which permission a tool is waiting on.
 *
 * The company analogue is [CompanyInfoTool], and the reasoning is the same: the
 * content is a bundled asset, so the answer works offline and never drifts with
 * the network. The difference is what a wrong answer costs here. Without this
 * the agent either invents a settings path or spends several rounds driving the
 * Settings UI to rediscover one, and the question ("which setting do I change
 * to…") is one of the most common things a user asks the assistant it is
 * running inside.
 */
class AppInfoTool(private val context: Context) {

    fun aboutGotcha(): ToolResult = try {
        val text = context.assets.open(ABOUT_ASSET)
            .use { it.readBytes().toString(Charsets.UTF_8) }
        ToolResult.ok(text)
    } catch (e: Exception) {
        ToolResult.error("Could not read Gotcha info: ${e.message}")
    }

    companion object {
        const val ABOUT_ASSET = "app/about-gotcha.md"
    }
}
