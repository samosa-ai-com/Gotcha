package com.gotcha.connectors.notion

import com.gotcha.connectors.CredentialStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class InMemoryCredentialStore : CredentialStore {
    private val map = mutableMapOf<String, String>()
    override fun loadRaw(connectorId: String): String? = map[connectorId]
    override fun saveRaw(connectorId: String, blob: String) { map[connectorId] = blob }
    override fun clear(connectorId: String) { map.remove(connectorId) }
}

class NotionToolsTest {

    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryCredentialStore
    private lateinit var connector: NotionConnector
    private lateinit var tools: NotionTools

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = InMemoryCredentialStore()
        connector = NotionConnector(
            store = store,
            api = NotionApi(baseUrl = server.url("/v1").toString().trimEnd('/'))
        )
        tools = NotionTools { connector }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private suspend fun connect() {
        server.enqueue(MockResponse().setBody("""{"name":"Gotcha bot"}"""))
        connector.connect("secret_token")
        server.takeRequest() // the /users/me validation call
    }

    private fun args(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject(block)

    // ---- connection ----

    @Test
    fun `connect validates the token and stores the workspace name`() = runTest {
        server.enqueue(MockResponse().setBody("""{"name":"My workspace"}"""))
        val status = connector.connect("secret_token")

        assertTrue(connector.isConnected())
        assertEquals("My workspace", connector.credentials()?.workspaceName)
        assertTrue("must remind about page sharing", status.contains("Connections"))

        val request = server.takeRequest()
        assertEquals("Bearer secret_token", request.getHeader("Authorization"))
        assertEquals(NotionApi.NOTION_VERSION, request.getHeader("Notion-Version"))
    }

    @Test
    fun `a rejected token is not stored`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"API token is invalid"}"""))
        val status = connector.connect("wrong")

        assertTrue(!connector.isConnected())
        assertEquals(null, store.loadRaw("notion"))
        assertTrue(status.contains("Could not connect"))
    }

    @Test
    fun `disconnect clears the token`() = runTest {
        connect()
        connector.disconnect()
        assertTrue(!connector.isConnected())
        assertEquals(null, store.loadRaw("notion"))
    }

    @Test
    fun `tools steer to Settings when not connected`() = runTest {
        val result = tools.execute("notion_search", args())
        assertTrue(!result.success)
        assertTrue(result.message.contains("my-integrations"))
    }

    // ---- notion_search ----

    @Test
    fun `search lists results with ids`() = runTest {
        connect()
        server.enqueue(
            MockResponse().setBody(
                """{"results":[{"id":"p1","object":"page","last_edited_time":"2026-02-01T10:00:00Z",
                   "properties":{"Name":{"type":"title","title":[{"plain_text":"Roadmap"}]}}}]}"""
            )
        )
        val result = tools.execute("notion_search", args { put("query", "road") })

        assertTrue(result.success)
        assertTrue(result.message.contains("[p1]"))
        assertTrue(result.message.contains("Roadmap"))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"query\":\"road\""))
    }

    @Test
    fun `search renders a database result with a real title instead of crashing`() = runTest {
        connect()
        // A real database from /search: the title column definition has "title": {}.
        server.enqueue(
            MockResponse().setBody(
                """{"results":[
                   {"id":"db1","object":"database","last_edited_time":"2026-08-01T10:00:00Z",
                    "title":[{"plain_text":"My Todos"}],
                    "properties":{
                      "Task":{"id":"title","type":"title","name":"Task","title":{}},
                      "Done":{"id":"cXh","type":"checkbox","name":"Done","checkbox":{}}}}]}"""
            )
        )
        val result = tools.execute("notion_search", args { put("query", "todo") })

        assertTrue(result.success)
        assertTrue(result.message.contains("[db1]"))
        assertTrue(result.message.contains("My Todos"))
        assertTrue(!result.message.contains("(untitled)"))
        assertTrue(!result.message.contains("failed"))
    }

    @Test
    fun `an empty search explains the sharing requirement`() = runTest {
        connect()
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        val result = tools.execute("notion_search", args { put("query", "nothing") })

        assertTrue(result.success)
        assertTrue("must not imply the page does not exist", result.message.contains("Connections"))
    }

    // ---- notion_read_page ----

    @Test
    fun `read_page returns the title and markdown body`() = runTest {
        connect()
        server.enqueue(
            MockResponse().setBody(
                """{"id":"p1","url":"https://notion.so/p1",
                   "properties":{"Name":{"type":"title","title":[{"plain_text":"Roadmap"}]}}}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"results":[
                   {"type":"heading_1","heading_1":{"rich_text":[{"plain_text":"Q1"}]}},
                   {"type":"bulleted_list_item","bulleted_list_item":{"rich_text":[{"plain_text":"Ship it"}]}}]}"""
            )
        )
        val result = tools.execute("notion_read_page", args { put("page_id", "p1") })

        assertTrue(result.success)
        assertTrue(result.message.contains("Title: Roadmap"))
        assertTrue(result.message.contains("https://notion.so/p1"))
        assertTrue(result.message.contains("# Q1"))
        assertTrue(result.message.contains("- Ship it"))
    }

    @Test
    fun `read_page needs a page id`() = runTest {
        connect()
        val result = tools.execute("notion_read_page", args())
        assertTrue(!result.success)
        assertTrue(result.message.contains("page_id"))
    }

    @Test
    fun `a 404 is explained as an unshared page`() = runTest {
        connect()
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Could not find page"}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Could not find database"}"""))
        val result = tools.execute("notion_read_page", args { put("page_id", "missing") })

        assertTrue(!result.success)
        assertTrue(result.message.contains("shared with the integration"))
    }

    @Test
    fun `read_page falls back to a database read when the id is a database`() = runTest {
        connect()
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Could not find page"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"id":"db1","object":"database","title":[{"plain_text":"Todo list"}],
                   "properties":{"Item":{"id":"title","type":"title","name":"Item"},
                                 "Done":{"id":"done","type":"checkbox","name":"Done"}}}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"results":[
                   {"id":"r1","object":"page","properties":{
                     "Item":{"type":"title","title":[{"plain_text":"Buy milk"}]},
                     "Done":{"type":"checkbox","checkbox":false}}},
                   {"id":"r2","object":"page","properties":{
                     "Item":{"type":"title","title":[{"plain_text":"Call bank"}]},
                     "Done":{"type":"checkbox","checkbox":true}}}]}"""
            )
        )
        val result = tools.execute("notion_read_page", args { put("page_id", "db1") })

        assertTrue(result.success)
        assertTrue(result.message.contains("### Database: Todo list"))
        assertTrue(result.message.contains("- [ ] [row-r1] Buy milk"))
        assertTrue(result.message.contains("- [x] [row-r2] Call bank"))

        assertEquals("/v1/pages/db1", server.takeRequest().path)
        assertEquals("/v1/databases/db1", server.takeRequest().path)
        val query = server.takeRequest()
        assertEquals("/v1/databases/db1/query", query.path)
        assertEquals("POST", query.method)
        assertTrue(query.body.readUtf8().contains("\"page_size\":100"))
    }

    @Test
    fun `read_page paginates block children past one page`() = runTest {
        connect()
        server.enqueue(
            MockResponse().setBody(
                """{"id":"p1","url":"https://notion.so/p1",
                   "properties":{"Name":{"type":"title","title":[{"plain_text":"Roadmap"}]}}}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"results":[{"type":"paragraph","paragraph":{"rich_text":[{"plain_text":"First block"}]}}],
                   "has_more":true,"next_cursor":"cur2"}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"results":[{"type":"paragraph","paragraph":{"rich_text":[{"plain_text":"Second block"}]}}],
                   "has_more":false,"next_cursor":null}"""
            )
        )
        val result = tools.execute("notion_read_page", args { put("page_id", "p1") })

        assertTrue(result.success)
        assertTrue(result.message.contains("First block"))
        assertTrue(result.message.contains("Second block"))

        server.takeRequest() // the /pages call
        server.takeRequest() // first block page
        assertEquals("/v1/blocks/p1/children?page_size=100&start_cursor=cur2", server.takeRequest().path)
    }

    @Test
    fun `read_page recurses into nested blocks`() = runTest {
        connect()
        server.enqueue(
            MockResponse().setBody(
                """{"id":"p1","url":"https://notion.so/p1",
                   "properties":{"Name":{"type":"title","title":[{"plain_text":"Nested"}]}}}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"results":[
                   {"id":"b1","type":"bulleted_list_item","has_children":true,
                    "bulleted_list_item":{"rich_text":[{"plain_text":"Parent"}]}}]}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"results":[
                   {"id":"b2","type":"bulleted_list_item",
                    "bulleted_list_item":{"rich_text":[{"plain_text":"Nested child"}]}}]}"""
            )
        )
        val result = tools.execute("notion_read_page", args { put("page_id", "p1") })

        assertTrue(result.success)
        assertTrue(result.message.contains("- Parent"))
        assertTrue(result.message.contains("  - Nested child"))

        server.takeRequest() // the /pages call
        server.takeRequest() // the top-level blocks
        assertEquals("/v1/blocks/b1/children?page_size=100", server.takeRequest().path)
    }

    @Test
    fun `read_page embeds an inline child_database instead of a placeholder`() = runTest {
        connect()
        server.enqueue(
            MockResponse().setBody(
                """{"id":"p1","url":"https://notion.so/p1",
                   "properties":{"Name":{"type":"title","title":[{"plain_text":"Board"}]}}}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"results":[
                   {"id":"cd1","type":"child_database",
                    "child_database":{"title":[{"plain_text":"Todos"}]}}]}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"id":"cd1","object":"database","title":[{"plain_text":"Todos"}],
                   "properties":{"Item":{"id":"title","type":"title","name":"Item"}}}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"results":[
                   {"id":"r1","object":"page","properties":{
                     "Item":{"type":"title","title":[{"plain_text":"Do the thing"}]}}}]}"""
            )
        )
        val result = tools.execute("notion_read_page", args { put("page_id", "p1") })

        assertTrue(result.success)
        assertTrue(result.message.contains("### Database: Todos"))
        assertTrue(result.message.contains("- [ ] [row-r1] Do the thing"))
        assertTrue(!result.message.contains("unsupported block"))
    }

    @Test
    fun `read_page marks the output truncated when a database exceeds the row cap`() = runTest {
        connect()
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Could not find page"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"id":"db1","object":"database","title":[{"plain_text":"Big list"}],
                   "properties":{"Item":{"id":"title","type":"title","name":"Item"}}}"""
            )
        )
        fun queryPage(start: Int, cursor: String): String {
            val rows = (start until start + 100).joinToString(",") { i ->
                """{"id":"r$i","object":"page","properties":{
                   "Item":{"type":"title","title":[{"plain_text":"Row $i"}]}}}"""
            }
            return """{"results":[$rows],"has_more":true,"next_cursor":"$cursor"}"""
        }
        server.enqueue(MockResponse().setBody(queryPage(0, "c2")))
        server.enqueue(MockResponse().setBody(queryPage(100, "c3")))
        server.enqueue(MockResponse().setBody(queryPage(200, "c4")))

        val result = tools.execute("notion_read_page", args { put("page_id", "db1") })

        assertTrue(result.success)
        assertTrue(result.message.contains("Row 0"))
        assertTrue(result.message.contains("Row 199"))
        assertTrue(!result.message.contains("Row 200"))
        assertTrue(result.message.contains("[truncated — too many rows to read]"))
    }

    @Test
    fun `read_page notes when an inline database is not shared`() = runTest {
        connect()
        server.enqueue(
            MockResponse().setBody(
                """{"id":"p1","url":"https://notion.so/p1",
                   "properties":{"Name":{"type":"title","title":[{"plain_text":"Board"}]}}}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"results":[
                   {"id":"cd1","type":"child_database",
                    "child_database":{"title":[{"plain_text":"Todos"}]}}]}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(404).setBody("""{"message":"Could not find database"}""")
        )
        val result = tools.execute("notion_read_page", args { put("page_id", "p1") })

        assertTrue(result.success)
        assertTrue(result.message.contains("- (database) Todos"))
        assertTrue(result.message.contains("rows not readable (not shared)"))
    }

    @Test
    fun `a 404 naming a database steers to database ids rather than re-sharing`() = runTest {
        connect()
        server.enqueue(
            MockResponse().setResponseCode(404).setBody("""{"message":"Could not find database with ID"}""")
        )
        val error = runCatching { connector.page("missing") }.exceptionOrNull()

        assertTrue(error is NotionApiException)
        val message = error!!.message!!
        // A database 404 should name databases and steer to notion_search, not
        // fall back on the generic "re-share the page" guidance.
        assertTrue(message.contains("database", ignoreCase = true))
        assertTrue(message.contains("notion_search"))
    }

    // ---- notion_create_page ----

    @Test
    fun `create_page posts parent title and markdown children`() = runTest {
        connect()
        server.enqueue(MockResponse().setBody("""{"id":"new1","url":"https://notion.so/new1"}"""))
        val result = tools.execute(
            "notion_create_page",
            args {
                put("title", "Meeting notes")
                put("parent_page_id", "parent1")
                put("content", "# Agenda\n- Item one")
            }
        )

        assertTrue(result.success)
        assertTrue(result.message.contains("new1"))

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"page_id\":\"parent1\""))
        assertTrue(body.contains("Meeting notes"))
        assertTrue(body.contains("heading_1"))
        assertTrue(body.contains("bulleted_list_item"))
    }

    @Test
    fun `create_page without a parent explains why one is required`() = runTest {
        connect()
        val result = tools.execute("notion_create_page", args { put("title", "Orphan") })

        assertTrue(!result.success)
        assertTrue(result.message.contains("parent_page_id"))
        assertTrue(result.message.contains("notion_search"))
    }

    // ---- notion_append_to_page ----

    @Test
    fun `append_to_page patches block children`() = runTest {
        connect()
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        val result = tools.execute(
            "notion_append_to_page",
            args {
                put("page_id", "p1")
                put("content", "- New idea")
            }
        )

        assertTrue(result.success)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertTrue(request.path!!.contains("/blocks/p1/children"))
        assertTrue(request.body.readUtf8().contains("bulleted_list_item"))
    }

    @Test
    fun `append_to_page needs content`() = runTest {
        connect()
        val result = tools.execute("notion_append_to_page", args { put("page_id", "p1") })
        assertTrue(!result.success)
        assertTrue(result.message.contains("content"))
    }
}
