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

        val APP_NAVIGATOR = AgentDefinition(
            name = "APP_NAVIGATOR",
            description = "App navigation agent that opens apps and operates " +
                "them step by step: tap, swipe, type, scroll. " +
                "Returns a detailed action log and final summary.",
            isSubagent = true
        )
    }
}
