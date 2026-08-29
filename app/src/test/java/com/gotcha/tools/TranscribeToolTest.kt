package com.gotcha.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.audio.AudioModel
import com.gotcha.audio.AudioProvider
import com.gotcha.audio.ModelCategory
import com.gotcha.data.Settings
import com.gotcha.testsupport.ShadowExternalStorageManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * `transcribe_file`'s guards, and the property that justifies its existence:
 * unlike `SttEngine.transcribeApi`, which deletes its input after upload,
 * this tool must leave the user's recording exactly as it found it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowExternalStorageManager::class])
class TranscribeToolTest {

    private lateinit var context: Context
    private lateinit var audioDir: File

    private class FakeBackend(
        var models: List<AudioModel> = emptyList(),
        var answer: () -> Result<String> = { Result.failure(IOException("unreachable")) }
    ) : TranscribeTool.SttBackend {
        val calls = mutableListOf<Triple<File, String, String?>>()
        override fun listModels(): List<AudioModel> = models
        override fun transcribe(
            file: File,
            model: String,
            language: String?,
            contentType: String
        ): Result<String> {
            calls.add(Triple(file, model, language))
            return answer()
        }
    }

    private val apiSettings = Settings(
        sttProvider = AudioProvider.API,
        sttApiBaseUrl = "http://localhost:8880/v1",
        sttApiModel = "whisper-1"
    )

    @Before
    fun setUp() {
        ShadowExternalStorageManager.granted = true
        context = ApplicationProvider.getApplicationContext()
        audioDir = File(context.filesDir, "memos").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        ShadowExternalStorageManager.resetGranted()
    }

    private fun tool(
        settings: Settings = apiSettings,
        backend: FakeBackend = FakeBackend()
    ): TranscribeTool = TranscribeTool(context, loadSettings = { settings }, sttBackendFactory = { _, _ -> backend })

    private fun memo(name: String = "memo.m4a", bytes: ByteArray = ByteArray(2048) { it.toByte() }): File =
        File(audioDir, name).apply { writeBytes(bytes) }

    // ---- provider guards ----

    @Test
    fun `the Android provider is refused with directions to Settings`() {
        val result = tool(settings = Settings(sttProvider = AudioProvider.ANDROID)).transcribe(memo().path)
        assertFalse(result.success)
        assertTrue(result.message.contains("Settings"))
    }

    @Test
    fun `Samosa AI without a session asks for a sign-in`() {
        val result = tool(settings = Settings(sttProvider = AudioProvider.SAMOSA_AI)).transcribe(memo().path)
        assertFalse(result.success)
        assertTrue(result.message.contains("sign in"))
    }

    // ---- file guards ----

    @Test
    fun `a missing file is reported with its resolved path`() {
        val result = tool().transcribe(File(audioDir, "nope.m4a").path)
        assertFalse(result.success)
        assertTrue(result.message.contains("does not exist"))
    }

    @Test
    fun `an empty file is refused before the backend is touched`() {
        val backend = FakeBackend()
        val result = tool(backend = backend).transcribe(memo(bytes = ByteArray(0)).path)
        assertFalse(result.success)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `an unsupported extension is refused and lists what is supported`() {
        val backend = FakeBackend()
        val result = tool(backend = backend).transcribe(memo(name = "video.mkv").path)
        assertFalse(result.success)
        assertTrue(result.message.contains(".m4a"))
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `an oversized file is refused with the limit stated`() {
        val big = File(audioDir, "long.m4a")
        big.outputStream().use { out ->
            val chunk = ByteArray(1024 * 1024)
            repeat(26) { out.write(chunk) }
        }
        val result = tool().transcribe(big.path)
        assertFalse(result.success)
        assertTrue(result.message.contains("25.0 MB"))
    }

    // ---- the survival property ----

    @Test
    fun `the input file survives transcription byte for byte`() {
        val original = ByteArray(4096) { (it * 7).toByte() }
        val file = memo(bytes = original)
        val backend = FakeBackend(answer = { Result.success("hello from the memo") })

        val result = tool(backend = backend).transcribe(file.path)

        assertTrue(result.message, result.success)
        assertTrue(result.message.contains("hello from the memo"))
        assertTrue("input file was deleted", file.exists())
        assertArrayEquals("input file was modified", original, file.readBytes())
    }

    @Test
    fun `a failed upload also leaves the file in place`() {
        val file = memo()
        val backend = FakeBackend(answer = { Result.failure(IOException("HTTP 502")) })
        val result = tool(backend = backend).transcribe(file.path)
        assertFalse(result.success)
        assertTrue(result.message.contains("HTTP 502"))
        assertTrue(file.exists())
    }

    // ---- model and language resolution ----

    @Test
    fun `an explicit model beats the configured one`() {
        val backend = FakeBackend(answer = { Result.success("ok") })
        tool(backend = backend).transcribe(memo().path, model = "whisper-large")
        assertEquals("whisper-large", backend.calls.single().second)
    }

    @Test
    fun `with nothing configured the first advertised STT model is used`() {
        val backend = FakeBackend(
            models = listOf(
                AudioModel(id = "kokoro", category = ModelCategory.TTS),
                AudioModel(id = "parakeet", category = ModelCategory.STT)
            ),
            answer = { Result.success("ok") }
        )
        tool(settings = apiSettings.copy(sttApiModel = ""), backend = backend).transcribe(memo().path)
        assertEquals("parakeet", backend.calls.single().second)
    }

    @Test
    fun `no model anywhere is an actionable error, not a blank upload`() {
        val backend = FakeBackend()
        val result = tool(settings = apiSettings.copy(sttApiModel = ""), backend = backend).transcribe(memo().path)
        assertFalse(result.success)
        assertTrue(result.message.contains("model"))
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `a blank configured language is sent as no hint at all`() {
        val backend = FakeBackend(answer = { Result.success("ok") })
        tool(backend = backend).transcribe(memo().path)
        assertEquals(null, backend.calls.single().third)
    }

    // ---- transcript handling ----

    @Test
    fun `an empty transcript is an error, not a silent success`() {
        val backend = FakeBackend(answer = { Result.success("   ") })
        val result = tool(backend = backend).transcribe(memo().path)
        assertFalse(result.success)
        assertTrue(result.message.contains("empty transcript"))
    }

    @Test
    fun `an over-long transcript is truncated with an honest note`() {
        val backend = FakeBackend(
            answer = { Result.success("word ".repeat(TranscribeTool.MAX_TRANSCRIPT_CHARS)) }
        )
        val result = tool(backend = backend).transcribe(memo().path)
        assertTrue(result.success)
        assertTrue(result.message.contains("truncated"))
        assertTrue(result.message.length < TranscribeTool.MAX_TRANSCRIPT_CHARS + 1000)
    }
}
