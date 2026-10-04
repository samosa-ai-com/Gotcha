package com.gotcha.connectors.homeassistant

import com.gotcha.R
import com.gotcha.connectors.Connector
import com.gotcha.connectors.CredentialStore
import com.gotcha.i18n.StringLookup
import com.gotcha.llm.FunctionDefinition
import com.gotcha.llm.ToolDefinition
import com.gotcha.tools.ToolRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Credentials for the Home Assistant MCP server: base URL + long-lived access token. */
@Serializable
data class HomeAssistantCredentials(
    val baseUrl: String,
    val token: String,
    /**
     * Cached `tools/list` snapshot. HA's tool set is server-defined and dynamic,
     * so the names/schemas are stored alongside the credentials to make the tools
     * available immediately after an app restart without a network call.
     */
    val tools: List<McpToolSchema> = emptyList()
)

/**
 * Home Assistant connector backed by its MCP server (`<ha_url>/api/mcp`),
 * authenticated with a long-lived access token.
 *
 * Unlike the other connectors, the tool set is **server-defined**: it is fetched
 * from `tools/list` at connect time and registered with
 * [ToolRegistry.setDynamicTools], so the model sees exactly the tools Home
 * Assistant exposes for the entities shared with Assist. Disconnect clears the
 * registered tools again; [ConnectorRegistry] hides whatever is registered while
 * the connector is switched off.
 */
class HomeAssistantConnector(
    private val store: CredentialStore,
    private val client: HomeAssistantMcpClient = HomeAssistantMcpClient()
) : Connector {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var credentials: HomeAssistantCredentials? = store.loadRaw(ID)?.let { blob ->
        runCatching { json.decodeFromString<HomeAssistantCredentials>(blob) }.getOrNull()
    }

    /**
     * Bumped on every disconnect. connect()/refreshTools() capture it before their
     * network calls and bail out if it changed by the time they commit, so an
     * in-flight connect or refresh can never resurrect credentials (or register
     * tools) after the user has disconnected. The commit itself runs under
     * [stateLock], which disconnect() also takes, making the check + write atomic.
     */
    @Volatile
    private var connectionGeneration = 0L

    private val stateLock = Any()

    init {
        // After a restart the tools are re-registered from the cached snapshot so
        // they exist before any network call; connect() refreshes them live.
        registerTools(credentials?.tools.orEmpty())
    }

    override val id = ID
    override val displayName = "Home Assistant"
    override val description =
        "Control and query your smart home through Home Assistant's Model Context Protocol server."
    override val toolNames: Set<String>
        get() = credentials?.tools?.mapTo(mutableSetOf()) { it.name } ?: emptySet()
    override val spec = com.gotcha.connectors.ConnectorCatalog.HOME_ASSISTANT

    fun credentials(): HomeAssistantCredentials? = credentials

    override fun isConnected(): Boolean = credentials != null

    override fun statusLine(strings: StringLookup): String = credentials
        ?.let { strings(R.string.connector_connected_to, hostOf(it.baseUrl)) }
        ?: strings(R.string.connector_not_connected)

    override fun disconnect() {
        // Invalidate any in-flight connect/refresh so it cannot re-establish the
        // connection after the user disconnects (see [connectionGeneration]).
        synchronized(stateLock) {
            connectionGeneration++
            store.clear(id)
            credentials = null
        }
        ToolRegistry.clearDynamicTools()
    }

    /**
     * Validates the URL + long-lived token against the MCP server before storing
     * them, so a typo is caught on the Settings screen instead of on the first
     * tool call. Returns a status message for the card.
     */
    suspend fun connect(baseUrl: String, token: String, strings: StringLookup): String {
        val generation = connectionGeneration
        var url = baseUrl.trim().trimEnd('/')
        if (url.isNotBlank() && !url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "http://$url"
        }
        val trimmedToken = token.trim()
        if (url.isBlank()) return strings(R.string.ha_enter_url)
        if (trimmedToken.isBlank()) return strings(R.string.ha_paste_token)
        return try {
            val endpoint = HomeAssistantMcpClient.mcpEndpoint(url)
            client.initialize(endpoint, trimmedToken)
            val tools = client.listTools(endpoint, trimmedToken)
            val creds = HomeAssistantCredentials(url, trimmedToken, tools)
            val committed = synchronized(stateLock) {
                if (generation != connectionGeneration) {
                    null
                } else {
                    store.saveRaw(id, json.encodeToString(creds))
                    credentials = creds
                    creds
                }
            } ?: return strings(R.string.ha_disconnected_while_connecting)
            registerTools(committed.tools)
            val hint = strings(if (tools.isEmpty()) R.string.ha_hint_expose else R.string.ha_hint_expose_more)
            strings(R.string.ha_connected, hostOf(url), toolsAvailable(tools.size, strings), hint)
        } catch (e: Exception) {
            strings(R.string.connector_could_not_connect, e.message.orEmpty())
        }
    }

    /** "3 Home Assistant tools are available." */
    private fun toolsAvailable(count: Int, strings: StringLookup): String =
        strings.quantity(R.plurals.ha_tools_available, count, count)

    /** Executes a server-defined MCP tool by name with [args]. */
    suspend fun callTool(name: String, args: JsonObject): McpCallResult {
        val creds = checkNotNull(credentials) { "Home Assistant is not connected." }
        return client.callTool(
            HomeAssistantMcpClient.mcpEndpoint(creds.baseUrl),
            creds.token,
            name,
            args
        )
    }

    /** Re-reads `tools/list` and updates the cached + registered tool set. */
    override suspend fun refreshTools(strings: StringLookup): String {
        val generation = connectionGeneration
        val creds = credentials ?: return strings(R.string.ha_not_connected)
        return try {
            val endpoint = HomeAssistantMcpClient.mcpEndpoint(creds.baseUrl)
            val tools = client.listTools(endpoint, creds.token)
            val committed = synchronized(stateLock) {
                if (generation != connectionGeneration) {
                    null
                } else {
                    val updated = creds.copy(tools = tools)
                    store.saveRaw(id, json.encodeToString(updated))
                    credentials = updated
                    updated
                }
            } ?: return strings(R.string.ha_disconnected_while_refreshing)
            registerTools(committed.tools)
            toolsAvailable(committed.tools.size, strings)
        } catch (e: Exception) {
            strings(R.string.ha_refresh_failed, e.message.orEmpty())
        }
    }

    /** Registers [tools] with [ToolRegistry]; read-only names go to the Monitor slice. */
    private fun registerTools(tools: List<McpToolSchema>) {
        ToolRegistry.setDynamicTools(
            tools.map { ToolDefinition(function = FunctionDefinition(it.name, it.description, it.inputSchema)) },
            readOnlyNames = tools.filter { isReadOnlyTool(it.name) }
                .mapTo(mutableSetOf()) { it.name }
        )
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

    companion object {
        /**
         * Action verbs indicating mutations/control. Checked first so a tool like
         * `HassSetState` or `HassResetContext` is never incorrectly marked read-only.
         */
        private val MUTATION_MARKERS = listOf(
            "set",
            "turn",
            "toggle",
            "change",
            "update",
            "create",
            "delete",
            "remove",
            "clear",
            "reset",
            "add",
            "write",
            "execute",
            "run",
            "trigger",
            "press",
            "activate",
            "deactivate",
            "open",
            "close",
            "lock",
            "unlock",
            "stop",
            "start",
            "pause",
            "play"
        )

        /**
         * MCP does not mark tools read-only, so Monitor's strict read-only contract
         * has to be approximated from the tool name. HA's Assist intents follow a
         * stable naming scheme (`HassGetState`, `GetLiveContext`, `HassClimateGetTemperature`,
         * ...) whose reads carry a marker below; a name with none of them is treated
         * as control. Conservative by construction: read markers never unlock a tool
         * that is not actually read-only, the worst case is a read hidden from Monitor.
         */
        private val READ_ONLY_MARKERS = listOf(
            "get",
            "list",
            "read",
            "state",
            "context",
            "query",
            "snapshot",
            "search"
        )

        const val ID = "homeassistant"

        fun isReadOnlyTool(name: String): Boolean {
            val lower = name.lowercase()
            if (MUTATION_MARKERS.any { lower.contains(it) }) return false
            return READ_ONLY_MARKERS.any { lower.contains(it) }
        }
    }
}
