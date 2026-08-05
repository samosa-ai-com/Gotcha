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

    // ---- databases ----

    @Test
    fun `pageTitle reads a database top-level title when there is no properties title`() {
        val database = block(
            """{"object":"database",
               "title":[{"plain_text":"Todo list"}],
               "properties":{"Name":{"id":"title","type":"title","name":"Name"}}}"""
        )
        assertEquals("Todo list", NotionBlockRenderer.pageTitle(database))
    }

    @Test
    fun `pageTitle survives a title column definition with an empty object title`() {
        // A database's title column definition carries "title": {} (an object,
        // not an array) — rendering it must not crash.
        val database = block(
            """{"object":"database","title":[{"plain_text":"My Todos"}],
               "properties":{"Project name":{"id":"title","type":"title","name":"Project name","title":{}}}}"""
        )
        assertEquals("My Todos", NotionBlockRenderer.pageTitle(database))
    }

    @Test
    fun `richText of a non-array element is empty`() {
        assertEquals("", NotionBlockRenderer.richText(kotlinx.serialization.json.Json.parseToJsonElement("{}")))
        assertEquals("", NotionBlockRenderer.richText(null))
    }

    @Test
    fun `child_database block renders its title instead of an unsupported placeholder`() {
        assertEquals(
            "- (database) Todo list",
            markdownOf("""{"type":"child_database","child_database":{"title":[{"plain_text":"Todo list"}]}}""")
        )
    }

    @Test
    fun `databaseToMarkdown renders a heading and one line per row`() {
        val database = block(
            """{"object":"database","title":[{"plain_text":"Shopping"}],
               "properties":{"Item":{"type":"title","name":"Item"},
                             "Done":{"type":"checkbox","name":"Done"},
                             "Priority":{"type":"select","name":"Priority"}}}"""
        )
        val rows = listOf(
            block(
                """{"object":"page","properties":{
                   "Item":{"type":"title","title":[{"plain_text":"Milk"}]},
                   "Done":{"type":"checkbox","checkbox":false},
                   "Priority":{"type":"select","select":{"name":"High"}}}}"""
            ),
            block(
                """{"object":"page","properties":{
                   "Item":{"type":"title","title":[{"plain_text":"Bread"}]},
                   "Done":{"type":"checkbox","checkbox":true}}}"""
            )
        )
        val markdown = NotionBlockRenderer.databaseToMarkdown(database, rows)

        assertTrue(markdown.startsWith("### Database: Shopping"))
        assertTrue(markdown.contains("- [ ] Milk (Priority: High)"))
        assertTrue(markdown.contains("- [x] Bread"))
    }

    @Test
    fun `rowToMarkdown drops non-scalar properties and untitled rows degrade`() {
        val row = block(
            """{"object":"page","properties":{
               "Title":{"type":"title","title":[{"plain_text":"Item"}]},
               "Relation":{"type":"relation","relation":[]},
               "Created":{"type":"created_time","created_time":"2026-01-01T00:00:00.000Z"}}}"""
        )
        assertEquals("- [ ] Item", NotionBlockRenderer.rowToMarkdown(row))

        val untitled = block("""{"object":"page","properties":{"Done":{"type":"checkbox","checkbox":true}}}""")
        assertEquals("- [x] (untitled)", NotionBlockRenderer.rowToMarkdown(untitled))
    }
}
