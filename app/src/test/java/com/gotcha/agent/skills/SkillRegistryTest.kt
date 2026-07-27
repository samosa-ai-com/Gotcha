package com.gotcha.agent.skills

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkillRegistryTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setup() {
        // Clean any community files from a previous run so the merged view
        // starts deterministic.
        val communityDir = java.io.File(ctx.filesDir, "skills/community")
        communityDir.listFiles()?.forEach { it.delete() }
        SkillRegistry.init(ctx)
    }

    @After
    fun teardown() {
        val communityDir = java.io.File(ctx.filesDir, "skills/community")
        communityDir.listFiles()?.forEach { it.delete() }
        SkillRegistry.reload()
    }

    @Test
    fun `bundled skills are loaded from assets`() {
        val bundled = SkillRegistry.getAllSkills()
        // The repo ships at least settings_operations and whatsapp_operations.
        assertTrue(
            "Expected bundled skills to be present, got $bundled",
            bundled.any { it.id == "settings_operations" || it.id == "settings_search" }
        )
    }

    @Test
    fun `importing a community skill merges it in`() = runBlocking {
        val json = """
            {
              "id": "im_community_skill",
              "instructions": "be a great skill",
              "description": "test"
            }
        """.trimIndent()
        val skill = SkillRegistry.importCommunity(json)
        assertEquals("im_community_skill", skill.id)
        val all = SkillRegistry.getAllSkills()
        assertTrue(all.any { it.id == "im_community_skill" })
        val community = SkillRegistry.getCommunitySkills()
        assertEquals(1, community.size)
        assertEquals("im_community_skill", community[0].id)
    }

    @Test
    fun `wildcard package matches any app`() = runBlocking {
        val json = """
            {
              "id": "global_skill",
              "instructions": "everywhere",
              "targetPackageNames": ["*"]
            }
        """.trimIndent()
        SkillRegistry.importCommunity(json)
        val matches = SkillRegistry.getSkillsForPackage("com.example.any")
        assertTrue(matches.any { it.id == "global_skill" })
    }

    @Test
    fun `searchSkills finds community skills by description`() = runBlocking {
        val json = """
            {
              "id": "findable_skill",
              "instructions": "x",
              "description": "needle in haystack"
            }
        """.trimIndent()
        SkillRegistry.importCommunity(json)
        val hits = SkillRegistry.searchSkills("needle")
        assertTrue(hits.any { it.id == "findable_skill" })
    }

    @Test
    fun `removeCommunity drops the skill`() = runBlocking {
        val json = """
            {
              "id": "removable_skill",
              "instructions": "x"
            }
        """.trimIndent()
        SkillRegistry.importCommunity(json)
        assertTrue(SkillRegistry.getCommunitySkills().any { it.id == "removable_skill" })
        assertTrue(SkillRegistry.removeCommunity("removable_skill"))
        assertTrue(SkillRegistry.getCommunitySkills().none { it.id == "removable_skill" })
    }

    @Test
    fun `bundled skill id wins on collision`() = runBlocking {
        val json = """
            {
              "id": "settings_operations",
              "instructions": "shadowed"
            }
        """.trimIndent()
        SkillRegistry.importCommunity(json)
        val resolved = SkillRegistry.getSkillById("settings_operations")
        assertNotNull(resolved)
        // Bundled wins — its instructions never get replaced. Asserted against the
        // community text rather than a phrase from the bundled skill, so rewording
        // the shipped skill cannot fail a test about shadowing.
        assertNotEquals("shadowed", resolved!!.instructions)
    }

    @Test
    fun `importCommunity rejects invalid skill`() = runBlocking {
        val bad = """{"id":"","instructions":"x"}"""
        try {
            SkillRegistry.importCommunity(bad)
            throw AssertionError("Expected SkillValidationException")
        } catch (e: SkillValidationException) {
            assertTrue(e.message!!.contains("id is empty"))
        }
    }

    @Test
    fun `importCommunity runs the disk write off the main thread`() {
        // Robolectric runs tests on the main looper, so this StrictMode
        // policy will fail the test if the file I/O regresses to a
        // synchronous call.
        val policy = android.os.StrictMode.ThreadPolicy.Builder()
            .detectDiskWrites()
            .penaltyDeath()
            .build()
        val previous = android.os.StrictMode.getThreadPolicy()
        android.os.StrictMode.setThreadPolicy(policy)
        try {
            runBlocking {
                SkillRegistry.importCommunity(
                    """{"id":"off_main_paste","instructions":"x"}"""
                )
            }
        } finally {
            android.os.StrictMode.setThreadPolicy(previous)
        }
    }
}
