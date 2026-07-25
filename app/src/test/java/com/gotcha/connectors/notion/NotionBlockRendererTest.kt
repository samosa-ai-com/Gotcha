package com.gotcha.connectors.notion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotionBlockRendererTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun block(raw: String) = json.parseToJsonElement(raw).jsonObject

    private fun markdownOf(raw: String) = NotionBlockRenderer.blockToMarkdown(block(raw))

    // ---- blocks → markdown ----

    @Test
    fun `paragraph renders as plain text`() {
        assertEquals(
            "Hello there",
            markdownOf("""{"type":"paragraph","paragraph":{"rich_text":[{"plain_text":"Hello there"}]}}""")
        )
    }

    @Test
    fun `headings render at the right level`() {
        assertEquals("# One", markdownOf("""{"type":"heading_1","heading_1":{"rich_text":[{"plain_text":"One"}]}}"""))
        assertEquals("## Two", markdownOf("""{"type":"heading_2","heading_2":{"rich_text":[{"plain_text":"Two"}]}}"""))
        assertEquals(
            "### Three",
            markdownOf("""{"type":"heading_3","heading_3":{"rich_text":[{"plain_text":"Three"}]}}""")
        )
    }

    @Test
    fun `list items render as bullets and numbers`() {
        assertEquals(
            "- Milk",
            markdownOf("""{"type":"bulleted_list_item","bulleted_list_item":{"rich_text":[{"plain_text":"Milk"}]}}""")
        )
        assertEquals(
            "1. First",
            markdownOf("""{"type":"numbered_list_item","numbered_list_item":{"rich_text":[{"plain_text":"First"}]}}""")
        )
    }

    @Test
    fun `to_do renders its checked state`() {
        assertEquals(
            "- [ ] Buy milk",
            markdownOf("""{"type":"to_do","to_do":{"rich_text":[{"plain_text":"Buy milk"}],"checked":false}}""")
        )
        assertEquals(
            "- [x] Buy milk",
            markdownOf("""{"type":"to_do","to_do":{"rich_text":[{"plain_text":"Buy milk"}],"checked":true}}""")
        )
    }

    @Test
    fun `quote and divider render`() {
        assertEquals("> Said so", markdownOf("""{"type":"quote","quote":{"rich_text":[{"plain_text":"Said so"}]}}"""))
        assertEquals("---", markdownOf("""{"type":"divider","divider":{}}"""))
    }

    @Test
    fun `code block keeps its language fence`() {
        assertEquals(
            "```kotlin\nval x = 1\n```",
            markdownOf("""{"type":"code","code":{"rich_text":[{"plain_text":"val x = 1"}],"language":"kotlin"}}""")
        )
    }

    @Test
    fun `unsupported blocks are flagged rather than dropped silently`() {
        assertEquals("[unsupported block: table]", markdownOf("""{"type":"table","table":{}}"""))
    }

    @Test
    fun `rich_text falls back to nested text content when plain_text is absent`() {
        assertEquals(
            "Nested",
            markdownOf("""{"type":"paragraph","paragraph":{"rich_text":[{"text":{"content":"Nested"}}]}}""")
        )
    }

    @Test
    fun `blocksToMarkdown joins blocks with newlines`() {
        val blocks = listOf(
            block("""{"type":"heading_1","heading_1":{"rich_text":[{"plain_text":"Title"}]}}"""),
            block("""{"type":"paragraph","paragraph":{"rich_text":[{"plain_text":"Body"}]}}""")
        )
        assertEquals("# Title\nBody", NotionBlockRenderer.blocksToMarkdown(blocks))
    }

    // ---- markdown → blocks ----

    @Test
    fun `every supported markdown line maps back to its own block type`() {
        val markdown = """
            # Heading one
            ## Heading two
            ### Heading three
            - A bullet
            1. A number
            - [ ] Open task
            - [x] Done task
            > A quote
            Just a paragraph
        """.trimIndent()

        val types = NotionBlockRenderer.markdownToBlocks(markdown).map {
            it.jsonObject["type"]!!.toString().trim('"')
        }
        assertEquals(
            listOf(
                "heading_1", "heading_2", "heading_3",
                "bulleted_list_item", "numbered_list_item",
                "to_do", "to_do", "quote", "paragraph"
            ),
            types
        )
    }

    @Test
    fun `checked state survives the markdown round trip`() {
        val blocks = NotionBlockRenderer.markdownToBlocks("- [x] Done task")
        val rendered = NotionBlockRenderer.blocksToMarkdown(blocks.map { it.jsonObject })
        assertEquals("- [x] Done task", rendered)
    }

    @Test
    fun `every supported type survives the markdown round trip`() {
        val markdown = "# Title\n- Bullet\n> Quote\n- [ ] Task\nParagraph"
        val blocks = NotionBlockRenderer.markdownToBlocks(markdown)
        assertEquals(markdown, NotionBlockRenderer.blocksToMarkdown(blocks.map { it.jsonObject }))
    }

    @Test
    fun `unrecognised markdown becomes a paragraph so nothing is lost`() {
        val blocks = NotionBlockRenderer.markdownToBlocks("| a | table | row |")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0].toString().contains("paragraph"))
        assertTrue(blocks[0].toString().contains("table"))
    }

    @Test
    fun `blank lines are preserved as empty paragraphs`() {
        val blocks = NotionBlockRenderer.markdownToBlocks("One\n\nTwo")
        assertEquals(3, blocks.size)
    }

    // ---- page metadata ----

    @Test
    fun `pageTitle reads the title property whatever it is named`() {
        val page = block(
            """{"properties":{"Name":{"type":"title","title":[{"plain_text":"Project plan"}]}}}"""
        )
        assertEquals("Project plan", NotionBlockRenderer.pageTitle(page))
    }

    @Test
    fun `pageTitle ignores non-title properties`() {
        val page = block(
            """{"properties":{
               "Tags":{"type":"multi_select","multi_select":[]},
               "Name":{"type":"title","title":[{"plain_text":"Real title"}]}}}"""
        )
        assertEquals("Real title", NotionBlockRenderer.pageTitle(page))
    }

    @Test
    fun `pageTitle degrades gracefully when there is none`() {
        assertEquals("(untitled)", NotionBlockRenderer.pageTitle(block("""{"properties":{}}""")))
        assertEquals("(untitled)", NotionBlockRenderer.pageTitle(block("{}")))
    }

    @Test
    fun `titleProperty builds the shape the create-page endpoint expects`() {
        val property = NotionBlockRenderer.titleProperty("New page").toString()
        assertTrue(property.contains("\"title\""))
        assertTrue(property.contains("New page"))
    }
}
