package com.gotcha.tools

/**
 * Question tool — lets the agent ask the user clarifying questions mid-task.
 *
 * The tool itself just validates input and returns a marker result.
 * The [ToolExecutor] detects the marker and routes it through
 * the ChatViewModel's pending-question flow, which pauses the tool
 * loop and presents a dialog to the user.
 */
class QuestionTool {

    companion object {
        const val QUESTION_MARKER = "QUESTION:"
    }

    fun ask(question: String, options: List<String>?, allowCustom: Boolean): ToolResult {
        if (question.isBlank()) return ToolResult.error("Question cannot be empty.")
        val opts = options?.filter { it.isNotBlank() }?.take(10) ?: emptyList()
        return ToolResult.ok(
            "$QUESTION_MARKER$question" +
                (if (opts.isNotEmpty()) "\n--OPTIONS--\n${opts.joinToString("\n")}" else "") +
                "\n--ALLOW_CUSTOM--\n$allowCustom"
        )
    }
}
