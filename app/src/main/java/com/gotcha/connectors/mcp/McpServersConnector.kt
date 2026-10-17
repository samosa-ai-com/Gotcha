package com.gotcha.connectors.mcp

import com.gotcha.R
import com.gotcha.connectors.Connector
import com.gotcha.connectors.ConnectorCatalog
import com.gotcha.connectors.CredentialStore
import com.gotcha.connectors.ToolRouter
import com.gotcha.i18n.StringLookup
import com.gotcha.llm.FunctionDefinition
import com.gotcha.llm.ToolDefinition
import com.gotcha.tools.ToolRegistry
import com.gotcha.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/** The outcome of contacting a server: what it is and what it offers, or why it failed. */
sealed interface McpProbe {
    data class Ok(val server: McpServerInfo, val tools: List<McpTool>) : McpProbe
    data class Failed(val message: String) : McpProbe
}

/**
 * The user's own MCP servers (issue #130), as one connector.
 *
 * Each server is added with a URL, an optional bearer token and optional
 * headers, and can be switched on or off by itself; the connector switch turns
 * them all off at once. The tools of the servers that are on are registered
 * with [ToolRegistry] under this connector's id, named by [McpToolNames], so
 * the hidden-tools gating withholds them whenever the connector is off. A
 * server that is off or removed has its tools unregistered.
 *
 * Only tools a server marks `readOnlyHint` reach Monitor; every other tool is
 * Operator-only, as there is no other way to know what it does.
 */
class McpServersConnector(
    private val store: CredentialStore,
    private val client: McpHttpClient = McpHttpClient()
) : Connector {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(McpServerConfig.serializer())
    private val stateLock = Any()

    @Volatile
    private var servers: List<McpServerConfig> = store.loadRaw(ID)
        ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
        .orEmpty()

    /** Exposed tool name → (server id, the server's own tool name), for the servers that are on. */
    @Volatile
    private var routes: Map<String, Pair<String, String>> = emptyMap()

    /** Live sessions by server id. Dropped when a server is edited or removed. */
    private val sessions = mutableMapOf<String, McpHttpClient.Session>()

    init {
        register()
    }

    override val id = ID
    override val displayName = "MCP servers"
    override val description = "Your own Model Context Protocol servers, over Streamable HTTP."
    override val spec = ConnectorCatalog.MCP
    override val toolNames: Set<String>
        get() = routes.keys

    fun servers(): List<McpServerConfig> = servers

    fun server(id: String): McpServerConfig? = servers.firstOrNull { it.id == id }

    /** Exposed name → server tool name for [server], as the model sees them. */
    fun exposedNames(server: McpServerConfig): Map<String, String> =
        McpToolNames.exposedNames(
            McpToolNames.slugs(servers)[server.id] ?: McpToolNames.slug(server.name),
            server.tools
        )

    override fun isConnected(): Boolean = servers.isNotEmpty()

    override fun statusLine(strings: StringLookup): String {
        val list = servers
        if (list.isEmpty()) return strings(R.string.connector_not_connected)
        return strings(R.string.mcp_status_summary, list.count { it.enabled }, list.size, routes.size)
    }

    /** Removes every server. */
    override fun disconnect() {
        synchronized(stateLock) {
            servers = emptyList()
            sessions.clear()
            store.clear(ID)
            register()
        }
    }

    /**
     * Checks [url], [token] and [headers] and contacts the server, without
     * saving anything. The card's "Test connection".
     */
    suspend fun probe(url: String, token: String, headers: Map<String, String>, strings: StringLookup): McpProbe {
        validateMcpServer(url, token, headers)?.let { return McpProbe.Failed(problemMessage(it, strings)) }
        val draft = McpServerConfig(id = "probe", name = "probe", url = url.trim(), token = token, headers = headers)
        return try {
            val session = client.session(draft.endpoint())
            val info = session.initialize()
            McpProbe.Ok(info, session.listTools())
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpException) {
            McpProbe.Failed(failureMessage(e, strings))
        }
    }

    /**
     * Adds a server, or updates the one with [id], after a successful probe.
     * Nothing is saved when the server cannot be reached. Returns the message
     * for the card and whether it was saved.
     */
    suspend fun save(
        id: String?,
        name: String,
        url: String,
        token: String,
        headers: Map<String, String>,
        strings: StringLookup
    ): Pair<Boolean, String> {
        if (name.isBlank()) return false to strings(R.string.mcp_error_name)
        val probe = probe(url, token, headers, strings)
        if (probe is McpProbe.Failed) return false to probe.message
        probe as McpProbe.Ok
        val tools = usableTools(probe.tools)
        val saved = synchronized(stateLock) {
            val existing = id?.let { server(it) }
            if (id != null && existing == null) return false to strings(R.string.mcp_removed_while_saving)
            val config = McpServerConfig(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name.trim(),
                url = url.trim(),
                token = token.trim(),
                headers = headers,
                enabled = existing?.enabled ?: true,
                tools = tools,
                serverName = probe.server.name
            )
            servers = if (existing == null) servers + config else servers.map { if (it.id == config.id) config else it }
            sessions.remove(config.id)
            persist()
            register()
            config
        }
        return true to connectedMessage(saved, probe.tools.size, strings)
    }

    fun remove(id: String) {
        synchronized(stateLock) {
            servers = servers.filterNot { it.id == id }
            sessions.remove(id)
            persist()
            register()
        }
    }

    fun setServerEnabled(id: String, enabled: Boolean) {
        synchronized(stateLock) {
            servers = servers.map { if (it.id == id) it.copy(enabled = enabled) else it }
            persist()
            register()
        }
    }

    /** Re-reads one server's tools. Returns the message for the card. */
    suspend fun refresh(id: String, strings: StringLookup): String {
        val config = server(id) ?: return strings(R.string.mcp_removed_while_saving)
        return try {
            val tools = usableTools(sessionFor(config).listTools())
            commit(config) { it.copy(tools = tools, lastError = null) }
            strings.quantity(R.plurals.mcp_tools_count, tools.size, tools.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpException) {
            val message = failureMessage(e, strings)
            commit(config) { it.copy(lastError = message) }
            message
        }
    }

    /** Refreshes every server that is on, for the Connectors page's "Refresh all" and auto-sync. */
    override suspend fun refreshTools(strings: StringLookup): String = coroutineScope {
        servers.filter { it.enabled }.map { async { refresh(it.id, strings) } }.awaitAll()
        statusLine(strings)
    }

    /** Runs exposed tool [exposedName] on its server. */
    suspend fun callTool(exposedName: String, args: JsonObject): ToolResult {
        val (serverId, toolName) = routes[exposedName]
            ?: return ToolResult.error("Tool '$exposedName' belongs to no MCP server that is switched on.")
        val config = server(serverId) ?: return ToolResult.error("The MCP server for '$exposedName' was removed.")
        return try {
            val output = sessionFor(config).callTool(toolName, args)
            if (output.isError) ToolResult.error(output.text) else ToolResult.ok(output.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpException) {
            ToolResult.error("MCP server '${config.name}' failed to run '$toolName': ${e.message}")
        }
    }

    private fun sessionFor(config: McpServerConfig): McpHttpClient.Session = synchronized(stateLock) {
        sessions.getOrPut(config.id) { client.session(config.endpoint()) }
    }

    /** Applies [change] to the stored server, unless it was edited or removed since [seen] was read. */
    private fun commit(seen: McpServerConfig, change: (McpServerConfig) -> McpServerConfig) {
        synchronized(stateLock) {
            val current = server(seen.id) ?: return
            if (current.url != seen.url || current.token != seen.token || current.headers != seen.headers) return
            servers = servers.map { if (it.id == seen.id) change(it) else it }
            persist()
            register()
        }
    }

    private fun persist() {
        if (servers.isEmpty()) store.clear(ID) else store.saveRaw(ID, json.encodeToString(serializer, servers))
    }

    /**
     * Registers the tools of every server that is on, replacing what this
     * connector had registered. Called under [stateLock] (or from the
     * constructor), so two changes can't register out of order.
     */
    private fun register() {
        val list = servers
        val slugs = McpToolNames.slugs(list)
        val definitions = mutableListOf<ToolDefinition>()
        val readOnly = mutableSetOf<String>()
        val newRoutes = mutableMapOf<String, Pair<String, String>>()
        list.filter { it.enabled }.forEach { server ->
            val byName = server.tools.associateBy { it.name }
            McpToolNames.exposedNames(slugs.getValue(server.id), server.tools).forEach { (exposed, original) ->
                val tool = byName.getValue(original)
                definitions += ToolDefinition(
                    function = FunctionDefinition(
                        exposed,
                        "[MCP server \"${server.name}\"] ${tool.description}".take(MAX_DESCRIPTION),
                        tool.inputSchema
                    )
                )
                if (tool.readOnly) readOnly += exposed
                newRoutes[exposed] = server.id to original
            }
        }
        val registered = ToolRegistry.setDynamicTools(ID, definitions, readOnly)
        routes = newRoutes.filterKeys { it in registered }
    }

    private fun connectedMessage(config: McpServerConfig, offered: Int, strings: StringLookup): String {
        val label = config.serverName.ifBlank { hostOf(config.url) }
        val tools = strings.quantity(R.plurals.mcp_tools_count, config.tools.size, config.tools.size)
        val capped = if (offered > MAX_TOOLS) " " + strings(R.string.mcp_tools_capped, MAX_TOOLS) else ""
        return strings(R.string.mcp_connected, label, tools) + capped
    }

    fun failureMessage(e: McpException, strings: StringLookup): String = when (e.kind) {
        McpFailure.AUTH -> strings(R.string.mcp_error_auth, e.httpCode)
        McpFailure.NOT_FOUND -> strings(R.string.mcp_error_not_found)
        McpFailure.OLD_TRANSPORT -> strings(R.string.mcp_error_old_transport)
        McpFailure.NOT_MCP -> strings(R.string.mcp_error_not_mcp, e.detail.take(DETAIL_CHARS))
        McpFailure.HTTP -> strings(R.string.mcp_error_http, e.httpCode, e.detail.take(DETAIL_CHARS))
        McpFailure.RPC -> strings(R.string.mcp_error_rpc, e.detail.take(DETAIL_CHARS))
        McpFailure.NETWORK -> strings(R.string.mcp_error_network, e.detail.take(DETAIL_CHARS))
    }

    companion object {
        const val ID = "mcp"

        /** Tools kept per server: past this, a server costs more prompt than it is likely worth. */
        const val MAX_TOOLS = 64
        private const val MAX_DESCRIPTION = 1024
        private const val DETAIL_CHARS = 200

        /** The tools a model can be offered: object schemas only, capped at [MAX_TOOLS]. */
        internal fun usableTools(tools: List<McpTool>): List<McpTool> =
            tools.mapNotNull { tool -> normalizedInputSchema(tool.inputSchema)?.let { tool.copy(inputSchema = it) } }
                .take(MAX_TOOLS)

        fun problemMessage(problem: McpConfigProblem, strings: StringLookup): String = when (problem) {
            McpConfigProblem.BAD_URL -> strings(R.string.mcp_error_bad_url)
            McpConfigProblem.CLEARTEXT_WITH_SECRET -> strings(R.string.mcp_error_cleartext)
            McpConfigProblem.BAD_HEADER -> strings(R.string.mcp_error_bad_header)
            McpConfigProblem.BAD_TOKEN -> strings(R.string.mcp_error_bad_token)
        }

        fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host ?: url
    }
}

/** Routes the exposed MCP tool names to [McpServersConnector]. */
class McpServerTools(private val backend: () -> McpServersConnector?) : ToolRouter {

    override val toolNames: Set<String>
        get() = backend()?.toolNames.orEmpty()

    override suspend fun execute(name: String, args: JsonObject): ToolResult =
        backend()?.callTool(name, args)
            ?: ToolResult.error("No MCP servers are set up. Ask the user to add one in Settings → Connectors.")
}
