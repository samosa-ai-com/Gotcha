package com.gotcha.telemetry

import com.gotcha.connectors.mcp.McpToolNames
import com.gotcha.data.LlmProvider
import com.gotcha.tools.ToolDefinitions
import com.gotcha.tools.ToolRegistry
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException

/**
 * What kind of server a base URL points at, never the server itself: a user's
 * own host name can identify them, so anything not on this short list is
 * [OTHER], and a server on the user's own network is [LOCAL].
 */
enum class EndpointClass(override val wire: String) : WireWord {
    SAMOSA("samosa"),
    OPENAI("openai"),
    OPENROUTER("openrouter"),
    GROQ("groq"),
    LOCAL("local"),
    OTHER("other");

    companion object {
        fun of(provider: LlmProvider, baseUrl: String): EndpointClass =
            if (provider == LlmProvider.SAMOSA_AI) SAMOSA else of(baseUrl)

        @Suppress("MagicNumber", "ReturnCount")
        fun of(baseUrl: String): EndpointClass {
            if (baseUrl.startsWith(LlmProvider.SAMOSA_BASE_URL)) return SAMOSA
            val host = runCatching { URI(baseUrl.trim()).host }.getOrNull()?.lowercase()?.trimEnd('.')
                ?: return OTHER
            return when {
                host == "api.openai.com" -> OPENAI
                host == "openrouter.ai" || host.endsWith(".openrouter.ai") -> OPENROUTER
                host == "groq.com" || host.endsWith(".groq.com") -> GROQ
                isLocal(host) -> LOCAL
                else -> OTHER
            }
        }

        @Suppress("MagicNumber")
        private fun isLocal(host: String): Boolean {
            if (host == "localhost" || host.endsWith(".local") || host == "::1" || host == "[::1]") return true
            val octets = host.split('.').mapNotNull { it.toIntOrNull() }
            if (octets.size != 4) return false
            return when (octets[0]) {
                10, 127 -> true
                192 -> octets[1] == 168
                172 -> octets[1] in 16..31
                else -> false
            }
        }
    }
}

/** How a failed model request failed, from the exception it threw. */
object ErrorClass {
    @Suppress("MagicNumber")
    fun of(error: Throwable): AiError = when (error) {
        is HttpException -> when (error.code()) {
            401, 403 -> AiError.AUTH
            429 -> AiError.RATE
            in 400..499 -> AiError.HTTP_4XX
            in 500..599 -> AiError.HTTP_5XX
            else -> AiError.OTHER
        }
        is SocketTimeoutException -> AiError.TIMEOUT
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> AiError.OFFLINE
        is SerializationException -> AiError.PARSE
        else -> AiError.OTHER
    }
}

/**
 * Names that may be sent because Gotcha's own code defines them. Anything a user
 * or a server can name (an MCP tool, a Home Assistant service) collapses to a
 * fixed word, so its name never leaves the phone.
 */
object BoundedNames {
    const val MCP = "mcp"
    const val CONNECTOR = "connector"
    const val OTHER = "other"

    private val builtInTools: Set<String> by lazy { ToolDefinitions.all.map { it.function.name }.toSet() }

    /** Every value [tool] can return. */
    val toolVocabulary: Set<String> get() = builtInTools + setOf(MCP, CONNECTOR, OTHER)

    fun tool(name: String): String = when {
        name in builtInTools -> name
        name.startsWith(McpToolNames.PREFIX) -> MCP
        ToolRegistry.dynamicOwnerOf(name) != null -> CONNECTOR
        else -> OTHER
    }
}
