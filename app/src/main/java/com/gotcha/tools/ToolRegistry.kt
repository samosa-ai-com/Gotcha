package com.gotcha.tools

import com.gotcha.llm.ToolDefinition

/**
 * Fixed, code-defined tool catalog. The LLM can never add tools at runtime
 * (PRD §11.2 #1) — only this registry decides what is exposed.
 */
object ToolRegistry {

    private val definitions: Map<String, ToolDefinition> =
        ToolDefinitions.all.associateBy { it.function.name }

    /**
     * Tools whose execution mutates device state or is otherwise sensitive;
     * these require user confirmation when the confirmation toggle is on (Phase 7).
     */
    val sensitiveTools: Set<String> = setOf(
        "dial_number", "set_wallpaper", "clear_app_cache",
        "write_file", "run_command", "set_brightness"
    )

    fun allDefinitions(): List<ToolDefinition> = definitions.values.toList()

    fun definition(name: String): ToolDefinition? = definitions[name]

    fun contains(name: String): Boolean = name in definitions

    fun isSensitive(name: String): Boolean = name in sensitiveTools
}
