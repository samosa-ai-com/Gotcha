package com.gotcha.tools

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalToolTest {

    private val tool = TerminalTool(timeoutSeconds = 5)

    @Test
    fun `simple command returns stdout and exit code`() = runTest {
        val result = tool.runCommand("echo hello")
        assertTrue(result.success)
        assertTrue(result.message.contains("hello"))
        assertTrue(result.message.contains("exit code: 0"))
    }

    @Test
    fun `failing command reports nonzero exit`() = runTest {
        val result = tool.runCommand("false")
        assertFalse(result.success)
    }

    @Test
    fun `destructive commands are blocked by the deny-list`() = runTest {
        listOf("rm -rf /", "su -c id", "dd if=/dev/zero of=x", "reboot").forEach { cmd ->
            val result = tool.runCommand(cmd)
            assertFalse("expected '$cmd' to be blocked", result.success)
            assertTrue(result.message.contains("safety policy"))
        }
    }

    @Test
    fun `long output is capped`() = runTest {
        val small = TerminalTool(timeoutSeconds = 5, maxOutputBytes = 100)
        val result = small.runCommand("seq 1 10000")
        assertTrue(result.message.contains("output capped"))
    }

    @Test
    fun `empty command is rejected`() = runTest {
        assertFalse(tool.runCommand("   ").success)
    }
}
