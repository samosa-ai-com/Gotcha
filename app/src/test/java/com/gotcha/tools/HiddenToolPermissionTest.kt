package com.gotcha.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.testsupport.FakeAndroidKeyStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a tool withheld by a missing device capability hands back.
 *
 * The message alone is not enough. A hidden tool never reaches its own
 * `permissionNeeded` result, so before this the only thing standing between the
 * user and a bare "something went wrong" was the model choosing to read the
 * `<env>` block and explain — which it did most of the time, and that
 * "most of the time" is what made issue #76 look unreproducible. The marker
 * makes the deep-link fire regardless of how the model words its reply.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class HiddenToolPermissionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var executor: ToolExecutor

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        executor = ToolExecutor(context)
    }

    private suspend fun runHidden(tool: String): ToolResult =
        executor.execute(tool, JsonObject(emptyMap()), hiddenTools = setOf(tool))

    @Test
    fun `a withheld accessibility tool asks for accessibility, not something else`() = runTest {
        val result = runHidden("read_screen")
        assertFalse(result.success)
        assertEquals(ToolResult.ACCESSIBILITY_ACCESS, result.needsPermission)
        assertTrue(
            "the message must name the accessibility service: ${result.message}",
            result.message.contains("accessibility service")
        )
    }

    @Test
    fun `every grantable capability's tools carry that capability's marker`() {
        // Not just accessibility: the same gap existed for the overlay,
        // notification-listener and device-admin tools, and the issue asks for
        // the pattern, not a one-off fix.
        Capability.entries.forEach { capability ->
            val marker = capability.permissionMarker ?: return@forEach
            capability.tools.forEach { tool ->
                val result = kotlinx.coroutines.runBlocking { runHidden(tool) }
                assertEquals(
                    "hidden '$tool' must deep-link to ${capability.name}",
                    marker,
                    result.needsPermission
                )
            }
        }
    }

    @Test
    fun `capabilities with no settings screen produce a message and no deep-link`() = runTest {
        // run_root_command cannot be granted anywhere; sending the user to a
        // settings screen would be worse than the plain explanation.
        val result = runHidden("run_root_command")
        assertFalse(result.success)
        assertNull(result.needsPermission)
        assertTrue(result.message.contains("root"))
    }

    @Test
    fun `a withheld connector tool still steers to Connectors`() = runTest {
        // Connector gating is a different owner with no device grant behind it;
        // the capability change must not have swallowed its message.
        val result = runHidden("list_emails")
        assertFalse(result.success)
        assertNull(result.needsPermission)
        assertTrue(result.message.contains("Connectors"))
    }

    @Test
    fun `the navigator refuses up front instead of burning every step`() = runTest {
        // No service can bind under Robolectric, so this is the blocked state.
        // The old path ran maxSteps LLM round-trips against "(accessibility
        // service not available)" observations and ended on "Reached N steps
        // without completing the task" — the unrelated error users reported.
        // Returning before the first round also means no LLM call is attempted,
        // which is what keeps this test off the network.
        val output = AppNavigatorSession(
            appContext = context,
            toolExecutor = executor,
            settings = com.gotcha.data.Settings(),
            task = "open the settings screen"
        ).run()

        assertFalse(output.success)
        assertTrue("the run must not have stepped", output.steps.isEmpty())
        assertEquals(ToolResult.ACCESSIBILITY_ACCESS, output.needsPermission)
        assertTrue(
            "the answer must name accessibility, not step exhaustion: ${output.finalAnswer}",
            output.finalAnswer.contains("accessibility service")
        )
        assertFalse(output.finalAnswer.contains("Reached"))
    }

    @Test
    fun `the marker is one the host knows how to open`() = runTest {
        // AgentEngine only forwards markers prefixed "special:" to the host.
        val marker = runHidden("tap").needsPermission
        assertTrue("marker '$marker' would never reach the host", marker!!.startsWith("special:"))
    }
}
