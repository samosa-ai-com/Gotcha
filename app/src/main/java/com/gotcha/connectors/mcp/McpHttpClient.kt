package com.gotcha.connectors.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A tool an MCP server advertises through `tools/list`. [readOnly] is the
 * server's own `readOnlyHint` annotation: MCP has no other way to say a tool
 * only reads, so a tool without it is treated as one that acts.
 */
@Serializable
data class McpTool(
    val name: String,
    val description: String = "",
    val inputSchema: JsonObject,
    val readOnly: Boolean = false
)

/** Who answered `initialize`. */
data class McpServerInfo(val name: String, val version: String, val protocolVersion: String)

/** A `tools/call` outcome, as text for the model. */
data class McpToolOutput(val isError: Boolean, val text: String)

/** Why an MCP request failed, so the connector can say it in the user's language. */
enum class McpFailure {
    /** 401 or 403: the token or headers were refused. */
    AUTH,

    /** 404 on the first request: nothing at that URL. */
    NOT_FOUND,

    /** The server speaks the 2024-11-05 HTTP+SSE transport, which this client does not. */
    OLD_TRANSPORT,

    /** It answered, but not with MCP's JSON-RPC. */
    NOT_MCP,

    /** A redirect the client will not follow itself (another host, a downgrade). */
    REDIRECT,

    /** Any other HTTP status. */
    HTTP,

    /** A JSON-RPC error from the server (unknown tool, bad arguments, ...). */
    RPC,

    /** DNS, refused connection, TLS, timeout. */
    NETWORK
}

/** An MCP failure: [kind] for the message, [detail] for what the server or the socket said. */
class McpException(
    val kind: McpFailure,
    val detail: String,
    val httpCode: Int = 0,
    cause: Throwable? = null
) : IOException("$kind${if (httpCode != 0) " ($httpCode)" else ""}: $detail", cause)

/** Throws [McpFailure.NOT_MCP]: the server answered, but not with what MCP promises. */
private fun notMcp(detail: String): Nothing = throw McpException(McpFailure.NOT_MCP, detail)

/** Where a server lives and what every request to it carries (auth and custom headers). */
data class McpEndpoint(val url: String, val headers: Map<String, String> = emptyMap())

/**
 * An MCP client for the **Streamable HTTP** transport (MCP 2025-06-18), for
 * servers the user adds themselves (issue #130).
 *
 * Home Assistant's client ([com.gotcha.connectors.homeassistant.HomeAssistantMcpClient])
 * gets away with three bare round trips because that server keeps no state.
 * A general server may not, so this one also:
 *  - sends `notifications/initialized` after `initialize`;
 *  - keeps the `Mcp-Session-Id` the server hands out, sends it and the
 *    negotiated `MCP-Protocol-Version` on every later request, and starts a
 *    new session once when the server says the old one is gone (404);
 *  - follows `tools/list` pages through `nextCursor`;
 *  - reads a `text/event-stream` reply event by event and stops at the
 *    response whose id matches, skipping the server's own notifications and
 *    requests, so a stream the server keeps open cannot hang the call;
 *  - caps how much of a reply it reads, counted as bytes arrive;
 *  - follows a redirect only when it cannot leak anything: same origin
 *    (scheme, host and port), or an http-to-https upgrade on the same host.
 *    Anything else — another host, a port change, an https-to-http downgrade —
 *    fails as [McpFailure.REDIRECT] naming where it points, so the user can
 *    enter that URL themselves instead of sending the token somewhere
 *    [validateMcpServer] never saw, perhaps over plain http.
 *
 * Stdio servers and the older HTTP+SSE transport are out of scope; the latter
 * is recognised and reported as [McpFailure.OLD_TRANSPORT].
 */
class McpHttpClient(
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
) {

    /** Whatever client is passed in, redirects are handled above, not by OkHttp. */
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /** One server's session. Requests on it are serialized, which also keeps re-initialising race-free. */
    inner class Session internal constructor(initialEndpoint: McpEndpoint) {
        /**
         * Where requests go. The slash-tolerant retry may move it to the
         * working spelling; the setter stays private so all writes happen
         * under the session mutex.
         */
        var endpoint: McpEndpoint = initialEndpoint
            private set
        private val mutex = Mutex()
        private var sessionId: String? = null
        private var protocolVersion: String? = null

        /** Set by the first successful [initialize]. */
        var server: McpServerInfo? = null
            private set

        suspend fun initialize(): McpServerInfo = mutex.withLock { initializeLocked() }

        suspend fun listTools(): List<McpTool> = mutex.withLock {
            val tools = mutableListOf<McpTool>()
            var cursor: String? = null
            var pages = 0
            do {
                val params = buildJsonObject { cursor?.let { put("cursor", it) } }
                val result = requestLocked("tools/list", params)
                (result["tools"] as? JsonArray)?.mapNotNullTo(tools) { parseTool(it) }
                cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                pages++
            } while (cursor != null && pages < MAX_TOOL_PAGES)
            tools
        }

        suspend fun callTool(name: String, args: JsonObject): McpToolOutput = mutex.withLock {
            val result = requestLocked(
                "tools/call",
                buildJsonObject {
                    put("name", name)
                    put("arguments", args)
                }
            )
            toolOutput(result)
        }

        private suspend fun initializeLocked(): McpServerInfo {
            sessionId = null
            protocolVersion = null
            val configured = endpoint
            val reply = try {
                exchange(this, initializeBody(), firstRequest = true)
            } catch (e: McpException) {
                retryInitialize(e)
            }
            var ok = false
            try {
                val server = initializedServer(reply)
                ok = true
                return server
            } finally {
                // The fallback spelling may answer but not as MCP (RPC error,
                // no result, no version): don't stick to a spelling that never
                // worked. Cancellation restores too.
                if (!ok) endpoint = configured
            }
        }

        private suspend fun initializedServer(reply: Reply): McpServerInfo {
            sessionId = reply.sessionId
            val result = reply.result()
            val version = (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull
                ?: notMcp("initialize returned no protocolVersion")
            protocolVersion = version
            val info = result["serverInfo"] as? JsonObject
            val server = McpServerInfo(
                name = (info?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                version = (info?.get("version") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                protocolVersion = version
            )
            // A server that is loose about this one notification still works; only
            // a refusal of the credentials is worth stopping for.
            try {
                exchange(this, notification("notifications/initialized"), firstRequest = false)
            } catch (e: McpException) {
                if (e.kind == McpFailure.AUTH) throw e
            }
            this.server = server
            return server
        }

        /**
         * "/mcp" and "/mcp/" are the same thing: when the server 404s one spelling
         * before any session exists, try the other once (issue #154) rather than
         * fail the whole setup. A second failure restores the configured URL and
         * reports the retry's error, so the message describes the last attempt.
         */
        private suspend fun retryInitialize(first: McpException): Reply {
            val original = endpoint
            val alternate = slashToggled(original.url)
            if (first.kind != McpFailure.NOT_FOUND || alternate == null) throw first
            endpoint = original.copy(url = alternate)
            var ok = false
            try {
                val reply = exchange(this, initializeBody(), firstRequest = true)
                ok = true
                return reply
            } finally {
                // Cancellation included: a failed retry must not leave the
                // alternate spelling stored.
                if (!ok) endpoint = original
            }
        }

        /** One request, initialising first if needed, and once more if the server dropped the session. */
        private suspend fun requestLocked(method: String, params: JsonObject): JsonObject {
            if (protocolVersion == null) initializeLocked()
            return try {
                exchange(this, rpcRequest(method, params), firstRequest = false).result()
            } catch (e: McpException) {
                if (e.kind != McpFailure.NOT_FOUND || sessionId == null) throw e
                initializeLocked()
                exchange(this, rpcRequest(method, params), firstRequest = false).result()
            }
        }

        internal fun headers(): Map<String, String> = buildMap {
            sessionId?.let { put(HEADER_SESSION_ID, it) }
            protocolVersion?.let { put(HEADER_PROTOCOL_VERSION, it) }
        }
    }

    /** A new session with [endpoint]; nothing is sent until its first call. */
    fun session(endpoint: McpEndpoint): Session = Session(endpoint)

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json".toMediaType()
    private val nextId = AtomicLong(1)

    /** A JSON-RPC message to send: [id] is null for a notification. */
    private class Outgoing(val id: Long?, val body: JsonObject)

    /** What came back: the matching response (null for a notification) and the session id header. */
    private class Reply(val message: JsonObject?, val sessionId: String?) {
        fun result(): JsonObject {
            val msg = message ?: notMcp("empty reply")
            (msg["error"] as? JsonObject)?.let { err ->
                throw McpException(
                    McpFailure.RPC,
                    (err["message"] as? JsonPrimitive)?.contentOrNull ?: err.toString()
                )
            }
            return msg["result"] as? JsonObject ?: notMcp("reply has no result")
        }
    }

    private fun rpcRequest(method: String, params: JsonObject): Outgoing {
        val id = nextId.getAndIncrement()
        return Outgoing(
            id,
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }
        )
    }

    private fun notification(method: String) = Outgoing(
        null,
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
        }
    )

    /**
     * One POST. Every failure leaves as an [McpException], including a timeout
     * while the reply is still being read and a header OkHttp refuses to send,
     * so callers have one exception type to handle.
     */
    private suspend fun exchange(session: Session, out: Outgoing, firstRequest: Boolean): Reply =
        withContext(Dispatchers.IO) {
            try {
                post(session, out, firstRequest)
            } catch (e: IOException) {
                throw asMcp(e)
            } catch (e: IllegalArgumentException) {
                throw asMcp(e)
            }
        }

    private fun post(session: Session, out: Outgoing, firstRequest: Boolean): Reply {
        var url = session.endpoint.url
        var lastCode = 0
        repeat(MAX_REDIRECTS + 1) {
            val response = executePost(session, out, url)
            try {
                if (response.isSuccessful) {
                    val sessionId = response.header(HEADER_SESSION_ID)
                    val message = if (out.id == null) null else readResponse(response, out.id)
                    return Reply(message, sessionId)
                }
                lastCode = response.code
                val location = response.header("Location")
                // Only the codes that keep POST semantics are replayed (301,
                // 302, 307, 308). A 300 or 303 answers a GET instead, so those
                // fail here rather than repeat a side-effecting tool call.
                if (response.code in FOLLOW_CODES && location != null) {
                    url = redirectTarget(url, location, response.code)
                } else {
                    throw httpFailure(session, response, firstRequest, url)
                }
            } finally {
                response.close()
            }
        }
        throw McpException(McpFailure.HTTP, "too many redirects", lastCode)
    }

    /**
     * The URL with its trailing slash toggled: "/mcp" becomes "/mcp/" and back.
     * Null when there is nothing to toggle (unparseable, or the bare root).
     * Query and fragment survive; the rest of the URL is untouched. Visible
     * for tests.
     */
    internal fun slashToggled(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        val path = parsed.encodedPath
        if (path == "/") return null
        val toggled = if (path.endsWith("/")) path.dropLast(1) else "$path/"
        return parsed.newBuilder().encodedPath(toggled).build().toString()
    }

    private fun initializeBody(): Outgoing = rpcRequest(
        "initialize",
        buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", buildJsonObject {})
            put(
                "clientInfo",
                buildJsonObject {
                    put("name", CLIENT_NAME)
                    put("version", CLIENT_VERSION)
                }
            )
        }
    )

    /**
     * Where a redirect points, when it is safe to follow: the same origin
     * (scheme, host and port — the token stays where the user put it), or an
     * http-to-https upgrade on the same host. Anything else throws
     * [McpFailure.REDIRECT] carrying the destination, so the caller can ask
     * the user to enter that URL instead. Relative locations resolve against
     * the request URL. Visible for tests.
     */
    internal fun redirectTarget(currentUrl: String, location: String, code: Int): String {
        val base = currentUrl.toHttpUrlOrNull()
        val target = base?.resolve(location)
        if (base == null || target == null) throw McpException(McpFailure.REDIRECT, location, code)
        val sameOrigin = target.scheme == base.scheme && target.host == base.host && target.port == base.port
        val upgrade = base.scheme == "http" && target.scheme == "https" && target.host == base.host
        if (!sameOrigin && !upgrade) throw McpException(McpFailure.REDIRECT, target.toString(), code)
        return target.toString()
    }

    private fun executePost(session: Session, out: Outgoing, url: String): Response {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/event-stream")
            .post(out.body.toString().toRequestBody(jsonMediaType))
        session.endpoint.headers.forEach { (k, v) -> builder.header(k, v) }
        session.headers().forEach { (k, v) -> builder.header(k, v) }
        return client.newCall(builder.build()).execute()
    }

    /** Socket, TLS and timeout errors, and headers OkHttp refuses, as [McpFailure.NETWORK]. */
    private fun asMcp(e: Exception): McpException =
        e as? McpException ?: McpException(McpFailure.NETWORK, e.message ?: e.javaClass.simpleName, cause = e)

    private fun httpFailure(session: Session, r: Response, firstRequest: Boolean, url: String): McpException {
        // For a redirect, where it points is the useful part: the user can enter that URL instead.
        val detail = r.header("Location").takeIf { r.isRedirect }
            ?: runCatching { r.peekBody(ERROR_BODY_CHARS.toLong()).string() }.getOrDefault("").trim()
        return when (r.code) {
            401, 403 -> McpException(McpFailure.AUTH, detail, r.code)
            // The old transport answers POSTs on its SSE URL with 404/405 and
            // streams an `endpoint` event to a GET instead.
            400, 404, 405 -> if (firstRequest && speaksOldTransport(session, url)) {
                McpException(McpFailure.OLD_TRANSPORT, detail, r.code)
            } else if (r.code == 404) {
                McpException(McpFailure.NOT_FOUND, detail, r.code)
            } else {
                McpException(McpFailure.HTTP, detail, r.code)
            }
            else -> McpException(McpFailure.HTTP, detail, r.code)
        }
    }

    /** True when a GET opens an event stream: the 2024-11-05 HTTP+SSE transport. Reads only the headers. */
    private fun speaksOldTransport(session: Session, url: String): Boolean = runCatching {
        val builder = Request.Builder().url(url).header("Accept", "text/event-stream").get()
        session.endpoint.headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { r ->
            r.isSuccessful && r.header("Content-Type").orEmpty().startsWith("text/event-stream")
        }
    }.getOrDefault(false)

    private fun readResponse(r: Response, id: Long): JsonObject {
        val body = r.body ?: notMcp("empty body")
        val source = CappedSource(body.source(), MAX_REPLY_BYTES).buffer()
        val type = r.header("Content-Type").orEmpty()
        return if (type.startsWith("text/event-stream")) {
            readEventStream(source, id)
        } else {
            val text = source.readUtf8()
            val element = parseJson(text) ?: notMcp(text.take(ERROR_BODY_CHARS))
            matching(element, id) ?: notMcp("no response with id $id")
        }
    }

    /**
     * Walks SSE events until one carries the response to [id]. Notifications and
     * server-to-client requests in between are skipped; there is no client
     * capability that would need answering them.
     */
    private fun readEventStream(source: BufferedSource, id: Long): JsonObject {
        val data = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty()) {
                        parseJson(data.toString())?.let { matching(it, id) }?.let { return it }
                        data.clear()
                    }
                }
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
            }
        }
        if (data.isNotEmpty()) parseJson(data.toString())?.let { matching(it, id) }?.let { return it }
        notMcp("event stream ended without a response")
    }

    /**
     * Fails once more than [cap] bytes have come off the network. It sits under
     * the buffer, so it also bounds a line that never ends: `readUtf8Line`
     * would otherwise buffer all of it before anyone could count.
     */
    private class CappedSource(delegate: Source, private val cap: Long) : ForwardingSource(delegate) {
        private var total = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = super.read(sink, minOf(byteCount, cap - total + 1))
            if (n > 0) total += n
            if (total > cap) notMcp("reply larger than $cap bytes")
            return n
        }
    }

    private fun parseJson(text: String): JsonElement? =
        if (text.isBlank()) null else runCatching { json.parseToJsonElement(text) }.getOrNull()

    /** The response to [id] in [element], which may be one message or a JSON-RPC batch. */
    private fun matching(element: JsonElement, id: Long): JsonObject? = when (element) {
        is JsonObject -> element.takeIf { (it["id"] as? JsonPrimitive)?.longOrNull == id && "method" !in it }
        is JsonArray -> element.firstNotNullOfOrNull { matching(it, id) }
        else -> null
    }

    private fun parseTool(element: JsonElement): McpTool? {
        val obj = element as? JsonObject ?: return null
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val annotations = obj["annotations"] as? JsonObject
        return McpTool(
            name = name,
            description = (obj["description"] as? JsonPrimitive)?.contentOrNull
                ?: (obj["title"] as? JsonPrimitive)?.contentOrNull
                ?: "",
            inputSchema = obj["inputSchema"] as? JsonObject ?: buildJsonObject { put("type", "object") },
            readOnly = (annotations?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull == true
        )
    }

    /** Flattens `tools/call` content to text: images and audio are named, not inlined. */
    private fun toolOutput(result: JsonObject): McpToolOutput {
        val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true
        val parts = (result["content"] as? JsonArray).orEmpty().mapNotNull { el ->
            val item = el as? JsonObject ?: return@mapNotNull null
            fun str(key: String, from: JsonObject = item) = (from[key] as? JsonPrimitive)?.contentOrNull
            when (str("type")) {
                "text" -> str("text")
                "image", "audio" -> "[${str("type")} ${str("mimeType").orEmpty()} not shown]"
                "resource_link" -> "[link: ${str("name").orEmpty()} ${str("uri").orEmpty()}]".replace("  ", " ")
                "resource" -> (item["resource"] as? JsonObject)?.let { res ->
                    str("text", res) ?: "[resource: ${str("uri", res).orEmpty()}]"
                }
                else -> item.toString()
            }
        }
        val structured = result["structuredContent"]?.takeUnless { it is JsonNull }
        val text = parts.joinToString("\n").ifBlank { structured?.toString().orEmpty() }.ifBlank { "(no output)" }
        val capped = if (text.length > MAX_OUTPUT_CHARS) {
            text.take(MAX_OUTPUT_CHARS) + "\n[output cut at $MAX_OUTPUT_CHARS characters]"
        } else {
            text
        }
        return McpToolOutput(isError, capped)
    }

    companion object {
        /** The newest MCP version this client speaks; the server answers with the one it will use. */
        const val PROTOCOL_VERSION = "2025-06-18"
        private const val CLIENT_NAME = "Gotcha"
        private const val CLIENT_VERSION = "1.0"
        const val HEADER_SESSION_ID = "Mcp-Session-Id"
        const val HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"

        private const val MAX_TOOL_PAGES = 20
        private const val MAX_REDIRECTS = 5

        /** Redirect codes replayed as POST; 300/303 answer a GET instead. */
        private val FOLLOW_CODES = setOf(301, 302, 307, 308)
        private const val MAX_REPLY_BYTES = 4L * 1024 * 1024
        private const val MAX_OUTPUT_CHARS = 50_000
        private const val ERROR_BODY_CHARS = 300
    }
}
