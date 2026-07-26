package com.gotcha.connectors.microsoft

import com.gotcha.connectors.ToolRouter
import com.gotcha.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Router owning the persistent to-do tools, backed by Microsoft To Do.
 *
 * These are deliberately distinct from `todowrite`: `todowrite` is the agent's
 * in-session scratch plan and disappears when the conversation ends, whereas
 * these tools read and write the user's real task list, which syncs to their
 * other devices.
 *
 * Task ids are uniform: `ms:<listId>:<taskId>`.
 */
class TaskTools(
    private val backend: () -> MicrosoftConnector?
) : ToolRouter {

    companion object {
        private const val DEFAULT_MAX = 25
        private const val MAX_MAX = 100
        private const val ID_PARTS = 3
    }

    override val toolNames: Set<String> = setOf("list_tasks", "create_task", "complete_task")

    override suspend fun execute(name: String, args: JsonObject): ToolResult = try {
        when (name) {
            "list_tasks" -> listTasks(args)
            "create_task" -> createTask(args)
            "complete_task" -> completeTask(args)
            else -> ToolResult.error("Unknown task tool '$name'.")
        }
    } catch (e: Exception) {
        ToolResult.error("$name failed: ${e.message}")
    }

    private fun connectedOrNull(): MicrosoftConnector? = backend()?.takeIf { it.isConnected() }

    private fun notConnected(): ToolResult = ToolResult.error(
        "No task list is connected. These tools need the Microsoft connector (Settings → " +
            "Connectors). For a throwaway plan inside this conversation, use todowrite instead."
    )

    private suspend fun listTasks(args: JsonObject): ToolResult {
        val ms = connectedOrNull() ?: return notConnected()
        val max = (args.optInt("max") ?: DEFAULT_MAX).coerceIn(1, MAX_MAX)
        val includeCompleted = args.optBoolean("include_completed") ?: false
        val listId = resolveListId(ms, args.optString("list"))
            ?: return ToolResult.error("No Microsoft To Do lists were found on this account.")

        val tasks = ms.todoTasks(listId, includeCompleted, max).map { it.jsonObject }
        if (tasks.isEmpty()) {
            return ToolResult.ok(
                if (includeCompleted) "That list has no tasks." else "No open tasks in that list."
            )
        }
        val rows = tasks.joinToString("\n") { task ->
            val id = task.str("id").orEmpty()
            val status = task.str("status").orEmpty()
            val flag = if (status == "completed") "done" else "open"
            val due = task["dueDateTime"]?.jsonObject?.str("dateTime")
                ?.substringBefore('T')?.let { " | due $it" } ?: ""
            "[ms:$listId:$id] $flag | ${task.str("title").orEmpty()}$due"
        }
        return ToolResult.ok("${tasks.size} task(s):\n$rows")
    }

    private suspend fun createTask(args: JsonObject): ToolResult {
        val ms = connectedOrNull() ?: return notConnected()
        val title = args.optString("title")
            ?: return ToolResult.error("create_task needs a 'title'.")
        val listId = resolveListId(ms, args.optString("list"))
            ?: return ToolResult.error("No Microsoft To Do lists were found on this account.")

        val payload = buildJsonObject {
            put("title", JsonPrimitive(title))
            args.optString("notes")?.let { notes ->
                put(
                    "body",
                    buildJsonObject {
                        put("contentType", JsonPrimitive("text"))
                        put("content", JsonPrimitive(notes))
                    }
                )
            }
            args.optString("due_date")?.let { due ->
                put(
                    "dueDateTime",
                    buildJsonObject {
                        put("dateTime", JsonPrimitive("${due}T00:00:00"))
                        put("timeZone", JsonPrimitive("UTC"))
                    }
                )
            }
        }
        val newId = ms.createTodoTask(listId, payload)
        return ToolResult.ok("Created task \"$title\" (id ms:$listId:$newId).")
    }

    private suspend fun completeTask(args: JsonObject): ToolResult {
        val ms = connectedOrNull() ?: return notConnected()
        val id = args.optString("id") ?: return ToolResult.error("complete_task needs an 'id'.")
        val parts = id.split(":")
        if (parts.size != ID_PARTS || parts[0] != "ms") {
            return ToolResult.error("Task ids look like 'ms:<listId>:<taskId>' — got '$id'.")
        }
        val completed = args.optBoolean("completed") ?: true
        ms.completeTodoTask(parts[1], parts[2], completed)
        return ToolResult.ok(
            if (completed) "Marked $id complete." else "Reopened $id."
        )
    }

    /** Resolves a list name to its id; null [name] picks the account's default list. */
    private suspend fun resolveListId(ms: MicrosoftConnector, name: String?): String? {
        if (name.isNullOrBlank()) return ms.defaultTodoListId()
        val match = ms.todoLists().map { it.jsonObject }.firstOrNull { list ->
            list.str("displayName")?.equals(name, ignoreCase = true) == true
        }
        return match?.str("id") ?: ms.defaultTodoListId()
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.optString(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.optInt(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.optBoolean(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull
}
