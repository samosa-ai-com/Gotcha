package com.gotcha.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-capability tool gating. Exercises [CapabilityCatalog] directly — the
 * runtime probes in [DeviceCapabilities] need a real device, but the rules that
 * decide what gets hidden do not.
 */
class CapabilityGatingTest {

    @Test
    fun `nothing available hides every capability-gated tool`() {
        val hidden = CapabilityCatalog.hiddenTools(emptySet())
        assertEquals(CapabilityCatalog.allGatedTools, hidden)
        assertTrue("tap" in hidden)
        assertTrue("navigate_app" in hidden)
        assertTrue("read_notifications" in hidden)
        assertTrue("run_root_command" in hidden)
    }

    @Test
    fun `everything available hides nothing`() {
        assertTrue(CapabilityCatalog.hiddenTools(Capability.entries.toSet()).isEmpty())
    }

    @Test
    fun `capabilities gate independently`() {
        val hidden = CapabilityCatalog.hiddenTools(setOf(Capability.ACCESSIBILITY))
        assertFalse("tap" in hidden)
        assertFalse("read_screen" in hidden)
        assertTrue("read_notifications" in hidden)
        assertTrue("lock_screen" in hidden)
    }

    @Test
    fun `check_root stays exposed so the agent can discover root`() {
        // Gating the discovery tool on the thing it discovers would be circular,
        // and it is the one root tool that is useful on an unrooted device.
        assertFalse("check_root" in CapabilityCatalog.allGatedTools)
        assertTrue("run_root_command" in Capability.ROOT.tools)
        assertTrue("write_secure_settings" in Capability.ROOT.tools)
    }

    @Test
    fun `gated tools all exist and belong to exactly one capability`() {
        CapabilityCatalog.allGatedTools.forEach {
            assertTrue("unknown tool $it", ToolRegistry.contains(it))
        }
        val counts = CapabilityCatalog.allGatedTools.associateWith { tool ->
            Capability.entries.count { tool in it.tools }
        }
        counts.forEach { (tool, n) -> assertEquals("$tool has $n owners", 1, n) }
    }

    @Test
    fun `capability gating does not overlap connector gating`() {
        // A tool gated twice would need both owners consulted; keeping the two
        // sets disjoint means each hidden tool has one explainable reason.
        val overlap = CapabilityCatalog.allGatedTools intersect
            com.gotcha.connectors.ConnectorCatalog.allOwnedTools
        assertTrue("gated by both: $overlap", overlap.isEmpty())
    }

    @Test
    fun `ownerOf names the capability a tool needs`() {
        assertEquals(Capability.ACCESSIBILITY, CapabilityCatalog.ownerOf("swipe"))
        assertEquals(Capability.NOTIFICATION_LISTENER, CapabilityCatalog.ownerOf("get_now_playing"))
        assertNull(CapabilityCatalog.ownerOf("read_file"))
        assertNull(CapabilityCatalog.ownerOf("check_root"))
    }

    @Test
    fun `tools that work without any special access are never gated`() {
        // Regression guard: these are the fallbacks the agent relies on when the
        // fancier route is unavailable.
        listOf(
            "open_app", "list_installed_apps", "question", "sleep",
            "read_file", "write_file", "websearch", "compose_email",
            "list_calendar_events", "set_alarm", "run_command"
        ).forEach {
            assertFalse("$it must never be capability-gated", it in CapabilityCatalog.allGatedTools)
        }
    }

    @Test
    fun `hidden capability tools drop out of the operator schema list`() {
        val hidden = CapabilityCatalog.hiddenTools(emptySet())
        val exposed = ToolRegistry.toolsForAgent(AgentMode.OPERATOR, hidden).map { it.function.name }
        assertTrue(hidden.none { it in exposed })
        assertTrue("check_root" in exposed)
    }
}
