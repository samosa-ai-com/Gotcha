package com.gotcha.agent.skills

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class SkillImporterTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private val testHosts = setOf("localhost", "127.0.0.1")

    private fun importer(allowedHosts: Set<String> = testHosts) =
        SkillImporter(allowedHosts = allowedHosts, client = client, requireHttps = false)

    @Test
    fun `fetches and parses a valid skill`() {
        val body = """
            {
              "id": "form_entry_and_submission",
              "instructions": "Scan before typing.",
              "description": "Reduces form errors.",
              "targetPackageNames": []
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(body).setResponseCode(200))
        val preview = importer().fetchPreview(server.url("/skills/foo.json").toString())
        assertEquals("form_entry_and_submission", preview.skill.id)
        assertEquals("Scan before typing.", preview.skill.instructions)
    }

    @Test
    fun `rejects http scheme when requireHttps is true`() {
        val strict = SkillImporter(allowedHosts = testHosts, client = client, requireHttps = true)
        try {
            strict.fetchPreview("http://localhost/skills.json")
            fail("Expected SkillImportException")
        } catch (e: SkillImportException) {
            assertTrue(e.message!!.contains("HTTPS"))
        }
    }

    @Test
    fun `rejects host not in allowlist`() {
        val imp = SkillImporter(allowedHosts = setOf("samosa-ai.example"), requireHttps = false)
        try {
            imp.fetchPreview("https://example.com/skills.json")
            fail("Expected SkillImportException")
        } catch (e: SkillImportException) {
            assertTrue(e.message!!.contains("allowlist"))
        }
    }

    @Test
    fun `follows same-host redirect to final 200`() {
        val body = """
            { "id": "redirect_skill", "instructions": "ok" }
        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(301)
                .addHeader("Location", server.url("/final.json").toString())
        )
        server.enqueue(MockResponse().setBody(body).setResponseCode(200))
        val preview = importer().fetchPreview(server.url("/start.json").toString())
        assertEquals("redirect_skill", preview.skill.id)
        assertTrue(preview.sourceUrl.endsWith("/final.json"))
    }

    @Test
    fun `rejects redirect target outside allowlist`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(301)
                .addHeader("Location", "https://attacker.example/x.json")
        )
        try {
            importer().fetchPreview(server.url("/start.json").toString())
            fail("Expected SkillImportException")
        } catch (e: SkillImportException) {
            assertTrue(
                "Expected message to mention allowlist, was '${e.message}'",
                e.message!!.contains("allowlist")
            )
        }
    }

    @Test
    fun `rejects too many redirects`() {
        for (i in 0..10) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(301)
                    .addHeader("Location", server.url("/loop.json?i=$i").toString())
            )
        }
        try {
            importer().fetchPreview(server.url("/loop.json").toString())
            fail("Expected SkillImportException")
        } catch (e: SkillImportException) {
            assertTrue(e.message!!.contains("Too many redirects"))
        }
    }

    @Test
    fun `rejects 4xx response`() {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            importer().fetchPreview(server.url("/missing.json").toString())
            fail("Expected SkillImportException")
        } catch (e: SkillImportException) {
            assertTrue(e.message!!.contains("404"))
        }
    }

    @Test
    fun `rejects malformed json`() {
        server.enqueue(MockResponse().setBody("not json").setResponseCode(200))
        try {
            importer().fetchPreview(server.url("/bad.json").toString())
            fail("Expected SkillImportException")
        } catch (e: SkillImportException) {
            assertTrue(e.message!!.contains("Invalid JSON"))
        }
    }

    @Test
    fun `rejects oversized body`() {
        val giant = "x".repeat(SkillImporter.MAX_BODY_BYTES + 1)
        server.enqueue(MockResponse().setBody(giant).setResponseCode(200))
        try {
            importer().fetchPreview(server.url("/huge.json").toString())
            fail("Expected SkillImportException")
        } catch (e: SkillImportException) {
            assertTrue(e.message!!.contains("exceeds"))
        }
    }

    @Test
    fun `rejects skill failing validation`() {
        val body = """
            {
              "id": "BadId",
              "instructions": "x"
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(body).setResponseCode(200))
        try {
            importer().fetchPreview(server.url("/invalid.json").toString())
            fail("Expected SkillImportException")
        } catch (e: SkillValidationException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `network error is wrapped with a non-blank message`() {
        // Point at a closed port to force an IOException with a null message.
        val closed = MockWebServer()
        val imp = importer()
        val port = closed.port
        closed.shutdown()
        try {
            imp.fetchPreview("http://127.0.0.1:$port/skills.json")
            fail("Expected SkillImportException")
        } catch (e: SkillImportException) {
            // Must not be blank — the UI relies on it.
            assertTrue(
                "Expected non-blank message, was '${e.message}'",
                !e.message.isNullOrBlank()
            )
        }
    }

    @Test
    fun `previewFromRawJson accepts valid json`() {
        val imp = importer()
        val preview = imp.previewFromRawJson(
            """{"id":"good_id","instructions":"Do X"}""",
            "pasted"
        )
        assertEquals("good_id", preview.skill.id)
        assertEquals("pasted", preview.sourceUrl)
    }

    @Test
    fun `previewFromRawJson rejects invalid json`() {
        val imp = importer()
        try {
            imp.previewFromRawJson("not json", "pasted")
            fail("Expected SkillImportException")
        } catch (e: SkillImportException) {
            assertTrue(e.message!!.contains("Invalid JSON"))
        }
    }
}
