package com.gotcha.connectors.homeassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tool advertised by the Home Assistant MCP server through `tools/list`.
 * The schema is the server's JSON schema for the tool's arguments.
 */
@Serializable
data class McpToolSchema(
    val name: String,
    val description: String = "",
    val inputSchema: JsonObject
)

/** Outcome of a `tools/call`, fed back to the model. */
data class McpCallResult(
    val success: Boolean,
    val text: String
)

/** HTTP-level MCP failure, e.g. a wrong token (401) or an unconfigured server (404). */
class McpHttpException(val code: Int, message: String) : IOException(message)

/** JSON-RPC-level MCP failure, e.g. an unknown tool or malformed arguments. */
class McpRpcException(message: String) : IOException(message)

/**
 * Minimal in-app MCP client for Home Assistant's **Streamable HTTP** transport
 * (`<ha_url>/api/mcp`), authenticated with a long-lived access token.
 *
 * Home Assistant runs the MCP server statelessly: every POST is answered with a
 * single JSON-RPC response, so this client is just three JSON-RPC round trips
 * (`initialize`, `tools/list`, `tools/call`) over OkHttp — no SSE session, no
 * separate `mcp-proxy` subprocess (which Android cannot spawn). SSE `data:`
 * payloads are still tolerated defensively in case a different server answers
 * that way.
 *
 * JVM-testable via a MockWebServer [endpoint].
 */
@Suppress("TooManyFunctions") // one function per JSON-RPC method, plus shared helpers
class HomeAssistantMcpClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        /** The MCP protocol version this client speaks (HA: MCP 2025-06-18). */
        const val PROTOCOL_VERSION = "2025-06-18"
        private const val CLIENT_NAME = "Gotcha"

        /**
         * Turns a Home Assistant base URL into the MCP endpoint. Accepts either
         * `http://ha.local:8123` or a full `/api/mcp` URL (case-insensitive).
         */
        fun mcpEndpoint(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            return if (trimmed.endsWith("/api/mcp", ignoreCase = true)) {
                trimmed
            } else {
                "$trimmed/api/mcp"
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val nextId = AtomicInteger(1)

    /**
     * `initialize` handshake. Returns the server's result object (protocol
     * version, server info) so the caller can confirm the endpoint is an MCP
     * server before storing credentials.
     */
    suspend fun initialize(endpoint: String, token: String): JsonObject {
        val body = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(nextId.incrementAndGet()))
            put("method", JsonPrimitive("initialize"))
            put(
                "params",
                buildJsonObject {
                    put("protocolVersion", JsonPrimitive(PROTOCOL_VERSION))
                    put("capabilities", buildJsonObject {})
                    put(
                        "clientInfo",
                        buildJsonObject {
                            put("name", JsonPrimitive(CLIENT_NAME))
                            put("version", JsonPrimitive("0.1.0"))
                        }
                    )
                }
            )
        }
        val response = rpc(endpoint, token, body)
        return response["result"]?.jsonObject ?: response
    }

    /** `tools/list` — the server's dynamic tool set, scoped to exposed entities. */
    suspend fun listTools(endpoint: String, token: String): List<McpToolSchema> {
        val body = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(nextId.incrementAndGet()))
            put("method", JsonPrimitive("tools/list"))
            put("params", buildJsonObject {})
        }
        val result = rpc(endpoint, token, body)["result"]?.jsonObject ?: return emptyList()
        val tools = result["tools"] as? JsonArray ?: return emptyList()
        return tools.mapNotNull { element ->
            val obj = element.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            McpToolSchema(
                name = name,
                description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                inputSchema = obj["inputSchema"]?.jsonObject
                    ?: obj["parameters"]?.jsonObject
                    ?: buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("properties", buildJsonObject {})
                    }
            )
        }
    }

    /** `tools/call` — executes a server-defined tool and returns its text output. */
    suspend fun callTool(
        endpoint: String,
        token: String,
        name: String,
        args: JsonObject
    ): McpCallResult {
        val body = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(nextId.incrementAndGet()))
            put("method", JsonPrimitive("tools/call"))
            put(
                "params",
                buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("arguments", args)
                }
            )
        }
        val result = rpc(endpoint, token, body)["result"]?.jsonObject
            ?: return McpCallResult(false, "Home Assistant returned no result for '$name'.")
        val isError = result["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        val text = (result["content"] as? JsonArray)
            ?.joinToString("\n") { el -> el.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: el.toString() }
            .orEmpty()
            .ifBlank { if (isError) "Home Assistant tool '$name' failed." else "(no output)" }
        return McpCallResult(success = !isError, text = text)
    }

    /** One JSON-RPC request: HTTP round trip plus JSON-RPC error surfacing. */
    private suspend fun rpc(endpoint: String, token: String, body: JsonObject): JsonObject =
        withContext(Dispatchers.IO) {
            val response = post(endpoint, token, body)
            response["error"]?.jsonObject?.let { err ->
                val message = err["message"]?.jsonPrimitive?.contentOrNull ?: "MCP request failed"
                throw McpRpcException(message)
            }
            response
        }

    private suspend fun post(endpoint: String, token: String, body: JsonObject): JsonObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(endpoint)
                // Streamable HTTP: HA's endpoint rejects requests whose Accept
                // header does not include application/json.
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw mapHttpError(response.code, bodyText)
                parseBody(bodyText)
            }
        }

    /**
     * Parses the response body. HA answers with a single JSON object; SSE
     * (`data:` frames) is handled defensively by taking the last payload.
     */
    private fun parseBody(body: String): JsonObject {
        if (body.isBlank()) return buildJsonObject {}
        val payload = if (body.startsWith("data:") || body.contains("\ndata:")) {
            body.lineSequence()
                .mapNotNull { line ->
                    if (line.startsWith("data:")) line.removePrefix("data:").trim() else null
                }
                .lastOrNull { it.isNotBlank() }
                ?: throw IOException("MCP server sent an empty SSE response")
        } else {
            body
        }
        return json.parseToJsonElement(payload).jsonObject
    }

    private fun mapHttpError(code: Int, body: String): IOException {
        val detail = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]
                ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: body.take(200)
        val message = when (code) {
            401 ->
                "Home Assistant rejected the access token (401). Re-copy the long-lived " +
                    "token from your profile ▸ Security and reconnect. $detail"
            403 ->
                "Home Assistant refused access (403). The token needs access to the MCP " +
                    "server integration. $detail"
            404 ->
                "Home Assistant returned 404 on the MCP endpoint. Add the \"Model Context " +
                    "Protocol Server\" integration in Settings ▸ Devices & Services first. $detail"
            400 -> "Home Assistant rejected the request (400): $detail"
            else -> "Home Assistant HTTP error ($code): $detail"
        }
        return McpHttpException(code, message)
    }
}
