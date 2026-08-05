package com.gotcha.connectors.notion

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Converts Notion block JSON to Markdown and Markdown back to block JSON.
 *
 * Pure Kotlin, no Android dependencies — the Notion counterpart of
 * [com.gotcha.connectors.mail.MailBodyExtractor], and unit-testable on the JVM.
 * Supports the block types that carry the text a language model actually needs;
 * anything else renders as a `[unsupported: type]` placeholder rather than
 * silently disappearing.
 */
object NotionBlockRenderer {

    private const val TITLE_MAX = 200
    private const val MAX_ROW_PROPERTIES = 3

    /** Property types never rendered in a row's trailing extras. */
    private val EXCLUDED_FROM_EXTRAS = setOf(
        "title", "checkbox", "relation", "rollup", "formula", "people", "files",
        "unique_id", "created_by", "created_time", "last_edited_by", "last_edited_time"
    )

    /**
     * Concatenates a Notion `rich_text` array into plain text.
     *
     * Takes any [JsonElement] and only reads arrays: a database's title column
     * definition carries `"title": {}` (an empty object, not an array), so
     * rendering must never crash on a column definition.
     */
    fun richText(element: JsonElement?): String =
        if (element is JsonArray) {
            element.joinToString("") { node ->
                node.jsonObject["plain_text"]?.jsonPrimitive?.contentOrNull
                    ?: node.jsonObject["text"]?.jsonObject
                        ?.get("content")?.jsonPrimitive?.contentOrNull
                    ?: ""
            }
        } else {
            ""
        }

    /** Renders one block as a Markdown line (no trailing newline). */
    @Suppress("CyclomaticComplexMethod")
    fun blockToMarkdown(block: JsonObject): String {
        val type = block["type"]?.jsonPrimitive?.contentOrNull ?: return ""
        val payload = block[type]?.jsonObject
        val text = richText(payload?.get("rich_text"))
        return when (type) {
            "paragraph" -> text
            "heading_1" -> "# $text"
            "heading_2" -> "## $text"
            "heading_3" -> "### $text"
            "bulleted_list_item" -> "- $text"
            "numbered_list_item" -> "1. $text"
            "to_do" -> {
                val checked = payload?.get("checked")?.jsonPrimitive?.booleanOrNull ?: false
                val marker = elementId(block)?.let { "[block-$it] " }.orEmpty()
                "- [${if (checked) "x" else " "}] $marker$text"
            }
            "quote" -> "> $text"
            "code" -> {
                val language = payload?.get("language")?.jsonPrimitive?.contentOrNull.orEmpty()
                "```$language\n$text\n```"
            }
            "divider" -> "---"
            "child_page" ->
                "- (sub-page) " + (payload?.get("title")?.jsonPrimitive?.contentOrNull).orEmpty()
            "child_database" ->
                "- (database) " + databaseBlockTitle(payload).ifBlank { "Untitled database" }
            else -> if (text.isNotBlank()) text else "[unsupported block: $type]"
        }
    }

    /** Renders a list of blocks as a Markdown document. */
    fun blocksToMarkdown(blocks: List<JsonObject>): String =
        blocks.joinToString("\n") { blockToMarkdown(it) }.trim()

    /**
     * Renders a database and its rows as a Markdown document: a heading with the
     * database title, then one compact line per row — title property plus checkbox
     * state (`- [x]`/`- [ ]`) and a few other scalar properties.
     */
    fun databaseToMarkdown(database: JsonObject, rows: List<JsonObject>): String {
        val title = pageTitle(database).takeIf { it != "(untitled)" } ?: "Untitled database"
        val columns = database["properties"]?.jsonObject?.keys
            ?.filter { it.isNotBlank() }
            ?.joinToString(", ")
        val body = rows.joinToString("\n") { rowToMarkdown(it) }
        return buildString {
            append("### Database: ").append(title)
            if (!columns.isNullOrBlank()) append("\nColumns: ").append(columns)
            if (body.isNotBlank()) append("\n").append(body)
        }
    }

    /** One database row (a page object) as a compact Markdown line. */
    fun rowToMarkdown(row: JsonObject): String {
        val properties = row["properties"]?.jsonObject ?: return "- (empty row)"
        val titleText = firstTitleText(properties) ?: "(untitled)"
        val checked = firstCheckbox(properties)
        val marker = elementId(row)?.let { "[row-$it] " }.orEmpty()
        val extras = properties.entries
            .mapNotNull { (name, value) -> scalarProperty(name, value.jsonObject) }
            .take(MAX_ROW_PROPERTIES)
            .joinToString(" | ")
        return "- [${if (checked) "x" else " "}] $marker$titleText" +
            if (extras.isNotBlank()) " ($extras)" else ""
    }

    /** The `id` of a block or page object, when present. */
    private fun elementId(element: JsonObject): String? =
        element["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun firstTitleText(properties: JsonObject): String? {
        properties.values.forEach { property ->
            val node = property.jsonObject
            if (node["type"]?.jsonPrimitive?.contentOrNull == "title") {
                val text = richText(node["title"])
                if (text.isNotBlank()) return text
            }
        }
        return null
    }

    private fun firstCheckbox(properties: JsonObject): Boolean {
        properties.values.forEach { property ->
            val node = property.jsonObject
            if (node["type"]?.jsonPrimitive?.contentOrNull == "checkbox") {
                return node["checkbox"]?.jsonPrimitive?.booleanOrNull ?: false
            }
        }
        return false
    }

    /** A scalar property as `Name: value`, or null for unsupported/empty types. */
    private fun scalarProperty(name: String, node: JsonObject): String? {
        val type = node["type"]?.jsonPrimitive?.contentOrNull ?: return null
        if (type in EXCLUDED_FROM_EXTRAS) return null
        val payload = node[type] ?: return null
        val text = when (type) {
            "select", "status" -> payload.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            "multi_select" ->
                payload.jsonArray
                    .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                    .takeIf { it.isNotBlank() }
            "date" -> payload.jsonObject["start"]?.jsonPrimitive?.contentOrNull
            "number", "url", "email", "phone_number" -> payload.jsonPrimitive?.contentOrNull
            "rich_text", "text" ->
                if (payload is JsonArray) richText(payload) else payload.jsonPrimitive?.contentOrNull
            else -> null
        }
        return text?.trim()?.takeIf { it.isNotBlank() }?.let { "$name: $it" }
    }

    /** Title of an inline `child_database` block (rich-text array or plain string). */
    private fun databaseBlockTitle(payload: JsonObject?): String {
        val title = payload?.get("title") ?: return ""
        return if (title is JsonArray) {
            richText(title)
        } else {
            title.jsonPrimitive.contentOrNull.orEmpty()
        }
    }

    /**
     * Converts Markdown-ish text to Notion blocks. Recognises the same subset
     * [blockToMarkdown] emits; anything unrecognised becomes a paragraph, so no
     * user content is ever dropped.
     */
    fun markdownToBlocks(markdown: String): JsonArray = buildJsonArray {
        markdown.lines().forEach { rawLine ->
            val line = rawLine.trimEnd()
            // Blank lines become empty paragraphs so spacing survives the round trip.
            if (line.isBlank()) {
                add(textBlock("paragraph", ""))
                return@forEach
            }
            val block = when {
                line.startsWith("### ") -> textBlock("heading_3", line.removePrefix("### "))
                line.startsWith("## ") -> textBlock("heading_2", line.removePrefix("## "))
                line.startsWith("# ") -> textBlock("heading_1", line.removePrefix("# "))
                line.startsWith("> ") -> textBlock("quote", line.removePrefix("> "))
                line.startsWith("- [ ] ") -> todoBlock(line.removePrefix("- [ ] "), false)
                line.startsWith("- [x] ") -> todoBlock(line.removePrefix("- [x] "), true)
                line.startsWith("- ") -> textBlock("bulleted_list_item", line.removePrefix("- "))
                line.startsWith("* ") -> textBlock("bulleted_list_item", line.removePrefix("* "))
                NUMBERED.matches(line) ->
                    textBlock("numbered_list_item", line.replaceFirst(NUMBERED_PREFIX, ""))
                else -> textBlock("paragraph", line)
            }
            add(block)
        }
    }

    /** The `properties` payload for creating a page with [title] under a parent page. */
    fun titleProperty(title: String): JsonObject = buildJsonObject {
        put(
            "title",
            buildJsonObject {
                put("title", buildJsonArray { add(richTextNode(title.take(TITLE_MAX))) })
            }
        )
    }

    /**
     * Best-effort title for a page *or* database object. Pages store it in a
     * `properties.*.title` column; databases keep a top-level `title` array
     * instead, so search hits for a todo-list database show its real name
     * rather than "(untitled)".
     */
    fun pageTitle(page: JsonObject): String {
        page["properties"]?.jsonObject?.values?.forEach { property ->
            val node = property.jsonObject
            if (node["type"]?.jsonPrimitive?.contentOrNull == "title") {
                val text = richText(node["title"])
                if (text.isNotBlank()) return text
            }
        }
        val title = page["title"]
        if (title is JsonArray) {
            val text = richText(title)
            if (text.isNotBlank()) return text
        }
        return "(untitled)"
    }

    private val NUMBERED = Regex("^\\d+[.)] .*")
    private val NUMBERED_PREFIX = Regex("^\\d+[.)] ")

    private fun richTextNode(content: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("text"))
        put("text", buildJsonObject { put("content", JsonPrimitive(content)) })
    }

    private fun textBlock(type: String, content: String): JsonObject = buildJsonObject {
        put("object", JsonPrimitive("block"))
        put("type", JsonPrimitive(type))
        put(
            type,
            buildJsonObject {
                put("rich_text", buildJsonArray { add(richTextNode(content)) })
            }
        )
    }

    private fun todoBlock(content: String, checked: Boolean): JsonObject = buildJsonObject {
        put("object", JsonPrimitive("block"))
        put("type", JsonPrimitive("to_do"))
        put(
            "to_do",
            buildJsonObject {
                put("rich_text", buildJsonArray { add(richTextNode(content)) })
                put("checked", JsonPrimitive(checked))
            }
        )
    }
}
