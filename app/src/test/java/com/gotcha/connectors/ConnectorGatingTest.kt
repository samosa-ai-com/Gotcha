package com.gotcha.connectors

import com.gotcha.tools.AgentMode
import com.gotcha.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Connector-driven tool gating. Exercises [ConnectorCatalog] directly rather
 * than live connectors — that is the whole point of splitting the spec out of
 * [Connector]: the rules are testable without a Context or a credential store.
 */
class ConnectorGatingTest {

    private val mailTools = setOf("list_emails", "read_email", "send_email", "mark_email_read")

    @Test
    fun `nothing active hides every connector-owned tool`() {
        val hidden = ConnectorCatalog.hiddenTools(emptySet())
        assertEquals(ConnectorCatalog.allOwnedTools, hidden)
        assertTrue(mailTools.all { it in hidden })
        assertTrue("notion_search" in hidden)
        assertTrue("list_tasks" in hidden)
        assertTrue("check_availability" in hidden)
    }

    @Test
    fun `mail tools survive when any one mail backend is active`() {
        // Exposure is per-tool, not per-connector: turning Gmail off while IMAP
        // is connected must not take email away.
        listOf("imap", "google", "microsoft").forEach { id ->
            val hidden = ConnectorCatalog.hiddenTools(setOf(id))
            assertTrue("$id should expose mail tools", mailTools.none { it in hidden })
        }
    }

    @Test
    fun `disabling one mail backend keeps the others exposed`() {
        val hidden = ConnectorCatalog.hiddenTools(setOf("imap"))
        assertTrue(mailTools.none { it in hidden })
        // IMAP owns no tasks or Notion, so those stay hidden.
        assertTrue("list_tasks" in hidden)
        assertTrue("notion_search" in hidden)
    }

    @Test
    fun `check_availability needs google or microsoft, not imap`() {
        assertTrue("check_availability" in ConnectorCatalog.hiddenTools(setOf("imap")))
        assertFalse("check_availability" in ConnectorCatalog.hiddenTools(setOf("google")))
        assertFalse("check_availability" in ConnectorCatalog.hiddenTools(setOf("microsoft")))
    }

    @Test
    fun `device-backed calendar tools are never connector-owned`() {
        // They fall back to CalendarContract, so they must stay exposed with no
        // connector at all. delete and edit never leave ToolExecutor's device path.
        listOf(
            "list_calendar_events",
            "create_calendar_event",
            "edit_calendar_event",
            "delete_calendar_event"
        ).forEach {
            assertFalse("$it must not be gated", it in ConnectorCatalog.allOwnedTools)
        }
    }

    @Test
    fun `compose_email is never gated so the agent keeps a no-connector fallback`() {
        assertFalse("compose_email" in ConnectorCatalog.allOwnedTools)
    }

    @Test
    fun `every owned tool exists in the catalog and every read tool is owned`() {
        ConnectorCatalog.allOwnedTools.forEach {
            assertTrue("unknown tool $it", ToolRegistry.contains(it))
        }
        ConnectorCatalog.all.forEach { spec ->
            assertTrue(
                "${spec.id} read tools must be a subset of its owned tools",
                spec.ownedToolNames.containsAll(spec.readOnlyToolNames)
            )
        }
    }

    @Test
    fun `monitor tool set includes connector read tools and excludes their writes`() {
        assertTrue("list_emails" in ToolRegistry.monitorTools)
        assertTrue("read_email" in ToolRegistry.monitorTools)
        assertTrue("notion_search" in ToolRegistry.monitorTools)
        assertTrue("list_tasks" in ToolRegistry.monitorTools)
        assertFalse("send_email" in ToolRegistry.monitorTools)
        assertFalse("notion_create_page" in ToolRegistry.monitorTools)
        assertFalse("create_task" in ToolRegistry.monitorTools)
    }

    @Test
    fun `hidden tools are dropped from the operator schema list`() {
        val hidden = ConnectorCatalog.hiddenTools(emptySet())
        val exposed = ToolRegistry.toolsForAgent(AgentMode.OPERATOR, hidden)
            .map { it.function.name }
            .toSet()
        assertTrue(hidden.none { it in exposed })
        // Everything else survives.
        assertEquals(
            ToolRegistry.allDefinitions().size - hidden.size,
            exposed.size
        )
        assertTrue("compose_email" in exposed)
    }

    @Test
    fun `hidden tools are dropped for monitor and sub-agents too`() {
        val hidden = ConnectorCatalog.hiddenTools(emptySet())
        assertTrue(
            ToolRegistry.toolsForAgent(AgentMode.MONITOR, hidden)
                .none { it.function.name in hidden }
        )
        assertTrue(
            ToolRegistry.toolsForSubAgent(hidden).none { it.function.name in hidden }
        )
    }

    @Test
    fun `hidden tools are refused by the permission check`() {
        val hidden = setOf("list_emails")
        assertFalse(ToolRegistry.isAllowedForAgent("list_emails", AgentMode.OPERATOR, hidden))
        assertFalse(ToolRegistry.isAllowedForSubAgent("list_emails", hidden))
        // Unaffected without the gate.
        assertTrue(ToolRegistry.isAllowedForAgent("list_emails", AgentMode.OPERATOR))
    }

    private class FakeConnector(
        override val spec: ConnectorSpec,
        private val connected: Boolean
    ) : Connector {
        override val id = spec.id
        override val displayName = spec.displayName
        override val description = ""
        override val toolNames = spec.ownedToolNames
        override fun isConnected() = connected
        override fun statusLine() = ""
        override fun disconnect() = Unit
    }

    @Test
    fun `a disabled connector is inactive but stays connected`() {
        val notion = FakeConnector(ConnectorCatalog.NOTION, connected = true)
        assertTrue(notion.isActive(emptySet()))
        assertFalse(notion.isActive(setOf("notion")))
        // Credentials are untouched — re-enabling must not need a re-auth.
        assertTrue(notion.isConnected())
    }

    @Test
    fun `a disconnected connector is inactive regardless of the disabled set`() {
        val notion = FakeConnector(ConnectorCatalog.NOTION, connected = false)
        assertFalse(notion.isActive(emptySet()))
        assertFalse(notion.isActive(setOf("notion")))
    }

    @Test
    fun `catalog ids match the connector ids the registry uses`() {
        assertEquals(
            setOf("imap", "google", "microsoft", "notion"),
            ConnectorCatalog.all.map { it.id }.toSet()
        )
    }

    @Test
    fun `ownersOf names the connectors that would unblock a tool`() {
        assertEquals(
            setOf("imap", "google", "microsoft"),
            ConnectorCatalog.ownersOf("list_emails").map { it.id }.toSet()
        )
        assertEquals(listOf("notion"), ConnectorCatalog.ownersOf("notion_search").map { it.id })
        assertTrue(ConnectorCatalog.ownersOf("read_file").isEmpty())
    }
}
