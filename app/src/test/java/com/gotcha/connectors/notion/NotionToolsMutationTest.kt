package com.gotcha.connectors.notion

import com.gotcha.connectors.CredentialStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class InMemoryStore : CredentialStore {
    private val map = mutableMapOf<String, String>()
    override fun loadRaw(connectorId: String): String? = map[connectorId]
    override fun saveRaw(connectorId: String, blob: String) { map[connectorId] = blob }
    override fun clear(connectorId: String) { map.remove(connectorId) }
}

class NotionToolsMutationTest {

    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryStore
    private lateinit var connector: NotionConnector
    private lateinit var tools: NotionTools

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = InMemoryStore()
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

    // ---- notion_update_page ----

    private val pageWithSchema = """{
        "id":"r1",
        "properties":{
          "Task":{"id":"title","type":"title","title":[{"plain_text":"Buy milk"}]},
          "Done":{"id":"cXh","type":"checkbox","checkbox":false},
          "Status":{"id":"st","type":"status","status":{"name":"Not started"}},
          "Estimate":{"id":"est","type":"number","number":{"format":"number"}},
          "Tags":{"id":"tg","type":"multi_select","multi_select":{"options":[]}},
          "Created":{"id":"cr","type":"created_time","created_time":"2026-08-01T00:00:00.000Z"}}}"""

    @Test
    fun `update_page patches a checkbox property built from a simple value`() = runTest {
        connect()
        server.enqueue(MockResponse().setBody(pageWithSchema))
        server.enqueue(MockResponse().setBody("""{"id":"r1","object":"page"}"""))
        val result = tools.execute(
            "notion_update_page",
            args {
                put("page_id", "r1")
                put(
                    "properties",
                    buildJsonObject {
                        put("Done", JsonPrimitive(true))
                    }
                )
            }
        )

        assertTrue(result.success)
        assertTrue(result.message.contains("Updated Notion page r1"))
        assertEquals("/v1/pages/r1", server.takeRequest().path)
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/v1/pages/r1", patch.path)
        assertTrue(patch.body.readUtf8().contains("\"checkbox\":true"))
    }

    @Test
    fun `update_page resolves a select property by name`() = runTest {
        connect()
        server.enqueue(MockResponse().setBody(pageWithSchema))
        server.enqueue(MockResponse().setBody("""{"id":"r1","object":"page"}"""))
        val result = tools.execute(
            "notion_update_page",
            args {
                put("page_id", "r1")
                put(
                    "properties",
                    buildJsonObject {
                        put("Status", JsonPrimitive("Done"))
                    }
                )
            }
        )

        assertTrue(result.success)
        server.takeRequest() // the schema fetch
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"status\""))
        assertTrue(body.contains("\"name\":\"Done\""))
    }

    @Test
    fun `update_page supports the reserved title key whatever the column is named`() = runTest {
        connect()
        server.enqueue(MockResponse().setBody(pageWithSchema))
        server.enqueue(MockResponse().setBody("""{"id":"r1","object":"page"}"""))
        val result = tools.execute(
            "notion_update_page",
            args {
                put("page_id", "r1")
                put(
                    "properties",
                    buildJsonObject {
                        put("title", JsonPrimitive("New name"))
                    }
                )
            }
        )

        assertTrue(result.success)
        server.takeRequest() // the schema fetch
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"Task\""))
        assertTrue(body.contains("\"content\":\"New name\""))
    }

    @Test
    fun `update_page rejects an unknown column`() = runTest {
        connect()
        server.enqueue(MockResponse().setBody(pageWithSchema))
        val result = tools.execute(
            "notion_update_page",
            args {
                put("page_id", "r1")
                put(
                    "properties",
                    buildJsonObject {
                        put("Nope", JsonPrimitive(true))
                    }
                )
            }
        )

        assertTrue(!result.success)
        assertTrue(result.message.contains("No column named 'Nope'"))
    }

    @Test
    fun `update_page rejects a non-numeric value for a number column`() = runTest {
        connect()
        server.enqueue(MockResponse().setBody(pageWithSchema))
        val result = tools.execute(
            "notion_update_page",
            args {
                put("page_id", "r1")
                put(
                    "properties",
                    buildJsonObject {
                        put("Estimate", JsonPrimitive("lots"))
                    }
                )
            }
        )

        assertTrue(!result.success)
        assertTrue(result.message.contains("Estimate"))
        assertTrue(result.message.contains("not a number"))
    }

    @Test
    fun `update_page rejects an unsupported column type with the supported list`() = runTest {
        connect()
        server.enqueue(MockResponse().setBody(pageWithSchema))
        val result = tools.execute(
            "notion_update_page",
            args {
                put("page_id", "r1")
                put(
                    "properties",
                    buildJsonObject {
                        put("Created", JsonPrimitive("2026-08-01"))
                    }
                )
            }
        )

        assertTrue(!result.success)
        assertTrue(result.message.contains("not supported"))
        assertTrue(result.message.contains("checkbox"))
    }

    @Test
    fun `update_page accepts a multi_select value as an array of names`() = runTest {
        connect()
        server.enqueue(MockResponse().setBody(pageWithSchema))
        server.enqueue(MockResponse().setBody("""{"id":"r1","object":"page"}"""))
        val result = tools.execute(
            "notion_update_page",
            args {
                put("page_id", "r1")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "Tags",
                            buildJsonArray {
                                add(JsonPrimitive("Home"))
                                add(JsonPrimitive("Work"))
                            }
                        )
                    }
                )
            }
        )

        assertTrue(result.success)
        server.takeRequest() // the schema fetch
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"multi_select\""))
        assertTrue(body.contains("\"name\":\"Home\""))
        assertTrue(body.contains("\"name\":\"Work\""))
    }

    @Test
    fun `update_page accepts a comma-separated multi_select string as a fallback`() = runTest {
        connect()
        server.enqueue(MockResponse().setBody(pageWithSchema))
        server.enqueue(MockResponse().setBody("""{"id":"r1","object":"page"}"""))
        val result = tools.execute(
            "notion_update_page",
            args {
                put("page_id", "r1")
                put(
                    "properties",
                    buildJsonObject {
                        put("Tags", JsonPrimitive("Home, Work"))
                    }
                )
            }
        )

        assertTrue(result.success)
        server.takeRequest() // the schema fetch
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"name\":\"Home\""))
        assertTrue(body.contains("\"name\":\"Work\""))
    }

    @Test
    fun `update_page strips a row prefix from the id`() = runTest {
        connect()
        server.enqueue(MockResponse().setBody(pageWithSchema))
        server.enqueue(MockResponse().setBody("""{"id":"r1","object":"page"}"""))
        val result = tools.execute(
            "notion_update_page",
            args {
                put("page_id", "row-r1")
                put(
                    "properties",
                    buildJsonObject {
                        put("Done", JsonPrimitive(false))
                    }
                )
            }
        )

        assertTrue(result.success)
        server.takeRequest() // the schema fetch
        assertEquals("/v1/pages/r1", server.takeRequest().path)
    }

    // ---- notion_mark_todo ----

    @Test
    fun `mark_todo patches the checked state`() = runTest {
        connect()
        server.enqueue(MockResponse().setBody("""{"id":"b1","object":"block"}"""))
        val result = tools.execute(
            "notion_mark_todo",
            args {
                put("block_id", "b1")
                put("checked", true)
            }
        )

        assertTrue(result.success)
        assertTrue(result.message.contains("done"))
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/v1/blocks/b1", request.path)
        assertTrue(request.body.readUtf8().contains("\"checked\":true"))
    }

    @Test
    fun `mark_todo strips a block prefix from the id`() = runTest {
        connect()
        server.enqueue(MockResponse().setBody("""{"id":"b1","object":"block"}"""))
        val result = tools.execute(
            "notion_mark_todo",
            args {
                put("block_id", "block-b1")
                put("checked", false)
            }
        )

        assertTrue(result.success)
        assertEquals("/v1/blocks/b1", server.takeRequest().path)
    }

    @Test
    fun `mark_todo needs checked`() = runTest {
        connect()
        val result = tools.execute("notion_mark_todo", args { put("block_id", "b1") })
        assertTrue(!result.success)
        assertTrue(result.message.contains("checked"))
    }

    // ---- notion_delete_item ----

    @Test
    fun `delete_item with type page trashes the page`() = runTest {
        connect()
        server.enqueue(MockResponse().setBody("""{"id":"r1","object":"page"}"""))
        val result = tools.execute(
            "notion_delete_item",
            args {
                put("item_id", "r1")
                put("item_type", "page")
            }
        )

        assertTrue(result.success)
        assertTrue(result.message.contains("trash"))
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/v1/pages/r1", request.path)
        assertTrue(request.body.readUtf8().contains("\"in_trash\":true"))
    }

    @Test
    fun `delete_item with type block sends a delete request`() = runTest {
        connect()
        server.enqueue(MockResponse().setBody("""{"id":"b1","object":"block"}"""))
        val result = tools.execute(
            "notion_delete_item",
            args {
                put("item_id", "b1")
                put("item_type", "block")
            }
        )

        assertTrue(result.success)
        assertTrue(result.message.contains("Deleted"))
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/blocks/b1", request.path)
    }

    @Test
    fun `delete_item rejects an unknown item type`() = runTest {
        connect()
        val result = tools.execute(
            "notion_delete_item",
            args {
                put("item_id", "b1")
                put("item_type", "snippet")
            }
        )
        assertTrue(!result.success)
        assertTrue(result.message.contains("'page' or 'block'"))
    }
}
