package com.gotcha.ui

import com.gotcha.data.GotchaStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Pure grouping behind ChatAssetsScreen: no Android framework needed. */
class ChatAssetSectionsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(dir: File, name: String, modified: Long): File {
        dir.mkdirs()
        return File(dir, name).apply {
            writeText("x")
            setLastModified(modified)
        }
    }

    @Test
    fun `empty folder has no sections`() {
        assertTrue(chatAssetSections(tmp.root).isEmpty())
    }

    @Test
    fun `kinds come first loose files last newest first`() {
        val chatDir = tmp.root
        file(File(chatDir, "Pictures"), "old.png", 1000)
        file(File(chatDir, "Pictures"), "new.png", 3000)
        file(chatDir, "note.md", 2000)

        val sections = chatAssetSections(chatDir)

        assertEquals(
            listOf(GotchaStorage.Kind.PICTURES, null),
            sections.map { it.kind }
        )
        assertEquals(listOf("new.png", "old.png"), sections[0].items.map { it.name })
        assertEquals(listOf("note.md"), sections[1].items.map { it.name })
        assertTrue(sections[0].items[0].detail.startsWith("PNG · "))
    }

    @Test
    fun `empty kind folders are skipped`() {
        val chatDir = tmp.root
        File(chatDir, "Recordings").mkdirs()
        file(chatDir, "audio.m4a", 1000)

        val sections = chatAssetSections(chatDir)

        // Only the loose file: the empty Recordings dir adds no section.
        assertEquals(1, sections.size)
        assertEquals(null, sections[0].kind)
    }

    @Test
    fun `debug folders are not listed`() {
        val chatDir = tmp.root
        file(File(chatDir, GotchaStorage.Kind.DEBUG.dirName), "trace.txt", 1000)

        assertTrue(chatAssetSections(chatDir).isEmpty())
    }
}
