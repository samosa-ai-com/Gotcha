package com.gotcha.connectors.homeassistant

import com.gotcha.connectors.ToolRouter
import com.gotcha.tools.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * Router owning the Home Assistant MCP tools. The tool set is server-defined and
 * registered with ToolRegistry on connect, so [toolNames] follows the connector's
 * current snapshot instead of a compile-time set.
 */
class HomeAssistantTools(
    private val backend: () -> HomeAssistantConnector?
) : ToolRouter {

    override val toolNames: Set<String>
        get() = backend()?.toolNames ?: emptySet()

    override suspend fun execute(name: String, args: JsonObject): ToolResult {
        val ha = backend()?.takeIf { it.isConnected() }
            ?: return ToolResult.error(
                "Home Assistant is not connected. Ask the user to add its URL and a long-lived " +
                    "access token in Settings → Connectors."
            )
        return try {
            val result = ha.callTool(name, args)
            if (result.success) ToolResult.ok(result.text) else ToolResult.error(result.text)
        } catch (e: Exception) {
            ToolResult.error("Home Assistant tool '$name' failed: ${e.message}")
        }
    }
}
