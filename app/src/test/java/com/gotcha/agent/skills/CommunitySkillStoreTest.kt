package com.gotcha.agent.skills

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunitySkillStoreTest {

    private lateinit var ctx: android.content.Context
    private lateinit var store: CommunitySkillStore
    private lateinit var dir: File

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        store = CommunitySkillStore(ctx)
        dir = File(ctx.filesDir, "skills/community")
        // Clean up any leftovers from a previous test run.
        dir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `save and readAll round-trip`() {
        val json = """
            {
              "id": "good_id",
              "instructions": "Do X.",
              "description": "d"
            }
        """.trimIndent()
        store.save(json)
        val read = store.readAll()
        assertEquals(1, read.size)
        assertEquals("good_id", read[0].second.id)
        assertEquals("Do X.", read[0].second.instructions)
    }

    @Test
    fun `remove deletes the file`() {
        store.save("""{"id":"good_id","instructions":"x"}""")
        assertTrue(store.exists("good_id"))
        assertTrue(store.remove("good_id"))
        assertFalse(store.exists("good_id"))
    }

    @Test
    fun `remove on missing returns false`() {
        assertFalse(store.remove("never"))
    }

    @Test
    fun `safeFilename strips and lowercases`() {
        assertEquals("one_two", CommunitySkillStore.safeFilename("One_Two"))
        assertEquals("abcdef", CommunitySkillStore.safeFilename("ABC/def"))
        assertEquals("skill", CommunitySkillStore.safeFilename(""))
        assertEquals("a".repeat(64), CommunitySkillStore.safeFilename("a".repeat(200)))
    }

    @Test
    fun `readAll skips malformed files`() {
        store.save("""{"id":"good_id","instructions":"x"}""")
        File(dir, "bad.json").writeText("not json")
        val read = store.readAll()
        assertEquals(1, read.size)
        assertEquals("good_id", read[0].second.id)
    }
}
