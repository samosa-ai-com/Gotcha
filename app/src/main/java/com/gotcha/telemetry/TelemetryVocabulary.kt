package com.gotcha.telemetry

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A reproducible description of the values accepted by [TelemetrySchema]. */
object TelemetryVocabulary {
    fun build(versionCode: Int, generatedFrom: String = "unknown"): JsonObject = buildJsonObject {
        put("product", TelemetrySchema.PRODUCT)
        put("schema", TelemetrySchema.VERSION)
        put("app_version_code", versionCode)
        put("generated_from", generatedFrom)
        val events = TelemetrySchema.eventNames.associateWith { event ->
            val props = TelemetrySchema.propsOf(event).orEmpty().mapValues { (_, prop) ->
                buildJsonObject {
                    put("optional", prop.optional)
                    put("vocab", prop.vocab.describe())
                }
            }
            buildJsonObject { put("props", JsonObject(props)) }
        }
        put("events", JsonObject(events))
    }

    fun render(versionCode: Int, generatedFrom: String = "unknown"): String =
        renderElement(build(versionCode, generatedFrom), 0) + "\n"

    private fun renderElement(element: JsonElement, depth: Int): String = when (element) {
        is JsonObject -> {
            if (element.isEmpty()) {
                "{}"
            } else {
                element.entries.sortedBy { it.key }.joinToString(
                    separator = ",\n",
                    prefix = "{\n",
                    postfix = "\n${"  ".repeat(depth)}}"
                ) { (key, value) ->
                    "${"  ".repeat(depth + 1)}${JsonPrimitive(key)}: ${renderElement(value, depth + 1)}"
                }
            }
        }
        is JsonArray -> {
            if (element.isEmpty()) {
                "[]"
            } else {
                element.joinToString(
                    separator = ",\n",
                    prefix = "[\n",
                    postfix = "\n${"  ".repeat(depth)}]"
                ) { value -> "${"  ".repeat(depth + 1)}${renderElement(value, depth + 1)}" }
            }
        }
        else -> element.toString()
    }
}
