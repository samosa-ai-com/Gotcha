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
        val result = tools.execute("notion_read_page", args { put("page_id", "missing") })

        assertTrue(!result.success)
        assertTrue(result.message.contains("shared with the integration"))
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
