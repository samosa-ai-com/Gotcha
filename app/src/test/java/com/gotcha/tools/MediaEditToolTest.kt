package com.gotcha.tools

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `media_edit`'s guards — every refusal that happens *before* a codec is touched.
 *
 * The export path itself cannot be tested here at any tier below INSTRUMENTED:
 * Media3 Transformer drives MediaCodec, which is native and has no Robolectric
 * shadow, so a real device is the only place a trim can actually be exercised.
 * That makes these guards worth pinning down all the more — they are the part
 * that decides whether a wrong call destroys a file or returns an explanation,
 * and they are reachable without hardware precisely because [MediaEditTool]
 * validates paths before it opens anything.
 *
 * Everything stays inside the app sandbox so [FileResolver] grants access
 * without a permission stub.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaEditToolTest {

    private lateinit var tool: MediaEditTool
    private lateinit var dir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        tool = MediaEditTool(context)
        dir = File(context.filesDir, "mediatest").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    /**
     * A file that exists and is plausibly sized, but is not decodable. Every
     * assertion below is about a refusal that must land before anything tries to
     * parse it, so the bytes never matter.
     */
    private fun placeholder(name: String): File =
        File(dir, name).apply { writeBytes(ByteArray(1024)) }

    private fun edit(
        operation: String,
        input: String? = null,
        output: String? = null,
        start: String? = null,
        end: String? = null,
        overwrite: Boolean = false
    ): ToolResult = runBlocking {
        tool.edit(operation, input, output, start, end, overwrite)
    }

    // ---- operation and argument validation ----

    @Test
    fun `an unknown operation lists the valid ones`() {
        val result = edit("sharpen", input = placeholder("a.mp4").path)
        assertFalse(result.success)
        MediaEditTool.OPERATIONS.forEach { assertTrue(it, result.message.contains(it)) }
    }

    @Test
    fun `a missing input names the parameter and the operation`() {
        val result = edit("info")
        assertFalse(result.success)
        assertTrue(result.message.contains("input"))
        assertTrue(result.message.contains("info"))
    }

    @Test
    fun `a missing output is refused for every writing operation`() {
        listOf("trim", "extract_audio", "remove_audio").forEach { op ->
            val result = edit(op, input = placeholder("a.mp4").path, start = "1")
            assertFalse(op, result.success)
            assertTrue(op, result.message.contains("output"))
        }
    }

    @Test
    fun `trim with neither bound is refused rather than copying the file`() {
        val result = edit("trim", input = placeholder("a.mp4").path, output = File(dir, "out.mp4").path)
        assertFalse(result.success)
        assertTrue(result.message.contains("at least one"))
    }

    @Test
    fun `a malformed timecode is reported without writing anything`() {
        val out = File(dir, "out.mp4")
        val result = edit("trim", input = placeholder("a.mp4").path, output = out.path, start = "banana")
        assertFalse(result.success)
        assertFalse(out.exists())
    }

    // ---- input resolution ----

    @Test
    fun `a missing input file suggests how to find it`() {
        val result = edit("info", input = File(dir, "nope.mp4").path)
        assertFalse(result.success)
        assertTrue(result.message.contains("glob"))
    }

    @Test
    fun `a directory is not accepted as input`() {
        val result = edit("info", input = dir.path)
        assertFalse(result.success)
        assertTrue(result.message.contains("not a regular file"))
    }

    // ---- output resolution: the guards that protect a user's files ----

    @Test
    fun `writing back over the input is refused with the reason`() {
        val input = placeholder("clip.mp4")
        val result = edit("trim", input = input.path, output = input.path, start = "1", overwrite = true)
        assertFalse(result.success)
        assertTrue(result.message.contains("same file"))
        // The suggested alternative has to be a usable path, not a shrug.
        assertTrue(result.message.contains("clip-edited.mp4"))
    }

    @Test
    fun `an existing output is refused unless overwrite is passed`() {
        val existing = File(dir, "out.mp4").apply { writeBytes(ByteArray(8)) }
        val result = edit("trim", input = placeholder("a.mp4").path, output = existing.path, start = "1")
        assertFalse(result.success)
        assertTrue(result.message.contains("overwrite=true"))
        // Refusal must not have touched the file that was in the way.
        assertTrue(existing.length() == 8L)
    }

    @Test
    fun `a directory as output is refused`() {
        val result = edit("trim", input = placeholder("a.mp4").path, output = dir.path, start = "1")
        assertFalse(result.success)
        assertTrue(result.message.contains("is a directory"))
    }

    @Test
    fun `an output container the muxer cannot write is refused`() {
        listOf("out.mp3", "out.wav", "out.mkv", "out").forEach { name ->
            val result = edit("trim", input = placeholder("a.mp4").path, output = File(dir, name).path, start = "1")
            assertFalse(name, result.success)
            assertTrue(name, result.message.contains(".mp4") && result.message.contains(".m4a"))
        }
    }

    @Test
    fun `the supported output containers pass the extension guard`() {
        // These get past validation and fail later at the codec, which Robolectric
        // has no shadow for — the point is only that the guard let them through.
        listOf("out.mp4", "out.M4A").forEach { name ->
            val result = edit("trim", input = placeholder("a.mp4").path, output = File(dir, name).path, start = "1")
            assertFalse(name, result.message.contains("name it .mp4"))
        }
    }
}
