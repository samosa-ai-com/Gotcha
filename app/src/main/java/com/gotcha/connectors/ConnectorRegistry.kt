package com.gotcha.connectors

import android.content.Context
import com.gotcha.connectors.google.GoogleConnector
import com.gotcha.connectors.imap.ImapConnector
import com.gotcha.connectors.mail.EmailTools
import com.gotcha.tools.ToolResult
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
            connectors = listOf(imap, google)
            emailTools = EmailTools(appContext, imap, google)
            initialized = true
        }
    }

    fun all(): List<Connector> = connectors

    fun byId(id: String): Connector? = connectors.firstOrNull { it.id == id }

    /** Email tool router (null before [init]). Used by the send-confirmation flow. */
    fun email(): EmailTools? = emailTools

    /**
     * Returns a handler for [name] if a connector-backed router owns that tool,
     * else null. ToolExecutor calls this first; null falls through to the
     * built-in dispatch.
     */
    fun toolHandler(name: String): (suspend (String, JsonObject) -> ToolResult)? {
        val email = emailTools ?: return null
        return if (name in email.toolNames) email::execute else null
    }
}
