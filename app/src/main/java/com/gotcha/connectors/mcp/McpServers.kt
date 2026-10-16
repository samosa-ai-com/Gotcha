package com.gotcha.connectors.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.security.MessageDigest

/**
 * One MCP server the user added (issue #130), as stored in the encrypted
 * connector store. [tools] is the last `tools/list`, so the tools exist after
 * a restart before any network call.
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    /** Sent as `Authorization: Bearer <token>` when not blank. */
    val token: String = "",
    /** Extra request headers, e.g. an API-key header a server wants instead of a bearer token. */
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val tools: List<McpTool> = emptyList(),
    /** `serverInfo.name` from the last successful connect, for the card. */
    val serverName: String = "",
    /** Why the last connect or refresh failed, or null when it worked. Shown on the card. */
    val lastError: String? = null
) {
    fun endpoint(): McpEndpoint = McpEndpoint(
        url = url,
        headers = buildMap {
            putAll(headers)
            if (token.isNotBlank()) put("Authorization", "Bearer ${token.trim()}")
        }
    )
}

/** What is wrong with a server's URL or headers before anything is sent. */
enum class McpConfigProblem { BAD_URL, CLEARTEXT_WITH_SECRET, BAD_HEADER, BAD_TOKEN }

/**
 * Checks a server before it is contacted. Plain http is accepted for the local
 * network (a Home Assistant box, a dev machine), and anywhere when nothing
 * secret would travel with it; a token or header sent over http to the
 * internet could be read on the way, so that is refused.
 */
fun validateMcpServer(url: String, token: String, headers: Map<String, String>): McpConfigProblem? {
    val parsed = url.trim().toHttpUrlOrNull() ?: return McpConfigProblem.BAD_URL
    if (headers.keys.any { !isValidHeaderName(it) }) return McpConfigProblem.BAD_HEADER
    // OkHttp refuses to send anything else, so it is caught here rather than on the wire.
    if (!isHeaderValue(token) || headers.values.any { !isHeaderValue(it) }) return McpConfigProblem.BAD_TOKEN
    val secret = token.isNotBlank() || headers.isNotEmpty()
    if (!parsed.isHttps && secret && !isLocalHost(parsed.host)) return McpConfigProblem.CLEARTEXT_WITH_SECRET
    return null
}

private fun isHeaderValue(value: String): Boolean = value.all { it == '\t' || it in ' '..'~' }

private fun isValidHeaderName(name: String): Boolean =
    name.isNotEmpty() && name.all { it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~" } && name.all { it.code < 128 }

/**
 * True for hosts on the user's own network: localhost, mDNS and home-router
 * names, and private, loopback, link-local and CGNAT (Tailscale) addresses.
 * Never resolves a name: only IP literals are inspected as addresses.
 */
fun isLocalHost(host: String): Boolean {
    val h = host.lowercase().trimEnd('.')
    if (h == "localhost" || LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
    val literal = h.removePrefix("[").removeSuffix("]")
    val looksLikeIp = literal.contains(':') || literal.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
    if (!looksLikeIp) return false
    val address = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
    val bytes = address.address
    val cgnat = bytes.size == 4 && bytes[0].toInt() and 0xFF == 100 && (bytes[1].toInt() and 0xC0) == 64
    val uniqueLocalV6 = bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
    return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress || cgnat || uniqueLocalV6
}

private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".home.arpa", ".internal")

/** Parses the headers field: one `Name: value` per line, blank lines ignored. Null when a line has no colon. */
fun parseHeaderLines(text: String): Map<String, String>? {
    val headers = linkedMapOf<String, String>()
    text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
    }
    return headers
}

fun formatHeaderLines(headers: Map<String, String>): String =
    headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }

/**
 * The names a server's tools get in front of the model: `mcp_<server>__<tool>`.
 *
 * Servers choose their own tool names, and two servers may both have `search`,
 * so each is prefixed with its server's slug. Function names must match
 * `^[a-zA-Z0-9_-]{1,64}$` for the chat APIs Gotcha talks to, so anything else
 * becomes `_`, and a name that is too long, or that clashes after cleaning, is
 * shortened and given a hash of the original.
 */
object McpToolNames {

    const val PREFIX = "mcp_"
    private const val MAX_NAME = 64
    private const val MAX_SLUG = 16
    private const val HASH_CHARS = 6

    /** A short, stable label for [name]: lowercase letters, digits and `_`. */
    fun slug(name: String): String =
        clean(name.lowercase()).trim('_').take(MAX_SLUG).trimEnd('_').ifEmpty { "server" }

    /**
     * Slugs for [servers] in order, made unique by a numeric suffix. The order is
     * the stored order, so a server keeps its slug as long as none before it
     * is renamed into a clash.
     */
    fun slugs(servers: List<McpServerConfig>): Map<String, String> {
        val used = mutableSetOf<String>()
        return servers.associate { server ->
            val base = slug(server.name)
            var candidate = base
            var n = 2
            while (!used.add(candidate)) candidate = "${base.take(MAX_SLUG - 2)}$n".also { n++ }
            server.id to candidate
        }
    }

    /** Exposed name → the server's own tool name, for one server's [tools]. */
    fun exposedNames(slug: String, tools: List<McpTool>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        tools.forEach { tool ->
            var exposed = fit("$PREFIX${slug}__${clean(tool.name)}", tool.name)
            if (exposed in result) exposed = fit(exposed, "${tool.name}#${result.size}", force = true)
            result[exposed] = tool.name
        }
        return result
    }

    private fun clean(s: String): String = s.map {
        if (it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-') it else '_'
    }
        .joinToString("")

    private fun fit(name: String, original: String, force: Boolean = false): String {
        if (name.length <= MAX_NAME && !force) return name
        val hash = MessageDigest.getInstance("SHA-256").digest(original.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(HASH_CHARS)
        return name.take(MAX_NAME - HASH_CHARS - 1) + "_" + hash
    }
}

/**
 * Makes a server's input schema acceptable to the chat APIs: it must be an
 * object schema, and OpenAI-compatible endpoints reject one without
 * `properties`. Null for a schema that is not an object at all.
 */
fun normalizedInputSchema(schema: JsonObject): JsonObject? {
    val type = schema["type"]?.toString()?.trim('"')
    if (type != null && type != "object") return null
    if (type != null && "properties" in schema) return schema
    return JsonObject(
        buildMap {
            putAll(schema)
            put("type", kotlinx.serialization.json.JsonPrimitive("object"))
            if ("properties" !in schema) put("properties", buildJsonObject {})
        }
    )
}
