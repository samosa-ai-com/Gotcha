package com.gotcha.tools

/**
 * Task tracking tool — lets the agent create and update a structured task list
 * to plan and track progress during multi-step tasks.
 *
 * Stateless: each call provides the full replacement todo list.
 * Available to both Monitor and Operator.
 */
class TodoTool {

    fun todowrite(items: List<TodoItem>): ToolResult {
        if (items.isEmpty()) return ToolResult.ok("No tasks in the todo list.")

        val formatted = items.joinToString("\n") { item ->
            val icon = when (item.status) {
                TodoStatus.PENDING -> "[ ]"
                TodoStatus.IN_PROGRESS -> "[→]"
                TodoStatus.COMPLETED -> "[✓]"
                TodoStatus.CANCELLED -> "[✗]"
            }
            val priority = when (item.priority?.lowercase()) {
                "high" -> " 🔴"
                "medium" -> " 🟡"
                "low" -> " 🟢"
                else -> ""
            }
            "$icon$priority ${item.content}"
        }

        val summary = buildString {
            val total = items.size
            val done = items.count { it.status == TodoStatus.COMPLETED }
            val inProgress = items.count { it.status == TodoStatus.IN_PROGRESS }
            append("$total task(s): $done done")
            if (inProgress > 0) append(", $inProgress in progress")
        }

        return ToolResult.ok("$summary\n\n$formatted")
    }
}

data class TodoItem(
    val content: String,
    val status: TodoStatus = TodoStatus.PENDING,
    val priority: String? = null
)

enum class TodoStatus { PENDING, IN_PROGRESS, COMPLETED, CANCELLED }
