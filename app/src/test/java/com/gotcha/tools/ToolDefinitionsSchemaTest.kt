package com.gotcha.tools

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural invariants over the entire tool catalog. A malformed schema here
 * doesn't crash the app — it silently degrades the LLM's tool calling.
 */
class ToolDefinitionsSchemaTest {

    @Test
    fun `catalog is not empty`() {
        assertTrue(ToolDefinitions.all.isNotEmpty())
    }

    @Test
    fun `tool names are unique`() {
        val names = ToolDefinitions.all.map { it.function.name }
        val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("duplicate tool names: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `every tool has a non-blank name and description`() {
        ToolDefinitions.all.forEach { def ->
            assertTrue("blank name in catalog", def.function.name.isNotBlank())
            assertTrue("'${def.function.name}' has a blank description", def.function.description.isNotBlank())
        }
    }

    @Test
    fun `every schema is a JSON object with a properties map`() {
        ToolDefinitions.all.forEach { def ->
            val params = def.function.parameters
            assertEquals(
                "'${def.function.name}' parameters must have type=object",
                "object",
                params["type"]!!.jsonPrimitive.content
            )
            assertTrue(
                "'${def.function.name}' is missing a properties object",
                params["properties"] is kotlinx.serialization.json.JsonObject
            )
        }
    }

    @Test
    fun `every required parameter is declared in properties`() {
        ToolDefinitions.all.forEach { def ->
            val params = def.function.parameters
            val properties = params["properties"]!!.jsonObject.keys
            val required = params["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            required.forEach { name ->
                assertTrue(
                    "'${def.function.name}' requires '$name' but does not declare it in properties",
                    name in properties
                )
            }
        }
    }

    /**
     * A parameter with no `type` or no `description` is the most common cause of the model
     * passing the wrong shape — the schema still validates, so nothing else catches it.
     */
    @Test
    fun `every parameter declares a type and a description`() {
        ToolDefinitions.all.forEach { def ->
            def.function.parameters["properties"]!!.jsonObject.forEach { (param, spec) ->
                val obj = spec.jsonObject
                assertTrue(
                    "'${def.function.name}.$param' has no type",
                    obj["type"]?.jsonPrimitive?.content?.isNotBlank() == true
                )
                assertTrue(
                    "'${def.function.name}.$param' has no description",
                    obj["description"]?.jsonPrimitive?.content?.isNotBlank() == true
                )
            }
        }
    }

    @Test
    fun `required lists have no duplicates`() {
        ToolDefinitions.all.forEach { def ->
            val required = def.function.parameters["required"]?.jsonArray
                ?.map { it.jsonPrimitive.content } ?: emptyList()
            assertEquals(
                "'${def.function.name}' lists a required parameter twice: $required",
                required.size,
                required.toSet().size
            )
        }
    }

    @Test
    fun `parameter names follow the snake_case convention`() {
        val pattern = Regex("^[a-z][a-zA-Z0-9_]*$")
        ToolDefinitions.all.forEach { def ->
            def.function.parameters["properties"]!!.jsonObject.keys.forEach { param ->
                assertTrue("'${def.function.name}.$param' is not a valid parameter name", pattern.matches(param))
            }
        }
    }

    @Test
    fun `tool names follow the snake_case convention`() {
        val pattern = Regex("^[a-z][a-z0-9_]*$")
        ToolDefinitions.all.forEach { def ->
            assertTrue(
                "'${def.function.name}' is not snake_case",
                pattern.matches(def.function.name)
            )
        }
    }
}
