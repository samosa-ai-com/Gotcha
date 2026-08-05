package com.gotcha.connectors.notion

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
 * Router owning the Notion tools. Every tool needs a page to have been shared
 * with the integration first, so empty/404 outcomes say that explicitly rather
 * than letting the model conclude the content does not exist.
 */
class NotionTools(
    private val backend: () -> NotionConnector?
) : ToolRouter {

    companion object {
        private const val DEFAULT_PAGE_SIZE = 20
        private const val MAX_PAGE_SIZE = 100
        private const val BLOCK_PAGE_SIZE = 100
        private const val DB_PAGE_SIZE = 100

        /** Upper bound on blocks fetched for one read, across pagination and nesting. */
        private const val BLOCK_READ_BUDGET = 1000
        private const val DB_ROW_CAP = 200
        private const val MAX_NESTING_DEPTH = 3

        private const val SHARING_HINT =
            "If you expected results, the page is probably not shared with the integration yet: " +
                "open it in Notion, use ⋯ → Connections, and add the integration."
    }

    override val toolNames: Set<String> = setOf(
        "notion_search",
        "notion_read_page",
        "notion_create_page",
        "notion_append_to_page"
    )

    override suspend fun execute(name: String, args: JsonObject): ToolResult = try {
        when (name) {
            "notion_search" -> search(args)
            "notion_read_page" -> readPage(args)
            "notion_create_page" -> createPage(args)
            "notion_append_to_page" -> appendToPage(args)
            else -> ToolResult.error("Unknown Notion tool '$name'.")
        }
    } catch (e: Exception) {
        ToolResult.error("$name failed: ${e.message}")
    }

    private fun connected(): NotionConnector? = backend()?.takeIf { it.isConnected() }

    private fun notConnected(): ToolResult = ToolResult.error(
        "Notion is not connected. Ask the user to create an internal integration at " +
            "notion.so/my-integrations and paste its token in Settings → Connectors."
    )

    private suspend fun search(args: JsonObject): ToolResult {
        val notion = connected() ?: return notConnected()
        val pageSize = (args.optInt("max") ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
        val results = notion.search(args.optString("query"), pageSize).map { it.jsonObject }

        if (results.isEmpty()) return ToolResult.ok("No Notion pages matched. $SHARING_HINT")
        val rows = results.joinToString("\n") { item ->
            val kind = item.str("object") ?: "page"
            val title = NotionBlockRenderer.pageTitle(item)
            val edited = item.str("last_edited_time")?.substringBefore('T').orEmpty()
            "[${item.str("id").orEmpty()}] $kind | $title" +
                (if (edited.isNotBlank()) " | edited $edited" else "")
        }
        return ToolResult.ok(
            "${results.size} Notion result(s) — use the id with notion_read_page:\n$rows"
        )
    }

    private suspend fun readPage(args: JsonObject): ToolResult {
        val notion = connected() ?: return notConnected()
        val pageId = args.optString("page_id")
            ?: return ToolResult.error("notion_read_page needs a 'page_id' from notion_search.")

        return when (val target = pageOrDatabase(notion, pageId)) {
            is ReadTarget.Page -> {
                val url = target.json.str("url")?.let { "\nURL: $it" }.orEmpty()
                val body = pageMarkdown(notion, pageId)
                ToolResult.ok(
                    "Title: ${NotionBlockRenderer.pageTitle(target.json)}$url\n\n" +
                        body.ifBlank { "(this page has no text content)" }
                )
            }
            is ReadTarget.Database -> {
                val body = databaseMarkdown(notion, target.json, pageId, 0, ReadBudget(BLOCK_READ_BUDGET))
                ToolResult.ok(body.ifBlank { "(this database has no rows)" })
            }
        }
    }

    /**
     * A search id may point at a page *or* a database (Notion stores todo lists
     * and other table-style content as databases). Tries the page endpoint and
     * falls back to the database endpoint on a 404; any other error propagates.
     */
    private suspend fun pageOrDatabase(notion: NotionConnector, pageId: String): ReadTarget {
        val page = try {
            notion.page(pageId)
        } catch (e: NotionApiException) {
            if (e.code != 404) throw e
            return readDatabase(notion, pageId, e)
        }
        return ReadTarget.Page(page)
    }

    private suspend fun readDatabase(
        notion: NotionConnector,
        pageId: String,
        pageError: NotionApiException
    ): ReadTarget {
        return try {
            ReadTarget.Database(notion.database(pageId))
        } catch (dbE: NotionApiException) {
            throw if (dbE.code == 404) pageError else dbE
        }
    }

    /**
     * Walks a page's block tree into Markdown, paginating past 100 blocks and
     * recursing into nested blocks and inline databases (bounded by a shared
     * budget and nesting depth).
     */
    private suspend fun pageMarkdown(notion: NotionConnector, pageId: String): String {
        val budget = ReadBudget(BLOCK_READ_BUDGET)
        val blocks = fetchBlocks(notion, pageId, budget)
        val body = blocks.map { blockMarkdown(notion, it, 0, budget) }.joinToString("\n").trim()
        val note = if (budget.truncated) "\n\n[truncated — too many blocks to read]" else ""
        return body + note
    }

    private suspend fun blockMarkdown(
        notion: NotionConnector,
        block: JsonObject,
        depth: Int,
        budget: ReadBudget
    ): String {
        if (depth > MAX_NESTING_DEPTH) return ""
        val type = block["type"]?.jsonPrimitive?.contentOrNull ?: return ""
        if (type == "child_database") {
            val databaseId = block["id"]?.jsonPrimitive?.contentOrNull
                ?: return NotionBlockRenderer.blockToMarkdown(block)
            val database = try {
                notion.database(databaseId)
            } catch (e: NotionApiException) {
                // Graceful fallback, but tell the model why the rows are missing:
                // an unshared inline database is actionable, a transient error is not.
                val placeholder = NotionBlockRenderer.blockToMarkdown(block)
                return if (e.code == 404) "$placeholder — rows not readable (not shared)" else placeholder
            }
            return databaseMarkdown(notion, database, databaseId, depth, budget)
        }

        val line = NotionBlockRenderer.blockToMarkdown(block)
        val blockId = block["id"]?.jsonPrimitive?.contentOrNull
        val nested = if (block["has_children"]?.jsonPrimitive?.booleanOrNull == true && blockId != null) {
            val children = fetchBlocks(notion, blockId, budget)
            children.map { child -> blockMarkdown(notion, child, depth + 1, budget) }
                .joinToString("\n")
        } else {
            ""
        }
        val prefix = "  ".repeat(depth)
        return if (nested.isBlank()) "$prefix$line" else "$prefix$line\n$nested"
    }

    /** A database plus its rows (paginated, capped) as Markdown. */
    private suspend fun databaseMarkdown(
        notion: NotionConnector,
        database: JsonObject,
        databaseId: String,
        depth: Int,
        budget: ReadBudget
    ): String {
        val rows = mutableListOf<JsonObject>()
        var cursor: String? = null
        var hasMore = true
        while (hasMore && rows.size < DB_ROW_CAP && rows.size < budget.remaining) {
            val result = notion.databaseQuery(databaseId, DB_PAGE_SIZE, cursor)
            rows += result.results.map { it.jsonObject }
            budget.remaining -= result.results.size
            hasMore = result.hasMore
            val next = result.nextCursor
            if (next == null) break
            cursor = next
        }
        val truncated = hasMore && (rows.size >= DB_ROW_CAP || rows.size >= budget.remaining)
        if (truncated) budget.truncated = true
        val rendered = NotionBlockRenderer.databaseToMarkdown(database, rows)
        val prefix = "  ".repeat(depth)
        val note = if (truncated) "\n[truncated — too many rows to read]" else ""
        return rendered.lineSequence().joinToString("\n") { prefix + it } + note
    }

    /** One block-level list, following `start_cursor` until the budget is spent. */
    private suspend fun fetchBlocks(
        notion: NotionConnector,
        blockId: String,
        budget: ReadBudget
    ): List<JsonObject> {
        val blocks = mutableListOf<JsonObject>()
        var cursor: String? = null
        var hasMore = true
        while (hasMore && blocks.size < budget.remaining) {
            val result = notion.blockChildren(blockId, BLOCK_PAGE_SIZE, cursor)
            blocks += result.results.map { it.jsonObject }
            budget.remaining -= result.results.size
            hasMore = result.hasMore
            val next = result.nextCursor
            if (next == null) break
            cursor = next
        }
        if (hasMore && blocks.size >= budget.remaining) budget.truncated = true
        return blocks
    }

    private suspend fun createPage(args: JsonObject): ToolResult {
        val notion = connected() ?: return notConnected()
        val title = args.optString("title")
            ?: return ToolResult.error("notion_create_page needs a 'title'.")
        val parentId = args.optString("parent_page_id")
            ?: return ToolResult.error(
                "notion_create_page needs a 'parent_page_id' — Notion has no root to create " +
                    "into. Use notion_search to find a page that is shared with the integration " +
                    "and create the new page underneath it."
            )

        val payload = buildJsonObject {
            put(
                "parent",
                buildJsonObject {
                    put("type", JsonPrimitive("page_id"))
                    put("page_id", JsonPrimitive(parentId))
                }
            )
            put("properties", NotionBlockRenderer.titleProperty(title))
            args.optString("content")?.let {
                put("children", NotionBlockRenderer.markdownToBlocks(it))
            }
        }
        val page = notion.createPage(payload)
        val url = page.str("url")?.let { " ($it)" }.orEmpty()
        return ToolResult.ok("Created Notion page \"$title\"$url, id ${page.str("id").orEmpty()}.")
    }

    private suspend fun appendToPage(args: JsonObject): ToolResult {
        val notion = connected() ?: return notConnected()
        val pageId = args.optString("page_id")
            ?: return ToolResult.error("notion_append_to_page needs a 'page_id'.")
        val content = args.optString("content")
            ?: return ToolResult.error("notion_append_to_page needs 'content' to append.")

        notion.appendBlocks(pageId, NotionBlockRenderer.markdownToBlocks(content))
        return ToolResult.ok("Appended ${content.lines().size} line(s) to Notion page $pageId.")
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.optString(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.optInt(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
}

/** Mutable fetch allowance shared across pagination, nesting and inline databases. */
private class ReadBudget(var remaining: Int) {
    /** True when a fetch loop stopped because the budget ran out mid-list. */
    var truncated = false
}

/** What a [pageOrDatabase] lookup resolved to — a page or a database object. */
private sealed interface ReadTarget {
    data class Page(val json: JsonObject) : ReadTarget
    data class Database(val json: JsonObject) : ReadTarget
}
