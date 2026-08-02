package com.gotcha.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.testsupport.FakeAndroidKeyStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `finish_task` is the top-level agent's terminal signal (issue #20): without it
 * the only way out of the tool loop is a reply that happens to carry no tool
 * calls, so an agent that keeps delegating never tells the user anything.
 *
 * These assertions pin the two halves the engine depends on — the `FINISH_TASK:`
 * marker it recognises, and who is allowed to emit it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinishTaskToolTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var executor: ToolExecutor

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        executor = ToolExecutor(context)
    }

    @Test
    fun `returns the summary behind the marker AgentEngine matches on`() = runTest {
        val result = executor.execute(
            "finish_task",
            JsonObject(mapOf("summary" to JsonPrimitive("Searched Amazon for bicycles."))),
            agent = AgentMode.OPERATOR
        )

        assertTrue(result.message, result.success)
        assertEquals("FINISH_TASK:Searched Amazon for bicycles.", result.message)
    }

    @Test
    fun `a summary-less call is rejected rather than ending the turn silently`() = runTest {
        val result = executor.execute("finish_task", JsonObject(emptyMap()), agent = AgentMode.OPERATOR)

        assertFalse(result.message, result.success)
        assertTrue(result.message, result.message.contains("summary"))
    }

    @Test
    fun `both top-level agents can end their turn`() {
        assertTrue(ToolRegistry.isAllowedForAgent("finish_task", AgentMode.OPERATOR))
        assertTrue(ToolRegistry.isAllowedForAgent("finish_task", AgentMode.MONITOR))
    }

    /**
     * A sub-agent ending the *parent's* turn would strand the delegation
     * mid-flight; it reports back with ask_final_answer instead.
     */
    @Test
    fun `sub-agents cannot end the parent turn`() {
        assertFalse(ToolRegistry.isAllowedForSubAgent("finish_task"))
        assertTrue(ToolRegistry.isAllowedForSubAgent("ask_final_answer"))
    }

    /**
     * The mirror image: ask_final_answer is a sub-agent-to-parent signal that the
     * top-level loop has no handling for, so offering it there would give the
     * model a "done" tool that silently does nothing.
     */
    @Test
    fun `ask_final_answer is not offered to the top-level agent`() {
        val operatorTools = ToolRegistry.toolsForAgent(AgentMode.OPERATOR).map { it.function.name }

        assertFalse(operatorTools.toString(), "ask_final_answer" in operatorTools)
        assertTrue(operatorTools.toString(), "finish_task" in operatorTools)
    }

    @Test
    fun `delegation tools are the ones that hand off to a sub-agent`() {
        assertEquals(setOf("task", "navigate_app"), ToolRegistry.delegationTools)
    }
}
