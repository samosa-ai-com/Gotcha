package com.gotcha.tools

data class AgentDefinition(
    val name: String,
    val description: String,
    val isSubagent: Boolean
) {
    companion object {
        val GENERAL = AgentDefinition(
            name = "GENERAL",
            description = "General-purpose agent for multi-step tasks. " +
                "Delegates complex work so the main agent stays responsive. " +
                "Runs all Operator tools and returns a final summary.",
            isSubagent = true
        )
    }
}
