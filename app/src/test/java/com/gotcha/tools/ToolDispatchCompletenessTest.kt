package com.gotcha.tools

import com.gotcha.testsupport.RepoPaths
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every tool the LLM can call must be reachable by an executor.
 *
 * Catches the classic bug this catalogue invites: a schema is added to [ToolDefinitions], the
 * executor is never wired, and the failure only surfaces at runtime as the LLM getting back
 * `Tool 'x' has no executor.` from [ToolExecutor]'s `else` branch.
 *
 * Verified by **statically scanning source**, not by calling `execute()` in a loop: tools with no
 * required parameters (`get_battery_info`, `check_root`, `take_photo`, `websearch`) would perform
 * real side effects or network I/O. See `ToolArgValidationTest` for the dynamic half, which is
 * safe because dispatch returns on the missing-argument check before touching anything.
 */
class ToolDispatchCompletenessTest {

    @Test
    fun everyToolHasAnExecutor() {
        val handled = dispatchCaseLabels() + connectorOwnedToolNames()
        val unreachable = ToolDefinitions.all.map { it.function.name }.filterNot { it in handled }

        assertTrue(
            "tool(s) declared in ToolDefinitions.all with no executor — calling them at runtime " +
                "returns \"Tool 'x' has no executor.\": ${unreachable.sorted()}",
            unreachable.isEmpty()
        )
    }

    @Test
    fun executorCaseLabelsAllHaveASchema() {
        val declared = ToolDefinitions.all.map { it.function.name }.toSet()
        val orphans = dispatchCaseLabels() - declared - INTERNAL_ONLY_LABELS

        assertTrue(
            "ToolExecutor.dispatch handles name(s) that no schema declares, so the LLM can never " +
                "call them: ${orphans.sorted()}. Either add a schema, delete the branch, or add " +
                "the name to INTERNAL_ONLY_LABELS with a note.",
            orphans.isEmpty()
        )
    }

    @Test
    fun connectorRouterToolNamesAreAllDeclared() {
        val declared = ToolDefinitions.all.map { it.function.name }.toSet()
        val orphans = connectorOwnedToolNames() - declared

        assertTrue(
            "connector router(s) claim tool name(s) that no schema declares: ${orphans.sorted()}",
            orphans.isEmpty()
        )
    }

    @Test
    fun theSourceScanFoundAPlausibleNumberOfBranches() {
        // Guards the scan itself: if dispatch is refactored so the regex stops matching, the
        // completeness assertions above would silently pass by matching nothing.
        val labels = dispatchCaseLabels()
        assertTrue(
            "the ToolExecutor.dispatch source scan found only ${labels.size} case labels, which " +
                "means the scan is broken rather than that dispatch shrank that far",
            labels.size > MIN_EXPECTED_DISPATCH_LABELS
        )
        val routerNames = connectorOwnedToolNames()
        assertTrue(
            "the connector-router source scan found only ${routerNames.size} tool names",
            routerNames.size > MIN_EXPECTED_ROUTER_NAMES
        )
    }

    // ---- source scanning ----

    /** String case labels inside `ToolExecutor.dispatch`'s `when (name)`, nested `when`s excluded. */
    private fun dispatchCaseLabels(): Set<String> {
        val source = RepoPaths.mainSource("com/gotcha/tools/ToolExecutor.kt")
        val start = source.indexOf(DISPATCH_SIGNATURE)
        check(start >= 0) { "could not find dispatch() in ToolExecutor.kt — did its signature change?" }
        val end = source.indexOf(DISPATCH_FALLBACK, start)
        check(end > start) { "could not find dispatch()'s else branch — did the fallback message change?" }

        return source.substring(start, end)
            .lineSequence()
            // Top-level branches of `when (name)` sit at a fixed indent; nested `when`s are deeper.
            .filter { it.startsWith(CASE_INDENT) && !it.startsWith("$CASE_INDENT ") }
            .flatMap { CASE_LABEL.findAll(it) }
            .map { it.groupValues[1] }
            .toSet()
    }

    /** Tool names claimed by the connector routers `ConnectorRegistry.toolHandler` consults. */
    private fun connectorOwnedToolNames(): Set<String> =
        ROUTER_SOURCES.flatMap { path ->
            val source = RepoPaths.mainSource(path)
            val start = source.indexOf(TOOL_NAMES_DECLARATION)
            check(start >= 0) { "no `override val toolNames` in $path" }
            val block = source.substring(start, minOf(source.length, start + TOOL_NAMES_SCAN_WINDOW))
            // Stop at the end of the setOf(...) initialiser, i.e. the first blank line after it.
            QUOTED.findAll(block.substringBefore("\n\n")).map { it.groupValues[1] }
        }.toSet()

    private companion object {
        const val DISPATCH_SIGNATURE = "private suspend fun dispatch(name: String"
        const val DISPATCH_FALLBACK = "has no executor."
        const val TOOL_NAMES_DECLARATION = "override val toolNames"
        const val TOOL_NAMES_SCAN_WINDOW = 400
        const val CASE_INDENT = "            \""

        val CASE_LABEL = Regex("\"([a-z0-9_]+)\"\\s*(?:,|->)")
        val QUOTED = Regex("\"([a-z0-9_]+)\"")

        /** Mirrors the routers in `ConnectorRegistry.init`. */
        val ROUTER_SOURCES = listOf(
            "com/gotcha/connectors/mail/EmailTools.kt",
            "com/gotcha/connectors/microsoft/TaskTools.kt",
            "com/gotcha/connectors/calendar/CalendarTools.kt",
            "com/gotcha/connectors/notion/NotionTools.kt"
        )

        /**
         * Executor branches deliberately not exposed as LLM-callable schemas.
         * `read_image` is invoked internally when a tool result carries an image.
         */
        val INTERNAL_ONLY_LABELS = setOf("read_image")

        const val MIN_EXPECTED_DISPATCH_LABELS = 70
        const val MIN_EXPECTED_ROUTER_NAMES = 12
    }
}
