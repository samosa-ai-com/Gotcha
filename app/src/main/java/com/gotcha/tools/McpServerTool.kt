package com.gotcha.tools

import android.content.Context
import com.gotcha.connectors.ConnectorRegistry
import com.gotcha.connectors.mcp.McpConfigProblem
import com.gotcha.connectors.mcp.McpServerConfig
import com.gotcha.connectors.mcp.McpServersConnector
import com.gotcha.connectors.mcp.isCleartextWithSecret
import com.gotcha.connectors.mcp.validateMcpServer
import com.gotcha.data.SettingsRepository
import com.gotcha.i18n.StringLookup
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val MAX_NAME = 60
private const val MAX_TOOL_NAMES_LISTED = 20

private fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host ?: "(invalid address)"

private fun toolList(names: List<String>): String {
    val shown = names.take(MAX_TOOL_NAMES_LISTED).joinToString(", ")
    val more = names.size - MAX_TOOL_NAMES_LISTED
    return if (more > 0) "$shown and $more more" else shown
}

private fun sameOrigin(a: String, b: String): Boolean {
    val x = a.toHttpUrlOrNull() ?: return false
    val y = b.toHttpUrlOrNull() ?: return false
    return x.scheme == y.scheme && x.host == y.host && x.port == y.port
}

private fun checkServer(name: String, url: String, token: String, headers: Map<String, String>): String? {
    if (name.isBlank()) return "'name' must not be blank"
    if (name.length > MAX_NAME) return "'name' must be at most $MAX_NAME characters"
    return when (validateMcpServer(url, token, headers)) {
        McpConfigProblem.BAD_URL -> "'url' must be a full http:// or https:// address"
        McpConfigProblem.BAD_HEADER -> "a header name has characters a header can't have"
        McpConfigProblem.BAD_TOKEN -> "the token or a header value has characters a header can't carry"
        null -> null
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** `headers` as a name → value map; null when absent. */
private fun JsonObject.headers(): Result<Map<String, String>?> {
    val raw = this["headers"] ?: return Result.success(null)
    val obj = raw as? JsonObject
        ?: return Result.failure(IllegalArgumentException("'headers' must be an object of header name → value"))
    val headers = obj.mapValues { (name, value) ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return Result.failure(IllegalArgumentException("header '$name' must have a string value"))
    }
    return Result.success(headers)
}

/**
 * `list_mcp_servers` and `manage_mcp_servers`: the assistant's view of, and
 * hands on, the user's own MCP servers (the connector from issue #130).
 *
 * Adding a server hands Gotcha new tools and sends a secret to an address, so
 * it is the change a prompt injection would most like to make. Hence:
 *  - every write is shown to the user first, and adding a server or moving
 *    one to a new address asks even when the chat skips its approvals;
 *  - a saved token or header never follows a server to a different host;
 *  - nothing the tools return or log carries a token, a header value, or the
 *    path of a server's address (some services put their key in it).
 */
object McpServerTool {

    const val LIST = "list_mcp_servers"
    const val MANAGE = "manage_mcp_servers"

    /** Marker [ToolExecutor] returns so [com.gotcha.agent.AgentEngine] asks before writing. */
    const val CONFIRM_PREFIX = "CONFIRM_MCP_SERVER:"

    /** Arguments never written to the audit log for [MANAGE]: secrets, and the address that may hold one. */
    val AUDIT_REDACTED_KEYS = setOf("token", "headers", "url")

    /** A validated write, waiting for the user's answer. */
    sealed interface Request {
        data class Add(val name: String, val url: String, val token: String, val headers: Map<String, String>) :
            Request

        data class Edit(
            val before: McpServerConfig,
            val name: String,
            val url: String,
            val token: String,
            val headers: Map<String, String>,
            /** True when the saved token or headers were dropped because the host changed. */
            val droppedSecrets: Boolean
        ) : Request

        data class Remove(val server: McpServerConfig) : Request
        data class SetEnabled(val server: McpServerConfig, val enabled: Boolean) : Request
    }

    /** A [Request] and the model's one-line reason, as carried across the confirmation. */
    data class Pending(val request: Request, val reason: String?)

    /**
     * Requests waiting for an answer, by an opaque id. The confirmation marker
     * carries only the id: tool results are written to the audit log, and an
     * added server's token must not be.
     */
    class PendingRequests {
        private val pending = ConcurrentHashMap<String, Pending>()

        fun put(request: Pending): String = UUID.randomUUID().toString().also { pending[it] = request }

        /** The request with [id], once: a marker can't be replayed. */
        fun take(id: String): Pending? = pending.remove(id)
    }

    /** The user's MCP servers connector, once [ConnectorRegistry] is set up (ToolExecutor's init does it). */
    fun connector(): McpServersConnector? = ConnectorRegistry.byId(McpServersConnector.ID) as? McpServersConnector

    /** Whether the user has the MCP servers connector switched on. */
    fun connectorOn(context: Context): Boolean =
        McpServersConnector.ID !in SettingsRepository(context).load().disabledConnectors

    /**
     * `manage_mcp_servers`: refresh runs at once; every other action is validated
     * and parked in [pending], and only its id goes back in the marker.
     */
    suspend fun manage(args: JsonObject, pending: PendingRequests, strings: StringLookup): ToolResult {
        val connector = connector() ?: return ToolResult.error("MCP servers are not available here.")
        if (args.string("action") == "refresh") {
            val id = args.string("server_id") ?: return ToolResult.error("Missing required parameter: server_id")
            val server = connector.server(id)
                ?: return ToolResult.error("No MCP server has server_id '$id'; $LIST shows the ids.")
            return ToolResult.ok("Refreshed \"${server.name}\": ${connector.refresh(id, strings)}")
        }
        val request = parse(args, connector.servers()).getOrElse {
            return ToolResult.error("Nothing was changed: ${it.message}.")
        }
        return ToolResult.ok(CONFIRM_PREFIX + pending.put(Pending(request, args.string("reason"))))
    }

    // ---- reading ----

    /** The address with its path and query hidden: `https://host:port/…`. */
    fun redactedAddress(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return "(invalid address)"
        val origin = "${parsed.scheme}://${parsed.host}" +
            if (parsed.port != okhttp3.HttpUrl.defaultPort(parsed.scheme)) ":${parsed.port}" else ""
        val hasMore = parsed.encodedPath != "/" || parsed.encodedQuery != null
        return if (hasMore) "$origin/…" else origin
    }

    /** What `list_mcp_servers` returns. Never a token, a header value or an address path. */
    fun list(connector: McpServersConnector, connectorOn: Boolean): String = buildString {
        val servers = connector.servers()
        if (servers.isEmpty()) {
            append("No MCP servers are set up. $MANAGE with action \"add\" adds one when the user gives its address.")
            return@buildString
        }
        if (!connectorOn) {
            append(
                "The MCP servers connector is switched off, so none of these tools are available. " +
                    "update_gotcha_settings with connectors {\"mcp\": true} turns it back on.\n"
            )
        }
        servers.forEach { server ->
            val tools = connector.exposedNames(server).keys.toList()
            append("- server_id: ").append(server.id)
            append(" | name: ").append(server.name)
            append(" | address: ").append(redactedAddress(server.url))
            append(" | ").append(if (server.enabled) "on" else "off")
            append(" | token: ").append(if (server.token.isBlank()) "none" else "set")
            if (server.headers.isNotEmpty()) append(" | headers: ").append(server.headers.keys.joinToString(", "))
            if (server.serverName.isNotBlank()) append(" | reports itself as: ").append(server.serverName)
            append(" | ").append(tools.size).append(if (tools.size == 1) " tool" else " tools")
            if (tools.isNotEmpty()) append(": ").append(toolList(tools))
            server.lastError?.let { append(" | last error: ").append(it) }
            append('\n')
        }
    }.trimEnd()

    // ---- validating ----

    /**
     * Validates a write ([args] of [MANAGE], action other than refresh) against
     * the [servers] stored now. Failures carry the message for the model.
     */
    // One branch per action, each short; splitting them up would only scatter the rules.
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun parse(args: JsonObject, servers: List<McpServerConfig>): Result<Request> {
        fun fail(message: String) = Result.failure<Request>(IllegalArgumentException(message))
        val action = args.string("action") ?: return fail("'action' is required")
        if (action == "add") {
            val name = args.string("name")?.trim().orEmpty()
            val url = args.string("url")?.trim().orEmpty()
            if (name.isEmpty()) return fail("'name' is required to add a server")
            if (url.isEmpty()) return fail("'url' is required to add a server")
            val headers = args.headers().getOrElse { return fail(it.message!!) }.orEmpty()
            val token = args.string("token")?.trim().orEmpty()
            servers.firstOrNull { it.url.trimEnd('/') == url.trimEnd('/') }?.let {
                return fail("that address is already added as \"${it.name}\" (server_id ${it.id}); use action \"edit\"")
            }
            checkServer(name, url, token, headers)?.let { return fail(it) }
            return Result.success(Request.Add(name, url, token, headers))
        }
        val id = args.string("server_id") ?: return fail("'server_id' is required for action \"$action\"")
        val server = servers.firstOrNull { it.id == id }
            ?: return fail("no MCP server has server_id '$id'; $LIST shows the ids")
        return when (action) {
            "remove" -> Result.success(Request.Remove(server))
            "set_enabled" -> {
                val enabled = (args["enabled"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                    ?: return fail("'enabled' must be true or false")
                Result.success(Request.SetEnabled(server, enabled))
            }
            "edit" -> parseEdit(args, server).fold({ Result.success(it) }, { fail(it.message!!) })
            else -> fail("unknown action '$action'")
        }
    }

    private fun parseEdit(args: JsonObject, before: McpServerConfig): Result<Request> {
        fun fail(message: String) = Result.failure<Request>(IllegalArgumentException(message))
        val newName = args.string("name")?.trim()
        val newUrl = args.string("url")?.trim()
        val newToken = args.string("token")?.trim()
        val newHeaders = args.headers().getOrElse { return fail(it.message!!) }
        if (newName == null && newUrl == null && newToken == null && newHeaders == null) {
            return fail("give at least one of name, url, token or headers to edit")
        }
        val url = newUrl ?: before.url
        // A secret stays with the host it was given for: a new host gets only what this call supplies.
        val hostChanged = newUrl != null && !sameOrigin(newUrl, before.url)
        val hadSecrets = before.token.isNotBlank() || before.headers.isNotEmpty()
        val token = newToken ?: if (hostChanged) "" else before.token
        val headers = newHeaders ?: if (hostChanged) emptyMap() else before.headers
        val dropped = hostChanged && hadSecrets && (newToken == null || newHeaders == null)
        val name = newName ?: before.name
        checkServer(name, url, token, headers)?.let { return fail(it) }
        return Result.success(Request.Edit(before, name, url, token, headers, dropped))
    }

    // ---- asking ----

    /**
     * True when the request must be asked about even in a chat that skips its
     * approvals: it would send a secret, or hand Gotcha tools, from a new address.
     */
    fun alwaysAsk(request: Request): Boolean = when (request) {
        is Request.Add -> true
        is Request.Edit -> request.url != request.before.url
        is Request.Remove, is Request.SetEnabled -> false
    }

    /** The confirmation text. Shows the full address — it is the user's screen — but never a secret. */
    fun describe(pending: Pending, connector: McpServersConnector): String = buildString {
        pending.reason?.takeIf { it.isNotBlank() }?.let { append("Why: ").append(it.trim()).append("\n\n") }
        when (val r = pending.request) {
            is Request.Add -> {
                append("Add an MCP server:\n")
                append("• Name: ").append(r.name).append('\n')
                append("• Address: ").append(r.url).append('\n')
                secretsLines(r.token, r.headers)
                append('\n').append(TRUST_NOTE)
                cleartextNote(r.url, r.token, r.headers)
            }
            is Request.Edit -> {
                append("Change MCP server \"").append(r.before.name).append("\":\n")
                if (r.name != r.before.name) append("• Name: ${r.before.name} → ${r.name}\n")
                if (r.url != r.before.url) append("• Address: ${r.before.url} → ${r.url}\n")
                if (r.token != r.before.token) {
                    append("• Token: ").append(
                        when {
                            r.token.isBlank() -> "removed"
                            r.before.token.isBlank() -> "added"
                            else -> "replaced"
                        }
                    ).append('\n')
                }
                if (r.headers != r.before.headers) {
                    append("• Extra headers: ")
                        .append(r.headers.keys.joinToString(", ").ifEmpty { "none" })
                        .append(" (values hidden)\n")
                }
                if (r.droppedSecrets) {
                    append("\nThe saved token and headers were for ").append(hostOf(r.before.url))
                        .append(", so they are not sent to the new address.\n")
                }
                if (r.url != r.before.url) append('\n').append(TRUST_NOTE)
                cleartextNote(r.url, r.token, r.headers)
            }
            is Request.Remove -> {
                val tools = connector.exposedNames(r.server).size
                append("Remove MCP server \"").append(r.server.name).append("\" (").append(hostOf(r.server.url))
                append(").\nIts saved token and headers are deleted, and its ").append(tools)
                append(if (tools == 1) " tool goes" else " tools go").append(" away.")
            }
            is Request.SetEnabled -> {
                val tools = connector.exposedNames(r.server).size
                append("Switch MCP server \"").append(r.server.name).append("\" (").append(hostOf(r.server.url))
                append(if (r.enabled) ") on.\nIts " else ") off.\nIts ").append(tools)
                append(if (tools == 1) " tool" else " tools")
                append(if (r.enabled) " become available to Gotcha." else " stop being available to Gotcha.")
            }
        }
    }.trimEnd()

    private fun StringBuilder.secretsLines(token: String, headers: Map<String, String>) {
        append("• Sends a token: ").append(if (token.isBlank()) "no" else "yes").append('\n')
        if (headers.isNotEmpty()) {
            append("• Extra headers: ").append(headers.keys.joinToString(", ")).append(" (values hidden)\n")
        }
    }

    private fun StringBuilder.cleartextNote(url: String, token: String, headers: Map<String, String>) {
        if (isCleartextWithSecret(url, token, headers)) {
            append("\n\nThis address uses plain http outside your network: the token could be read on the way.")
        }
    }

    private const val TRUST_NOTE =
        "Gotcha will contact it now and, if it answers, can use its tools. Only add servers you trust."

    /** One audit-log line for [request]: what and which host, never a secret or an address path. */
    fun auditLine(request: Request): String = when (request) {
        is Request.Add -> "add \"${request.name}\" (${hostOf(request.url)})"
        is Request.Edit -> "edit \"${request.before.name}\" (${hostOf(request.before.url)}" +
            (if (request.url != request.before.url) " → ${hostOf(request.url)}" else "") + ")"
        is Request.Remove -> "remove \"${request.server.name}\" (${hostOf(request.server.url)})"
        is Request.SetEnabled -> "switch ${if (request.enabled) "on" else "off"} \"${request.server.name}\" " +
            "(${hostOf(request.server.url)})"
    }

    // ---- applying ----

    /** Carries out an approved [request]. [connectorOn] says whether the connector switch is on. */
    suspend fun apply(
        request: Request,
        connector: McpServersConnector,
        connectorOn: Boolean,
        strings: StringLookup
    ): ToolResult {
        val offNote = if (connectorOn) {
            ""
        } else {
            " The MCP servers connector is switched off, so its tools stay unavailable until it is turned on."
        }
        return when (request) {
            is Request.Add -> saved(
                connector.save(null, request.name, request.url, request.token, request.headers, strings),
                connector,
                { it.url == request.url && it.name == request.name },
                offNote
            )
            is Request.Edit -> saved(
                connector.save(request.before.id, request.name, request.url, request.token, request.headers, strings),
                connector,
                { it.id == request.before.id },
                offNote
            )
            is Request.Remove -> {
                connector.remove(request.server.id)
                ToolResult.ok("Removed MCP server \"${request.server.name}\" and its tools.")
            }
            is Request.SetEnabled -> {
                if (connector.server(request.server.id) == null) {
                    return ToolResult.error("MCP server \"${request.server.name}\" was removed meanwhile.")
                }
                connector.setServerEnabled(request.server.id, request.enabled)
                val state = if (request.enabled) "on" else "off"
                ToolResult.ok("MCP server \"${request.server.name}\" is $state." + if (request.enabled) offNote else "")
            }
        }
    }

    private fun saved(
        outcome: Pair<Boolean, String>,
        connector: McpServersConnector,
        which: (McpServerConfig) -> Boolean,
        offNote: String
    ): ToolResult {
        val (ok, message) = outcome
        if (!ok) return ToolResult.error("Not saved: $message")
        val server = connector.servers().lastOrNull(which)
        val tools = server?.let { connector.exposedNames(it).keys.toList() }.orEmpty()
        val available = when {
            tools.isEmpty() -> ""
            server?.enabled == false -> " The server is switched off, so its tools are not available."
            else -> " Its tools can be called from the next step: ${toolList(tools)}."
        }
        return ToolResult.ok(message + available + offNote)
    }
}
