package com.gotcha.ui

import com.gotcha.data.GotchaStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Pure listing behind ChatAssetsScreen: no Android framework needed. */
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
    fun `empty folder has no entries`() {
        assertTrue(chatDirEntries(tmp.root).isEmpty())
    }

    @Test
    fun `folders first alphabetical then files newest first`() {
        val dir = tmp.root
        file(File(dir, "zebra"), "a.txt", 1000)
        file(File(dir, "apple"), "b.txt", 1000)
        file(File(dir, "Pictures"), "old.png", 1000)
        file(File(dir, "Pictures"), "new.png", 3000)
        file(dir, "note.md", 2000)

        // Only immediate children: the PNGs live one level down.
        val entries = chatDirEntries(dir)

        val folders = entries.filterIsInstance<ChatAssetEntry.Folder>().map { it.name }
        assertEquals(listOf("apple", "Pictures", "zebra"), folders)
        val files = entries.filterIsInstance<ChatAssetEntry.Asset>()
        assertEquals(listOf("note.md"), files.map { it.name })
        // Every folder sorts before every file.
        assertTrue(
            entries.indexOfLast { it is ChatAssetEntry.Folder } <
                entries.indexOfFirst { it is ChatAssetEntry.Asset }
        )

        // Inside Pictures: newest first, with type and size in the detail.
        val photos = chatDirEntries(File(dir, "Pictures"))
            .filterIsInstance<ChatAssetEntry.Asset>()
        assertEquals(listOf("new.png", "old.png"), photos.map { it.name })
        assertTrue(photos[0].detail.startsWith("PNG · "))
    }

    @Test
    fun `dot entries stay hidden`() {
        val dir = tmp.root
        file(File(dir, GotchaStorage.Kind.DEBUG.dirName), "trace.txt", 1000)
        file(dir, ".nomedia", 1000)
        file(dir, "visible.txt", 1000)

        val entries = chatDirEntries(dir)

        assertEquals(listOf("visible.txt"), entries.map { it.name })
    }
}
