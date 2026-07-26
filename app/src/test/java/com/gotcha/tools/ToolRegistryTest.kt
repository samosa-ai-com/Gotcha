package com.gotcha.tools

import com.gotcha.llm.ToolDefinition
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `all definitions serialize as valid function schemas`() {
        val defs = ToolRegistry.allDefinitions()
        assertTrue(defs.isNotEmpty())
        defs.forEach { def ->
            assertEquals("function", def.type)
            assertTrue(def.function.name.isNotBlank())
            assertTrue(def.function.description.isNotBlank())
            val encoded = json.encodeToString(ToolDefinition.serializer(), def)
            val obj = json.parseToJsonElement(encoded).jsonObject
            val params = obj["function"]!!.jsonObject["parameters"]!!.jsonObject
            assertEquals("object", params["type"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `definitions round-trip through serialization`() {
        val serializer = ListSerializer(ToolDefinition.serializer())
        val defs = ToolRegistry.allDefinitions()
        val decoded = json.decodeFromString(serializer, json.encodeToString(serializer, defs))
        assertEquals(defs, decoded)
    }

    @Test
    fun `sensitive tools are all registered tools`() {
        ToolRegistry.sensitiveTools.forEach { name ->
            assertTrue("'$name' marked sensitive but not registered", ToolRegistry.contains(name))
        }
    }

    @Test
    fun `destructive tools are all registered tools`() {
        ToolRegistry.destructiveTools.forEach { name ->
            assertTrue("'$name' marked destructive but not registered", ToolRegistry.contains(name))
        }
    }

    @Test
    fun `unknown tools are rejected`() {
        assertTrue(!ToolRegistry.contains("install_apk"))
    }

    /**
     * A tool exposed on no agent surface is dead weight: it is catalogued, dispatchable and
     * documented, but no agent is ever offered it, so the model cannot call it.
     */
    @Test
    fun `every tool is reachable from at least one agent surface`() {
        val reachable = (
            ToolRegistry.toolsForAgent(AgentMode.MONITOR) +
                ToolRegistry.toolsForAgent(AgentMode.OPERATOR) +
                ToolRegistry.toolsForSubAgent() +
                ToolRegistry.toolsForNavigator()
            ).map { it.function.name }.toSet()

        val orphans = ToolDefinitions.all.map { it.function.name }.filterNot { it in reachable }
        assertTrue(
            "tool(s) offered to no agent surface (Monitor, Operator, sub-agent or navigator), " +
                "so the model can never call them: ${orphans.sorted()}",
            orphans.isEmpty()
        )
    }

    @Test
    fun `every agent surface offers only catalogued tools`() {
        val catalogued = ToolDefinitions.all.map { it.function.name }.toSet()
        val surfaces = mapOf(
            "MONITOR" to ToolRegistry.toolsForAgent(AgentMode.MONITOR),
            "OPERATOR" to ToolRegistry.toolsForAgent(AgentMode.OPERATOR),
            "sub-agent" to ToolRegistry.toolsForSubAgent(),
            "navigator" to ToolRegistry.toolsForNavigator()
        )
        surfaces.forEach { (surface, defs) ->
            assertTrue("$surface was offered no tools at all", defs.isNotEmpty())
            val unknown = defs.map { it.function.name }.filterNot { it in catalogued }
            assertTrue("$surface offers uncatalogued tool(s): ${unknown.sorted()}", unknown.isEmpty())
        }
    }

    /**
     * Connector routers claim tool names by string. A name that is not in the
     * catalog would make the tool invisible to the model — and a typo would fail
     * silently, since ToolExecutor rejects unregistered names before dispatch.
     */
    @Test
    fun `every connector-router tool name is in the catalog`() {
        val routerToolNames = listOf(
            com.gotcha.connectors.mail.EmailTools(
                gmailBackend = { null },
                microsoftBackend = { null },
                imapBackend = { null },
                composeLauncher = { _, _, _ -> ToolResult.ok("") }
            ),
            com.gotcha.connectors.microsoft.TaskTools { null },
            com.gotcha.connectors.notion.NotionTools { null },
            com.gotcha.connectors.calendar.CalendarTools(
                device = NoopDeviceCalendar,
                google = { null },
                microsoft = { null }
            )
        ).flatMap { it.toolNames }

        assertTrue("no router claimed any tools", routerToolNames.isNotEmpty())
        routerToolNames.forEach { name ->
            assertTrue("router claims '$name' but it is not a registered tool", ToolRegistry.contains(name))
        }
    }

    /** Routers must not claim the same tool — ConnectorRegistry resolves to the first match. */
    @Test
    fun `connector routers do not claim overlapping tool names`() {
        val claimed = listOf(
            com.gotcha.connectors.microsoft.TaskTools { null }.toolNames,
            com.gotcha.connectors.notion.NotionTools { null }.toolNames,
            com.gotcha.connectors.calendar.CalendarTools(
                device = NoopDeviceCalendar,
                google = { null },
                microsoft = { null }
            ).toolNames
        )
        val all = claimed.flatten()
        assertEquals("a tool is claimed by more than one router", all.size, all.toSet().size)
    }

    private object NoopDeviceCalendar : com.gotcha.connectors.calendar.DeviceCalendar {
        override fun listEvents(
            daysAhead: Int?,
            fromDate: String?,
            toDate: String?,
            search: String?
        ): ToolResult = ToolResult.ok("")

        @Suppress("LongParameterList")
        override fun createEvent(
            title: String,
            start: String,
            end: String?,
            location: String?,
            description: String?,
            allDay: Boolean?,
            reminderMinutes: Int?,
            calendarName: String?,
            attendees: List<String>?,
            recurrence: String?,
            recurrenceCount: Int?,
            recurrenceUntil: String?
        ): ToolResult = ToolResult.ok("")
    }
}
