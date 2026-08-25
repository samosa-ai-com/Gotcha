package com.gotcha.tools

import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.gotcha.testsupport.ShadowExternalStorageManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `media_convert`'s guards — every refusal that happens before Termux is asked
 * to do anything.
 *
 * The conversion itself cannot be tested at any tier below INSTRUMENTED, and not
 * even there without Termux and its ffmpeg package installed on the device. What
 * *is* testable is everything that decides whether a call ever reaches ffmpeg,
 * which is also where a mistake costs a user their file. The command string that
 * would be sent is covered separately and exhaustively by [FfmpegCommandTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowExternalStorageManager::class])
class MediaConvertToolTest {

    private lateinit var tool: MediaConvertTool
    private lateinit var shared: File
    private lateinit var sandbox: File

    @Before
    fun setUp() {
        // Shared storage is where this tool insists its files live, so the tests
        // need "All files access" granted to reach any of the interesting guards.
        ShadowExternalStorageManager.granted = true
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        tool = MediaConvertTool(context)
        shared = File(Environment.getExternalStorageDirectory(), "convert").apply {
            deleteRecursively()
            mkdirs()
        }
        sandbox = File(context.filesDir, "convert").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        ShadowExternalStorageManager.resetGranted()
    }

    /** A file that exists but is not decodable — every case here refuses before ffmpeg runs. */
    private fun placeholder(dir: File, name: String): File =
        File(dir, name).apply { writeBytes(ByteArray(1024)) }

    private fun convert(
        input: String,
        output: String,
        bitrate: String? = null,
        overwrite: Boolean = false
    ): ToolResult = runBlocking { tool.convert(input, output, bitrate, overwrite) }

    // ---- target format ----

    @Test
    fun `an unsupported output format lists what is supported`() {
        val result = convert(placeholder(shared, "a.m4a").path, File(shared, "out.xyz").path)
        assertFalse(result.success)
        assertTrue(result.message.contains(".mp3"))
        assertTrue(result.message.contains(".flac"))
    }

    @Test
    fun `a video output is redirected to media_edit rather than attempted`() {
        val result = convert(placeholder(shared, "a.m4a").path, File(shared, "out.mp4").path)
        assertFalse(result.success)
        assertTrue(result.message.contains("media_edit"))
    }

    @Test
    fun `every advertised target is actually accepted`() {
        // Guards against the tool description promising a format the table lacks.
        FfmpegCommand.AUDIO_TARGETS.keys.forEach { ext ->
            val result = convert(placeholder(shared, "a.m4a").path, File(shared, "out.$ext").path)
            assertFalse(ext, result.message.contains("does not convert to"))
        }
    }

    // ---- bitrate ----

    @Test
    fun `a nonsense bitrate is refused before anything runs`() {
        listOf("loud", "192kbps", "192k; reboot").forEach { bad ->
            val result = convert(
                placeholder(shared, "a.m4a").path,
                File(shared, "out.mp3").path,
                bitrate = bad
            )
            assertFalse(bad, result.success)
            assertTrue(bad, result.message.contains("not a bitrate"))
        }
    }

    // ---- the uid boundary, which is this tool's peculiar failure mode ----

    @Test
    fun `an input in Gotcha's private storage is refused with the reason`() {
        val result = convert(placeholder(sandbox, "a.m4a").path, File(shared, "out.mp3").path)
        assertFalse(result.success)
        assertTrue(result.message.contains("Termux cannot see"))
        assertTrue("should suggest where to put it", result.message.contains("Music"))
    }

    @Test
    fun `an output in Gotcha's private storage is refused too`() {
        val result = convert(placeholder(shared, "a.m4a").path, File(sandbox, "out.mp3").path)
        assertFalse(result.success)
        assertTrue(result.message.contains("Termux cannot see"))
    }

    // ---- input resolution ----

    @Test
    fun `a missing input suggests how to find it`() {
        val result = convert(File(shared, "nope.m4a").path, File(shared, "out.mp3").path)
        assertFalse(result.success)
        assertTrue(result.message.contains("glob"))
    }

    @Test
    fun `a directory is not accepted as input`() {
        val result = convert(shared.path, File(shared, "out.mp3").path)
        assertFalse(result.success)
        assertTrue(result.message.contains("not a regular file"))
    }

    // ---- output resolution ----

    @Test
    fun `converting a file onto itself is refused`() {
        val same = placeholder(shared, "a.mp3")
        val result = convert(same.path, same.path, overwrite = true)
        assertFalse(result.success)
        assertTrue(result.message.contains("same file"))
    }

    @Test
    fun `an existing output is refused unless overwrite is passed`() {
        val existing = File(shared, "out.mp3").apply { writeBytes(ByteArray(8)) }
        val result = convert(placeholder(shared, "a.m4a").path, existing.path)
        assertFalse(result.success)
        assertTrue(result.message.contains("overwrite=true"))
        assertTrue("the file in the way must be untouched", existing.length() == 8L)
    }

    @Test
    fun `a directory named like a target file is still refused as a directory`() {
        // A bare directory path has no extension and is caught by the format check.
        // The one that reaches the directory guard is a folder called "out.mp3",
        // which ffmpeg would otherwise fail on with something unreadable.
        val trap = File(shared, "out.mp3").apply { mkdirs() }
        val result = convert(placeholder(shared, "a.m4a").path, trap.path)
        assertFalse(result.success)
        assertTrue(result.message.contains("is a directory"))
    }

    @Test
    fun `a bare directory path is refused for having no format`() {
        val result = convert(placeholder(shared, "a.m4a").path, shared.path)
        assertFalse(result.success)
        assertTrue(result.message.contains("does not convert to"))
    }
}
