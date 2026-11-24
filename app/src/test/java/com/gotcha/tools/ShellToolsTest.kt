package com.gotcha.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellToolsTest {

    @Test
    fun `old Termux tool names map to the new ones`() {
        assertEquals("run_shell", ShellTools.canonical("run_termux_command"))
        assertEquals("pull_from_shell", ShellTools.canonical("pull_from_termux"))
    }

    @Test
    fun `other names pass through unchanged`() {
        assertEquals("run_command", ShellTools.canonical("run_command"))
        assertEquals("run_shell", ShellTools.canonical("run_shell"))
    }

    @Test
    fun `the catalog offers only the new names`() {
        assertNotNull(ToolRegistry.definition("run_shell"))
        assertNotNull(ToolRegistry.definition("pull_from_shell"))
        assertNull(ToolRegistry.definition("run_termux_command"))
        assertNull(ToolRegistry.definition("pull_from_termux"))
    }

    @Test
    fun `the shared description names neither backend`() {
        val description = ToolRegistry.definition("run_shell")!!.function.description
        assertFalse(description.contains("Termux"))
        assertFalse(description.contains("Gotcha Shell"))
        assertTrue(description.contains("run_command"))
    }

    @Test
    fun `backend notes are appended to run_shell only`() {
        val tools = ToolRegistry.toolsForAgent(AgentMode.OPERATOR, shellNotes = "NOTES-FOR-THIS-SHELL")
        val run = tools.single { it.function.name == "run_shell" }
        assertTrue(run.function.description.startsWith(ShellTools.RUN_DESCRIPTION))
        assertTrue(run.function.description.endsWith("NOTES-FOR-THIS-SHELL"))
        val others = tools.filter { it.function.name != "run_shell" }
        assertTrue(others.none { "NOTES-FOR-THIS-SHELL" in it.function.description })
    }

    @Test
    fun `without notes the tool list is returned as it is`() {
        val run = ToolRegistry.toolsForAgent(AgentMode.OPERATOR).single { it.function.name == "run_shell" }
        assertEquals(ShellTools.RUN_DESCRIPTION, run.function.description)
    }

    @Test
    fun `each backend's notes describe its own package manager`() {
        assertTrue(TermuxMessages.TOOL_NOTES.contains("pkg install"))
        assertTrue(CompanionMessages.TOOL_NOTES.contains("apt-get install"))
        assertTrue(CompanionMessages.TOOL_NOTES.contains("pull_from_shell"))
    }
}
