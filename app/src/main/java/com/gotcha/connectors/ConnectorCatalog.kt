package com.gotcha.connectors

import com.gotcha.tools.AgentMode

/**
 * Context-free description of a connector: which tools depend on it, which of
 * those only read, and which agents may use them.
 *
 * Split out of [Connector] deliberately. Tool gating and the Monitor tool set
 * have to be derivable without constructing connectors — constructing one needs
 * a Context and touches the encrypted credential store, which neither
 * [com.gotcha.tools.ToolRegistry] nor its unit tests can do.
 */
data class ConnectorSpec(
    /** Matches [Connector.id] — the credential-store key. */
    val id: String,
    val displayName: String,
    /**
     * Tools that have no non-connector implementation, and are therefore hidden
     * from the model while every owning connector is inactive.
     *
     * Deliberately narrower than [Connector.toolNames] (what the Settings card
     * lists): `list_calendar_events` and `create_calendar_event` are owned by
     * Google/Microsoft for remote accounts but fall back to the device calendar
     * via CalendarContract, so they must stay exposed with no connector at all.
     */
    val ownedToolNames: Set<String>,
    /** Subset of [ownedToolNames] that only reads — the slice Monitor may call. */
    val readOnlyToolNames: Set<String> = emptySet(),
    /** Agents allowed to use this connector's tools. */
    val agents: Set<AgentMode> = setOf(AgentMode.MONITOR, AgentMode.OPERATOR)
)

/**
 * The static half of the connector registry. [ConnectorRegistry] owns the live
 * instances and their connection state; this owns what each connector *would*
 * contribute, which is fixed at compile time.
 */
object ConnectorCatalog {

    private val MAIL_TOOLS = setOf("list_emails", "read_email", "send_email", "mark_email_read")
    private val MAIL_READ_TOOLS = setOf("list_emails", "read_email")

    val IMAP = ConnectorSpec(
        id = "imap",
        displayName = "Email (IMAP)",
        ownedToolNames = MAIL_TOOLS,
        readOnlyToolNames = MAIL_READ_TOOLS
    )

    val GOOGLE = ConnectorSpec(
        id = "google",
        displayName = "Google (BYO OAuth)",
        // check_availability is the one calendar tool with no CalendarContract
        // implementation — free/busy needs a remote account.
        ownedToolNames = MAIL_TOOLS + "check_availability",
        readOnlyToolNames = MAIL_READ_TOOLS + "check_availability"
    )

    val MICROSOFT = ConnectorSpec(
        id = "microsoft",
        displayName = "Microsoft (Outlook, Calendar, To Do)",
        ownedToolNames = MAIL_TOOLS + setOf(
            "check_availability",
            "list_tasks",
            "create_task",
            "complete_task"
        ),
        readOnlyToolNames = MAIL_READ_TOOLS + setOf("check_availability", "list_tasks")
    )

    val NOTION = ConnectorSpec(
        id = "notion",
        displayName = "Notion",
        ownedToolNames = setOf(
            "notion_search",
            "notion_read_page",
            "notion_create_page",
            "notion_append_to_page"
        ),
        readOnlyToolNames = setOf("notion_search", "notion_read_page")
    )

    val all: List<ConnectorSpec> = listOf(IMAP, GOOGLE, MICROSOFT, NOTION)

    fun byId(id: String): ConnectorSpec? = all.firstOrNull { it.id == id }

    /** Every tool that depends on at least one connector. */
    val allOwnedTools: Set<String> = all.flatMapTo(mutableSetOf()) { it.ownedToolNames }

    /** Connector-owned tools Monitor may call, contributed to ToolRegistry.monitorTools. */
    val monitorTools: Set<String> = all
        .filter { AgentMode.MONITOR in it.agents }
        .flatMapTo(mutableSetOf()) { it.readOnlyToolNames }

    /**
     * Tools to hide given the ids of the currently active connectors.
     *
     * Exposure is per-tool, not per-connector: `list_emails` is owned by IMAP,
     * Google and Microsoft, so it survives as long as *any* of them is active.
     */
    fun hiddenTools(activeIds: Set<String>): Set<String> {
        val exposed = all
            .filter { it.id in activeIds }
            .flatMapTo(mutableSetOf()) { it.ownedToolNames }
        return allOwnedTools - exposed
    }

    /** Connectors that own [tool] — used to explain what to connect. */
    fun ownersOf(tool: String): List<ConnectorSpec> = all.filter { tool in it.ownedToolNames }
}
