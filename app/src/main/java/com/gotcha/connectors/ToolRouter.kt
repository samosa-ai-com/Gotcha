package com.gotcha.connectors

import com.gotcha.tools.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * A connector-backed handler for a group of tools. Routers own the tools whose
 * implementation depends on connector credentials (email, tasks, calendar,
 * Notion); the schemas still live in [com.gotcha.tools.ToolDefinitions] and the
 * agent-mode gating still lives in [com.gotcha.tools.ToolRegistry].
 *
 * [ConnectorRegistry.toolHandler] resolves a tool name to the owning router, and
 * `ToolExecutor.dispatch` consults it before its own `when`.
 */
interface ToolRouter {
    /** Tool names this router claims. Must be disjoint from every other router. */
    val toolNames: Set<String>

    suspend fun execute(name: String, args: JsonObject): ToolResult
}
