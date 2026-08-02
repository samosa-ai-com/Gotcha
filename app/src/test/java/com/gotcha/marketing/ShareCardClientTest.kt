package com.gotcha.marketing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.LlmProvider
import com.gotcha.data.RunSummary
import com.gotcha.data.Settings
import com.gotcha.data.ToolSummary
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareCardClientTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(baseUrl: String = server.url("/").toString()) = ShareCardClient(
        context = context,
        settings = Settings(
            provider = LlmProvider.OPENAI_COMPATIBLE,
            apiKey = "test-key",
            baseUrl = baseUrl
        )
    )

    private fun run(
        prompt: String = "Plan my Goa trip",
        tool: String = "websearch",
        toolSuccess: Boolean = true
    ) = RunSummary(
        startedAt = 1000,
        endedAt = 42_000,
        userPrompt = prompt,
        finalReply = "I planned your Goa trip with flights, hotels and itinerary.",
        model = "chai-small",
        agentMode = "OPERATOR",
        delegated = false,
        succeeded = toolSuccess,
        toolCalls = listOf(
            ToolSummary(tool, toolSuccess, if (toolSuccess) "Found 3 options" else "No results")
        )
    )

    // ---- JSON parsing ----

    @Test
    fun `parse accepts a plain JSON object`() {
        val content = client().parse(
            """
            {"eligible":true,"template":"hero","headline":"I asked Gotcha to plan my Goa trip",
             "subheadline":"done in 42s","body":"Flights and hotels sorted.","achievements":[],
             "callToAction":"Meet your agent.","hashtags":["#Gotcha","#AI"]}
            """.trimIndent()
        )
        assertNotNull(content)
        assertEquals("hero", content!!.template)
        assertEquals("I asked Gotcha to plan my Goa trip", content.headline)
        assertEquals(listOf("#Gotcha", "#AI"), content.hashtags)
    }

    @Test
    fun `parse strips markdown code fences`() {
        val content = client().parse(
            """
            ```json
            {"eligible":true,"template":"hero","headline":"H","subheadline":"S","body":"B","achievements":[],"callToAction":"CTA","hashtags":[]}
            ```
            """.trimIndent()
        )
        assertNotNull(content)
        assertEquals("H", content!!.headline)
    }

    @Test
    fun `parse ignores surrounding prose`() {
        val content = client().parse(
            """
            prefix
            {"eligible":true,"template":"recap","headline":"7 things","subheadline":"S",
             "body":"B","achievements":["a","b"],"callToAction":"CTA","hashtags":[]}
            suffix
            """.trimIndent()
        )
        assertNotNull(content)
        assertEquals("recap", content!!.template)
        assertEquals(listOf("a", "b"), content.achievements)
    }

    @Test
    fun `parse returns null on garbage`() {
        assertNull(client().parse("Sorry, I cannot do that."))
        assertNull(client().parse(""))
    }

    @Test
    fun `parse tolerates valid json that lacks poster fields`() {
        // A valid JSON object with no poster keys decodes to all-defaults
        // (eligible=true, empty copy) — never null, never a crash.
        val content = client().parse("""{"unrelated": true}""")
        assertNotNull(content)
        assertTrue(content!!.eligible)
    }

    @Test
    fun `parse returns null on malformed json`() {
        assertNull(client().parse("""{"eligible": true, "headline":"""))
    }

    @Test
    fun `parse tolerates missing optional fields`() {
        val content = client().parse("""{"headline":"only headline"}""")
        assertNotNull(content)
        assertTrue(content!!.eligible)
        assertEquals("hero", content.template)
    }

    // ---- generation ----

    @Test
    fun `generate returns parsed content from a scripted model reply`() = runTest {
        val inner = """{"eligible":true,"template":"hero",""" +
            """"headline":"I asked Gotcha to plan my Goa trip",""" +
            """"subheadline":"and it nailed it","body":"B",""" +
            """"achievements":[],"callToAction":"CTA",""" +
            """"hashtags":["#Gotcha"]}"""
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"${inner.escapeForJson()}"}}]}"""
            )
        )
        val content = client().generate(listOf(run()))
        assertTrue(content.eligible)
        assertEquals("hero", content.template)
        assertEquals("I asked Gotcha to plan my Goa trip", content.headline)
    }

    @Test
    fun `generate ignores a model veto and falls back to the deterministic poster`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"{\"eligible\":false}"}}]}"""
            )
        )
        val content = client().generate(listOf(run()))
        assertTrue("a model veto must not fail the card: $content", content.eligible)
        assertEquals("hero", content.template)
        assertTrue(content.headline.isNotBlank())
    }

    @Test
    fun `generate falls back when the model returns no json`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"I cannot write that."}}]}"""
            )
        )
        val content = client().generate(listOf(run()))
        assertTrue(content.eligible)
        assertEquals("hero", content.template)
        assertTrue(content.headline.isNotBlank())
    }

    @Test
    fun `generate falls back when the request fails`() = runTest {
        // Point at an unreachable port so the request throws.
        val content = client(baseUrl = "http://127.0.0.1:1/").generate(listOf(run()))
        assertTrue(content.eligible)
        assertTrue(content.headline.isNotBlank())
    }

    @Test
    fun `generate returns ineligible for an empty run list`() = runTest {
        val content = client().generate(emptyList())
        assertFalse(content.eligible)
    }

    @Test
    fun `generate sends only successful tool executions in the digest`() = runTest {
        val inner = """{"eligible":true,"template":"hero",""" +
            """"headline":"H","subheadline":"S","body":"B",""" +
            """"achievements":[],"callToAction":"CTA",""" +
            """"hashtags":[]}"""
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"${inner.escapeForJson()}"}}]}"""
            )
        )
        val mixedRun = RunSummary(
            startedAt = 0,
            endedAt = 1000,
            userPrompt = "Fix my wifi",
            finalReply = "Wi-Fi is working now.",
            model = "chai-small",
            agentMode = "OPERATOR",
            delegated = false,
            succeeded = true,
            toolCalls = listOf(
                ToolSummary("toggle_wifi", false, "Failed: no radio"),
                ToolSummary("open_setting", true, "Opened wifi settings"),
                ToolSummary("read_screen", true, "Wi-Fi on")
            )
        )
        client().generate(listOf(mixedRun))

        val requestBody = server.takeRequest().body.readUtf8()
        assertTrue("the digest must include the successful action", requestBody.contains("open_setting"))
        assertTrue("the digest must include the successful read", requestBody.contains("read_screen"))
        assertFalse(
            "the failed toggle_wifi must never reach the marketing model",
            requestBody.contains("toggle_wifi")
        )
    }

    // ---- fallback ----

    @Test
    fun `fallback builds a single-run hero from the last run`() {
        val content = client().fallback(listOf(run()))
        assertEquals("hero", content.template)
        assertEquals("Gotcha handled that for me.", content.headline)
        assertTrue(content.subheadline.isNotBlank())
    }

    @Test
    fun `fallback builds a recap for multiple runs`() {
        val content = client().fallback(listOf(run(), run(prompt = "Book a cab")))
        assertEquals("recap", content.template)
        assertEquals("Gotcha handled 2 things for me today.", content.headline)
        assertEquals(2, content.achievements.size)
    }

    private fun String.escapeForJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
