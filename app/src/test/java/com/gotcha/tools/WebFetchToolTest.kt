package com.gotcha.tools

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebFetchToolTest {

    private lateinit var server: MockWebServer
    private val tool = WebFetchTool()

    private val sampleHtml = """
        <html>
        <head><title>Page</title><script>var secret = 1;</script><style>.x{color:red}</style></head>
        <body>
        <nav>NavBarText</nav>
        <h1>Main Title</h1>
        <h2>Sub Title</h2>
        <p>Hello <strong>world</strong> and <code>inline</code>.</p>
        <a href="https://example.com/page">A Link</a>
        <ul><li>first item</li><li>second item</li></ul>
        <blockquote>quoted text</blockquote>
        <pre>preformatted block</pre>
        <footer>FooterText</footer>
        </body>
        </html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueHtml(body: String = sampleHtml) {
        server.enqueue(
            MockResponse().setBody(body).setHeader("Content-Type", "text/html; charset=utf-8")
        )
    }

    @Test
    fun `text format strips script style nav and footer`() {
        enqueueHtml()
        val result = tool.fetch(server.url("/").toString(), "text")

        assertTrue(result.success)
        assertTrue(result.message.contains("Main Title"))
        assertTrue(result.message.contains("Hello world"))
        assertFalse(result.message.contains("NavBarText"))
        assertFalse(result.message.contains("FooterText"))
        assertFalse(result.message.contains("var secret"))
        assertFalse(result.message.contains("color:red"))
    }

    @Test
    fun `markdown format converts headings links emphasis lists and quotes`() {
        enqueueHtml()
        val result = tool.fetch(server.url("/").toString(), "markdown")

        assertTrue(result.success)
        assertTrue(result.message.contains("# Main Title"))
        assertTrue(result.message.contains("## Sub Title"))
        assertTrue(result.message.contains("**world**"))
        assertTrue(result.message.contains("`inline`"))
        assertTrue(result.message.contains("[A Link](https://example.com/page)"))
        assertTrue(result.message.contains("- first item"))
        assertTrue(result.message.contains("- second item"))
        assertTrue(result.message.contains("> quoted text"))
        assertTrue(result.message.contains("```"))
        assertTrue(result.message.contains("preformatted block"))
        assertFalse(result.message.contains("NavBarText"))
    }

    @Test
    fun `relative links are resolved against the page URL`() {
        enqueueHtml("""<html><body><a href="/sub/page">Rel</a></body></html>""")
        val base = server.url("/").toString()
        val result = tool.fetch(base, "markdown")

        assertTrue(result.success)
        assertTrue(result.message.contains("[Rel](${base}sub/page)"))
    }

    @Test
    fun `unknown format falls back to text`() {
        enqueueHtml()
        val result = tool.fetch(server.url("/").toString(), "yaml")

        assertTrue(result.success)
        assertTrue(result.message.contains("KB text"))
        assertFalse(result.message.contains("# Main Title"))
    }

    @Test
    fun `null format defaults to text`() {
        enqueueHtml()
        val result = tool.fetch(server.url("/").toString(), null)

        assertTrue(result.success)
        assertTrue(result.message.contains("Main Title"))
    }

    @Test
    fun `non-http URLs are rejected without a network call`() {
        listOf("ftp://example.com/x", "file:///etc/passwd", "example.com").forEach { url ->
            val result = tool.fetch(url, "text")
            assertFalse("expected '$url' to be rejected", result.success)
            assertTrue(result.message.contains("http"))
        }
        assertTrue(server.requestCount == 0)
    }

    @Test
    fun `http error status is reported`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("gone"))
        val result = tool.fetch(server.url("/missing").toString(), "text")

        assertFalse(result.success)
        assertTrue(result.message.contains("HTTP 404"))
    }

    @Test
    fun `unsupported content type is rejected`() {
        server.enqueue(
            MockResponse().setBody("binarydata").setHeader("Content-Type", "image/png")
        )
        val result = tool.fetch(server.url("/img.png").toString(), "text")

        assertFalse(result.success)
        assertTrue(result.message.contains("Unsupported content type"))
        assertTrue(result.message.contains("image/png"))
    }

    @Test
    fun `plain text response is returned as-is`() {
        server.enqueue(
            MockResponse().setBody("just plain text").setHeader("Content-Type", "text/plain")
        )
        val result = tool.fetch(server.url("/plain").toString(), "text")

        assertTrue(result.success)
        assertTrue(result.message.contains("just plain text"))
    }

    @Test
    fun `json response is accepted`() {
        server.enqueue(
            MockResponse().setBody("""{"key":"value"}""").setHeader("Content-Type", "application/json")
        )
        val result = tool.fetch(server.url("/api").toString(), "text")

        assertTrue(result.success)
        assertTrue(result.message.contains(""""key":"value""""))
    }
}
