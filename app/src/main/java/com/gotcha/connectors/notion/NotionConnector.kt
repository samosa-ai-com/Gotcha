package com.gotcha.connectors.notion

import com.gotcha.R
import com.gotcha.connectors.Connector
import com.gotcha.connectors.CredentialStore
import com.gotcha.i18n.StringLookup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class NotionCredentials(
    val token: String,
    /** Bot/workspace name from `GET /users/me`, shown on the Settings card. */
    val workspaceName: String
)

/**
 * Notion connector authenticated by an **internal integration token**: the user
 * creates an integration in their own workspace at notion.so/my-integrations and
 * pastes the secret. There is no OAuth, no redirect URI and no refresh cycle —
 * the same shape as the IMAP app-password connector.
 *
 * The one non-obvious rule: an integration can only see pages that have been
 * explicitly shared with it (page ⋯ → Connections). Every empty result says so.
 */
class NotionConnector(
    private val store: CredentialStore,
    private val api: NotionApi = NotionApi()
) : Connector {

    private val json = Json { ignoreUnknownKeys = true }

    override val id = "notion"
    override val displayName = "Notion"
    override val description =
        "Search, read, create and append to Notion pages shared with your integration."
    override val toolNames = setOf(
        "notion_search",
        "notion_read_page",
        "notion_create_page",
        "notion_append_to_page"
    )
    override val spec = com.gotcha.connectors.ConnectorCatalog.NOTION

    @Volatile
    private var credentials: NotionCredentials? = store.loadRaw(id)?.let { blob ->
        runCatching { json.decodeFromString<NotionCredentials>(blob) }.getOrNull()
    }

    fun credentials(): NotionCredentials? = credentials

    override fun isConnected(): Boolean = credentials != null

    override fun statusLine(strings: StringLookup): String = credentials
        ?.let { strings(R.string.connector_connected_to, it.workspaceName) }
        ?: strings(R.string.connector_not_connected)

    override fun disconnect() {
        store.clear(id)
        credentials = null
    }

    override suspend fun refreshTools(strings: StringLookup): String {
        val creds = credentials ?: return strings(R.string.connector_not_connected)
        return try {
            val me = api.me(creds.token)
            val name = me["name"]?.jsonPrimitive?.contentOrNull
                ?: me["bot"]?.jsonObject?.get("workspace_name")?.jsonPrimitive?.contentOrNull
                ?: creds.workspaceName
            if (name != creds.workspaceName) {
                val updated = creds.copy(workspaceName = name)
                store.saveRaw(id, json.encodeToString(updated))
                credentials = updated
            }
            strings(R.string.connector_connected_to, name)
        } catch (e: Exception) {
            strings(R.string.notion_refresh_failed, e.message.orEmpty())
        }
    }

    /**
     * Validates [token] against `GET /users/me` before storing it, so a typo is
     * caught on the Settings screen instead of on the first tool call. Returns a
     * status message for the card.
     */
    suspend fun connect(token: String, strings: StringLookup): String {
        val trimmed = token.trim()
        if (trimmed.isBlank()) return strings(R.string.notion_paste_token)
        return try {
            val me = api.me(trimmed)
            val name = me["name"]?.jsonPrimitive?.contentOrNull
                ?: me["bot"]?.jsonObject?.get("workspace_name")?.jsonPrimitive?.contentOrNull
                ?: strings(R.string.notion_your_workspace)
            val creds = NotionCredentials(trimmed, name)
            store.saveRaw(id, json.encodeToString(creds))
            credentials = creds
            strings(R.string.notion_connected, name)
        } catch (e: Exception) {
            strings(R.string.connector_could_not_connect, e.message.orEmpty())
        }
    }

    private fun token(): String = checkNotNull(credentials?.token) {
        "Notion is not connected — add an integration token in Settings → Connectors."
    }

    suspend fun search(query: String?, pageSize: Int): JsonArray =
        api.search(token(), query, pageSize)

    suspend fun page(pageId: String): JsonObject = api.page(token(), pageId)

    suspend fun database(databaseId: String): JsonObject = api.database(token(), databaseId)

    suspend fun databaseQuery(databaseId: String, pageSize: Int, startCursor: String? = null): ListResult =
        api.databaseQuery(token(), databaseId, pageSize, startCursor)

    suspend fun blockChildren(blockId: String, pageSize: Int, startCursor: String? = null): ListResult =
        api.blockChildren(token(), blockId, pageSize, startCursor)

    suspend fun createPage(payload: JsonObject): JsonObject = api.createPage(token(), payload)

    suspend fun updatePage(pageId: String, payload: JsonObject): JsonObject =
        api.updatePage(token(), pageId, payload)

    suspend fun appendBlocks(blockId: String, children: JsonArray): JsonObject =
        api.appendBlocks(token(), blockId, children)

    suspend fun updateBlock(blockId: String, payload: JsonObject): JsonObject =
        api.updateBlock(token(), blockId, payload)

    suspend fun deleteBlock(blockId: String): JsonObject = api.deleteBlock(token(), blockId)
}
