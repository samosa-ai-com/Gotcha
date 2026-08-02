package com.gotcha.agent.skills

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkillRegistryUrlImportTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        val communityDir = java.io.File(ctx.filesDir, "skills/community")
        communityDir.listFiles()?.forEach { it.delete() }
        SkillRegistry.init(ctx)
        // Tests use an importer that allows plain HTTP — production stays HTTPS-only.
        SkillRegistry.setImporterFactoryForTesting { hosts ->
            SkillImporter(
                allowedHosts = hosts,
                client = OkHttpClient.Builder()
                    .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                    .build(),
                requireHttps = false
            )
        }
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
        val communityDir = java.io.File(ctx.filesDir, "skills/community")
        communityDir.listFiles()?.forEach { it.delete() }
        SkillRegistry.reload()
    }

    /**
     * Asserts that the network call is *not* made on the main thread. We
     * install a StrictMode policy that crashes the JVM on any network
     * detected on the calling thread; if the import path regressed to a
     * synchronous call, this test would die with NetworkOnMainThreadException
     * before the response body is read.
     */
    @Test
    fun `importCommunityFromUrl does not run the network call on the main thread`() {
        val policy = android.os.StrictMode.ThreadPolicy.Builder()
            .detectNetwork()
            .penaltyDeath()
            .build()
        val previous = android.os.StrictMode.getThreadPolicy()
        server.enqueue(
            MockResponse()
                .setBody("""{"id":"off_main","instructions":"x"}""")
                .setResponseCode(200)
        )
        android.os.StrictMode.setThreadPolicy(policy)
        try {
            val skill = runBlocking {
                SkillRegistry.importCommunityFromUrl(
                    server.url("/skills.json").toString(),
                    setOf(server.hostName)
                )
            }
            assertEquals("off_main", skill.id)
        } finally {
            android.os.StrictMode.setThreadPolicy(previous)
        }
        assertTrue(
            "Imported skill should appear in the community list",
            SkillRegistry.getCommunitySkills().any { it.id == "off_main" }
        )
    }

    @Test
    fun `importCommunityFromUrl returns a valid skill on success`() {
        server.enqueue(
            MockResponse()
                .setBody(
                    """
                    {
                      "id": "remote_skill",
                      "instructions": "Do X",
                      "description": "From URL",
                      "targetPackageNames": []
                    }
                    """.trimIndent()
                )
                .setResponseCode(200)
        )
        val skill = runBlocking {
            SkillRegistry.importCommunityFromUrl(
                server.url("/skills/x.json").toString(),
                setOf(server.hostName)
            )
        }
        assertEquals("remote_skill", skill.id)
    }

    @Test
    fun `importCommunityFromUrl makes the skill visible in the registry`() {
        server.enqueue(
            MockResponse()
                .setBody("""{"id":"async_skill","instructions":"x"}""")
                .setResponseCode(200)
        )
        runBlocking {
            SkillRegistry.importCommunityFromUrl(
                server.url("/async.json").toString(),
                setOf(server.hostName)
            )
        }
        assertTrue(
            "Registry should see the community skill after import",
            SkillRegistry.getAllSkills().any { it.id == "async_skill" }
        )
    }

    @Test
    fun `importCommunityFromUrl surfaces host allowlist errors`() {
        try {
            runBlocking {
                SkillRegistry.importCommunityFromUrl(
                    "https://attacker.example/x.json",
                    setOf("samosa-ai.example")
                )
            }
            throw AssertionError("Expected SkillImportException")
        } catch (e: SkillImportException) {
            assertTrue(e.message!!.contains("allowlist"))
        }
        assertFalse(
            "No skill should be persisted after a failed import",
            SkillRegistry.getCommunitySkills().any { it.id == "remote_skill" }
        )
    }
}
