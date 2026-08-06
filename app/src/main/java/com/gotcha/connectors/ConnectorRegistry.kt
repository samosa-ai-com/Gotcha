package com.gotcha.connectors

import android.content.Context
import com.gotcha.connectors.calendar.CalendarToolDevice
import com.gotcha.connectors.calendar.CalendarTools
import com.gotcha.connectors.google.GoogleConnector
import com.gotcha.connectors.homeassistant.HomeAssistantConnector
import com.gotcha.connectors.homeassistant.HomeAssistantTools
import com.gotcha.connectors.imap.ImapConnector
import com.gotcha.connectors.mail.EmailTools
import com.gotcha.connectors.microsoft.MicrosoftConnector
import com.gotcha.connectors.microsoft.TaskTools
import com.gotcha.connectors.notion.NotionConnector
import com.gotcha.connectors.notion.NotionTools
import com.gotcha.tools.ToolRegistry
import com.gotcha.tools.ToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject

/**
 * Central registry of all connectors and the tool routers built on them.
 * Initialized lazily from ToolExecutor and SettingsScreen; [init] is idempotent
 * so either can call it first.
 */
object ConnectorRegistry {

    @Volatile
    private var connectors: List<Connector> = emptyList()

    @Volatile
    private var routers: List<ToolRouter> = emptyList()

    @Volatile
    private var emailTools: EmailTools? = null

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            val store = ConnectorCredentialStore(appContext)
            val imap = ImapConnector(store)
            val google = GoogleConnector(store)
            val microsoft = MicrosoftConnector(store)
            val notion = NotionConnector(store)
            val homeAssistant = HomeAssistantConnector(store)
            connectors = listOf(imap, google, microsoft, notion, homeAssistant)

            val email = EmailTools(appContext, imap, google, microsoft)
            emailTools = email
            routers = listOf(
                email,
                TaskTools { microsoft },
                CalendarTools(
                    // Stateless ContentResolver wrapper — safe to hold a second instance
                    // beside ToolExecutor's, which still serves the confirmed-delete path.
                    device = CalendarToolDevice(com.gotcha.tools.CalendarTool(appContext)),
                    google = { google },
                    microsoft = { microsoft }
                ),
                NotionTools { notion },
                HomeAssistantTools { homeAssistant }
            )
            initialized = true
        }
    }

    fun all(): List<Connector> = connectors

    fun byId(id: String): Connector? = connectors.firstOrNull { it.id == id }

    /** Connectors that are connected and not switched off by the user. */
    fun active(disabledConnectors: Set<String>): List<Connector> =
        connectors.filter { it.isActive(disabledConnectors) }

    /** Ids of connectors that are connected and not switched off by the user. */
    fun activeIds(disabledConnectors: Set<String>): Set<String> =
        active(disabledConnectors).mapTo(mutableSetOf()) { it.id }

    /**
     * Executes [refreshTools] concurrently across all active connectors, returning a map
     * of connector ID to refresh result status message.
     */
    suspend fun refreshAllActive(disabledConnectors: Set<String>): Map<String, String> =
        coroutineScope {
            active(disabledConnectors).map { conn ->
                async {
                    conn.id to runCatching { conn.refreshTools() }.getOrElse { e ->
                        "Could not refresh ${conn.displayName}: ${e.message}"
                    }
                }
            }.awaitAll().toMap()
        }

    /**
     * Tool names to withhold from the model right now, because every connector
     * that could serve them is disconnected or disabled. Falls back to hiding
     * all connector-owned tools before [init] runs — nothing can serve them at
     * that point either. Home Assistant's MCP tools are server-defined, so the
     * compile-time catalog cannot enumerate them: whatever is currently
     * registered is hidden whenever that connector is not usable.
     */
    fun hiddenToolNames(disabledConnectors: Set<String>): Set<String> =
        hiddenToolNamesFor(activeIds(disabledConnectors))

    /**
     * Pure half of [hiddenToolNames], split out so the dynamic-tool gating is
     * testable without a Context. [activeIds] is the set of connected-and-enabled
     * connector ids.
     */
    internal fun hiddenToolNamesFor(activeIds: Set<String>): Set<String> =
        ConnectorCatalog.hiddenTools(activeIds).toMutableSet().also { hidden ->
            if (ConnectorCatalog.HOME_ASSISTANT.id !in activeIds) {
                hidden += ToolRegistry.dynamicTools
            }
        }

    /**
     * Connectors the user has never set up, for the one-line discovery hint in
     * the agent's environment block. Deliberately excludes connectors that are
     * configured but switched off: that was the user's choice, and re-offering
     * it every turn would be nagging.
     */
    fun unconfigured(): List<Connector> = connectors.filterNot { it.isConnected() }

    /** Email tool router (null before [init]). Used by the send-confirmation flow. */
    fun email(): EmailTools? = emailTools

    /**
     * Returns a handler for [name] if a connector-backed router owns that tool,
     * else null. ToolExecutor calls this first; null falls through to the
     * built-in dispatch.
     */
    fun toolHandler(name: String): (suspend (String, JsonObject) -> ToolResult)? =
        routers.firstOrNull { name in it.toolNames }?.let { router -> router::execute }
}
