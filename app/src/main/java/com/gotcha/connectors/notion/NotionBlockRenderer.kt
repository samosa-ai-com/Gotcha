package com.gotcha.connectors.notion

import kotlinx.serialization.json.JsonArray
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

    /** Concatenates a Notion `rich_text` array into plain text. */
    fun richText(array: JsonArray?): String =
        array.orEmpty().joinToString("") { node ->
            node.jsonObject["plain_text"]?.jsonPrimitive?.contentOrNull
                ?: node.jsonObject["text"]?.jsonObject
                    ?.get("content")?.jsonPrimitive?.contentOrNull
                ?: ""
        }

    /** Renders one block as a Markdown line (no trailing newline). */
    @Suppress("CyclomaticComplexMethod")
    fun blockToMarkdown(block: JsonObject): String {
        val type = block["type"]?.jsonPrimitive?.contentOrNull ?: return ""
        val payload = block[type]?.jsonObject
        val text = richText(payload?.get("rich_text")?.jsonArray)
        return when (type) {
            "paragraph" -> text
            "heading_1" -> "# $text"
            "heading_2" -> "## $text"
            "heading_3" -> "### $text"
            "bulleted_list_item" -> "- $text"
            "numbered_list_item" -> "1. $text"
            "to_do" -> {
                val checked = payload?.get("checked")?.jsonPrimitive?.booleanOrNull ?: false
                "- [${if (checked) "x" else " "}] $text"
            }
            "quote" -> "> $text"
            "code" -> {
                val language = payload?.get("language")?.jsonPrimitive?.contentOrNull.orEmpty()
                "```$language\n$text\n```"
            }
            "divider" -> "---"
            "child_page" ->
                "- (sub-page) " + (payload?.get("title")?.jsonPrimitive?.contentOrNull).orEmpty()
            else -> if (text.isNotBlank()) text else "[unsupported block: $type]"
        }
    }

    /** Renders a list of blocks as a Markdown document. */
    fun blocksToMarkdown(blocks: List<JsonObject>): String =
        blocks.joinToString("\n") { blockToMarkdown(it) }.trim()

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

    /** Best-effort page title from a page object's `properties`. */
    fun pageTitle(page: JsonObject): String {
        val properties = page["properties"]?.jsonObject ?: return "(untitled)"
        properties.values.forEach { property ->
            val node = property.jsonObject
            if (node["type"]?.jsonPrimitive?.contentOrNull == "title") {
                val text = richText(node["title"]?.jsonArray)
                if (text.isNotBlank()) return text
            }
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
