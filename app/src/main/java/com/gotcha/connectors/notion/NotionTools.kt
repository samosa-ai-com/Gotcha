package com.gotcha.connectors.notion

import com.gotcha.connectors.ToolRouter
import com.gotcha.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

        val page = notion.page(pageId)
        val blocks = notion.blockChildren(pageId, BLOCK_PAGE_SIZE).map { it.jsonObject }
        val body = NotionBlockRenderer.blocksToMarkdown(blocks)
        val url = page.str("url")?.let { "\nURL: $it" }.orEmpty()
        return ToolResult.ok(
            "Title: ${NotionBlockRenderer.pageTitle(page)}$url\n\n" +
                body.ifBlank { "(this page has no text content)" }
        )
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
