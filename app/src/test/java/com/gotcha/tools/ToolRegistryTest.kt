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
    fun `unknown tools are rejected`() {
        assertTrue(!ToolRegistry.contains("install_apk"))
    }
}
