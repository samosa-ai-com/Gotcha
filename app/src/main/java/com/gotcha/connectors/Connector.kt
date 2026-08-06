package com.gotcha.connectors

/**
 * A connector integrates an external service (Gmail, IMAP, ...) via its official
 * API instead of accessibility UI-driving. Connectors own their credentials,
 * network clients, and connection state; the tools built on them stay in the
 * existing ToolDefinitions/ToolRegistry/ToolCategories plumbing and are routed
 * by [ConnectorRegistry.toolHandler].
 */
interface Connector {
    /** Stable id used as the credential-store key (e.g. "imap", "google"). */
    val id: String

    /** Human name shown on the Settings card (e.g. "Email (IMAP)"). */
    val displayName: String

    /** One-line description of what connecting enables. */
    val description: String

    /** Names of the tools that depend on this connector (shown on its card). */
    val toolNames: Set<String>

    /**
     * The static half of this connector: which tools it owns outright, and which
     * agents may use them. See [ConnectorSpec] for why it lives apart from the
     * instance.
     */
    val spec: ConnectorSpec

    /** True when credentials are stored and the connector is usable. */
    fun isConnected(): Boolean

    /**
     * True when the connector is both connected and not switched off by the user.
     * Tool exposure keys off this, not [isConnected]: a disabled connector keeps
     * its credentials (so re-enabling needs no re-auth) but contributes no tools.
     */
    fun isActive(disabledConnectors: Set<String>): Boolean =
        isConnected() && id !in disabledConnectors

    /** Short status line for the Settings card (e.g. "Connected as a@b.com"). */
    fun statusLine(): String

    /** Re-synchronizes tools, tokens, or status from the remote server. */
    suspend fun refreshTools(): String = statusLine()

    /** Clear stored credentials and any cached clients/sessions. */
    fun disconnect()
}
