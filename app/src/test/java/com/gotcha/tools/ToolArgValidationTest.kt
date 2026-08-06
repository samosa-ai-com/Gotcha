package com.gotcha.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.testsupport.FakeAndroidKeyStore
import com.gotcha.testsupport.ShadowExternalStorageManager
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One parameterised assertion across the majority of the catalogue: every tool with required
 * parameters must reject an empty argument object with a `missing()` error naming the parameter,
 * rather than crashing or — worse — proceeding with a default.
 *
 * This is safe to run for real. `ToolExecutor.dispatch` evaluates the `?: return missing(...)`
 * guards before touching any tool, so no side effect or network call happens on this path. Tools
 * *without* required parameters are deliberately excluded: `take_photo`, `check_root` and
 * `websearch` would do real work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowExternalStorageManager::class])
class ToolArgValidationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var executor: ToolExecutor

    @Before
    fun setUp() {
        // ToolExecutor's constructor initialises ConnectorRegistry, which builds
        // EncryptedSharedPreferences and therefore needs an AndroidKeyStore provider.
        FakeAndroidKeyStore.setUp()
        executor = ToolExecutor(context)
    }

    @After
    fun tearDown() {
        ShadowExternalStorageManager.resetGranted()
    }

    private fun requiredParams(name: String): List<String> =
        ToolDefinitions.all.first { it.function.name == name }
            .function.parameters["required"]?.jsonArray
            ?.map { it.jsonPrimitive.content } ?: emptyList()

    private val toolsWithRequiredParams: List<String>
        get() = ToolDefinitions.all
            .map { it.function.name }
            .filter { requiredParams(it).isNotEmpty() }
            .filterNot { it in EXCLUDED }

    @Test
    fun `there are enough tools with required parameters to make this test meaningful`() {
        assertTrue(
            "only ${toolsWithRequiredParams.size} tools have required parameters — did the " +
                "catalogue or the exclusion list change?",
            toolsWithRequiredParams.size > 40
        )
    }

    @Test
    fun `every tool with required parameters rejects empty arguments`() = runTest {
        val failures = mutableListOf<String>()

        toolsWithRequiredParams.forEach { name ->
            val result = executor.execute(name, JsonObject(emptyMap()), agent = AgentMode.OPERATOR, isSubAgent = true)

            if (result.success) {
                failures += "$name: succeeded with no arguments (required: ${requiredParams(name)})"
            }
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `the rejection names the missing parameter`() = runTest {
        val failures = mutableListOf<String>()

        toolsWithRequiredParams.filterNot { it in NOT_PARAMETER_NAMED }.forEach { name ->
            val result = executor.execute(name, JsonObject(emptyMap()), agent = AgentMode.OPERATOR, isSubAgent = true)
            val required = requiredParams(name)

            // The LLM has to know *which* argument it forgot; a bare "failed" is not actionable.
            if (required.none { result.message.contains(it) }) {
                failures += "$name: error names none of $required — got: ${result.message}"
            }
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `an unknown tool is rejected before dispatch`() = runTest {
        val result = executor.execute("definitely_not_a_tool", JsonObject(emptyMap()))

        assertTrue(result.message, !result.success)
        assertTrue(result.message, result.message.contains("Unknown tool"))
    }

    private companion object {
        /**
         * Tools whose required-parameter guard is not reached before a side effect, or whose
         * failure mode is environmental rather than a `missing()` error.
         *
         * - `task` / `navigate_app`: delegate to agent-loop callbacks, not a leaf tool.
         * - `question`: blocks awaiting a UI answer.
         * - `sleep`: `duration_seconds` has a default, so an empty call really does sleep.
         * - `ask_final_answer`: a control-flow signal that returns whatever answer it was
         *   given, so an empty one legitimately succeeds rather than erroring.
         */
        val EXCLUDED = setOf("task", "navigate_app", "question", "sleep", "ask_final_answer")

        /**
         * These still reject an empty call — asserted above — but the message names a
         * precondition rather than the parameter, which is the more useful thing to tell the
         * model:
         *
         * - the connector-backed tools report "X is not connected" before looking at args;
         * - `set_timer` reports the domain rule ("must be at least 1 second");
         * - `finish_task` ends the *top-level* turn, so the sub-agent harness this test uses
         *   refuses it outright — which is the more important thing to say.
         */
        val NOT_PARAMETER_NAMED = setOf(
            "create_task",
            "finish_task",
            "notion_read_page",
            "notion_create_page",
            "notion_append_to_page",
            "notion_update_page",
            "notion_mark_todo",
            "notion_delete_item",
            "set_timer"
        )
    }
}
