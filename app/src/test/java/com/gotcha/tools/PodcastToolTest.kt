package com.gotcha.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.audio.AudioModel
import com.gotcha.audio.AudioProvider
import com.gotcha.audio.ModelCategory
import com.gotcha.audio.VoiceInfo
import com.gotcha.data.GotchaStorage
import com.gotcha.data.Settings
import com.gotcha.testsupport.ShadowExternalStorageManager
import kotlinx.coroutines.runBlocking
import org.junit.After
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
 * `synthesize_podcast`'s guards — every refusal that happens before, or instead
 * of, spending TTS API money.
 *
 * The assembly itself (Media3 Transformer) cannot run below the INSTRUMENTED
 * tier, same as media_edit. What is testable here is the decision layer:
 * provider/credential checks, script validation, destination handling, voice
 * resolution, and how a misbehaving TTS endpoint is reported. The pure
 * chunking/WAV logic is covered separately by [PodcastAudioTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowExternalStorageManager::class])
class PodcastToolTest {

    private lateinit var context: Context

    /** A backend that records what it was asked and answers from a script. */
    private class FakeBackend(
        var models: List<AudioModel> = emptyList(),
        var answer: (String) -> Result<ByteArray> = { Result.failure(IOException("unreachable")) }
    ) : PodcastTool.TtsBackend {
        val synthesizedWith = mutableListOf<Triple<String, String, String>>()
        override fun listModels(): List<AudioModel> = models
        override fun synthesize(text: String, model: String, voice: String): Result<ByteArray> {
            synthesizedWith.add(Triple(text, model, voice))
            return answer(text)
        }
    }

    private val apiSettings = Settings(
        ttsProvider = AudioProvider.API,
        ttsApiBaseUrl = "http://localhost:8880/v1",
        ttsApiModel = "kokoro",
        ttsVoice = "af_heart"
    )

    @Before
    fun setUp() {
        ShadowExternalStorageManager.granted = true
        context = ApplicationProvider.getApplicationContext()
        GotchaStorage.podcastsRoot().deleteRecursively()
    }

    @After
    fun tearDown() {
        ShadowExternalStorageManager.resetGranted()
    }

    private fun tool(
        settings: Settings = apiSettings,
        backend: FakeBackend = FakeBackend(),
        termux: Boolean = false
    ): PodcastTool = PodcastTool(
        context,
        loadSettings = { settings },
        ttsBackendFactory = { _, _ -> backend },
        termuxUsable = { termux }
    )

    private fun synthesize(
        tool: PodcastTool,
        script: String = "A perfectly ordinary sentence.",
        name: String = "episode",
        model: String? = null,
        voice: String? = null,
        format: String? = null,
        overwrite: Boolean = false
    ): ToolResult = runBlocking { tool.synthesize(script, name, model, voice, format, overwrite) }

    // ---- provider and credential guards ----

    @Test
    fun `the Android provider is refused with directions to Settings`() {
        val result = synthesize(tool(settings = Settings(ttsProvider = AudioProvider.ANDROID)))
        assertFalse(result.success)
        assertTrue(result.message.contains("Settings"))
        assertTrue(result.message.contains("cannot write audio to a file"))
    }

    @Test
    fun `Samosa AI without a session asks for a sign-in, not a crash`() {
        val result = synthesize(tool(settings = Settings(ttsProvider = AudioProvider.SAMOSA_AI)))
        assertFalse(result.success)
        assertTrue(result.message.contains("sign in"))
    }

    @Test
    fun `an External API provider with no base URL is refused`() {
        val result = synthesize(tool(settings = Settings(ttsProvider = AudioProvider.API)))
        assertFalse(result.success)
        assertTrue(result.message.contains("base URL"))
    }

    @Test
    fun `a blank configured model is its own error, not a provider error`() {
        val settings = apiSettings.copy(ttsApiModel = "")
        val result = synthesize(tool(settings = settings))
        assertFalse(result.success)
        assertTrue(result.message.contains("model"))
    }

    // ---- script guards ----

    @Test
    fun `a script that sanitizes to nothing is refused before any API call`() {
        val backend = FakeBackend()
        val result = synthesize(tool(backend = backend), script = "```kotlin\nval x = 1\n``` 🎉")
        assertFalse(result.success)
        assertTrue(backend.synthesizedWith.isEmpty())
    }

    @Test
    fun `a script past the ceiling is refused before any API call`() {
        val backend = FakeBackend()
        val script = "This sentence repeats. ".repeat(2000)
        val result = synthesize(tool(backend = backend), script = script)
        assertFalse(result.success)
        assertTrue(result.message.contains(PodcastTool.MAX_SCRIPT_CHARS.toString()))
        assertTrue(backend.synthesizedWith.isEmpty())
    }

    // ---- format and destination guards ----

    @Test
    fun `an unsupported format is refused and names the two supported ones`() {
        val result = synthesize(tool(), format = "wav")
        assertFalse(result.success)
        assertTrue(result.message.contains("m4a"))
        assertTrue(result.message.contains("mp3"))
    }

    @Test
    fun `an unrecognised extension on output_name is refused when no format is given`() {
        val result = synthesize(tool(), name = "episode.ogg")
        assertFalse(result.success)
    }

    @Test
    fun `an existing output without overwrite is refused before any API call`() {
        val backend = FakeBackend()
        GotchaStorage.podcastsRoot().mkdirs()
        File(GotchaStorage.podcastsRoot(), "episode.m4a").writeBytes(ByteArray(4))
        val result = synthesize(tool(backend = backend))
        assertFalse(result.success)
        assertTrue(result.message.contains("overwrite"))
        assertTrue(backend.synthesizedWith.isEmpty())
    }

    @Test
    fun `output_name is slugified rather than trusted`() {
        GotchaStorage.podcastsRoot().mkdirs()
        File(GotchaStorage.podcastsRoot(), "my-morning-news.m4a").writeBytes(ByteArray(4))
        // The collision proves '../my morning*news!.wtf' resolved to the slug, not a path escape.
        val result = synthesize(tool(), name = "../my morning*news!", overwrite = false)
        assertFalse(result.success)
        assertTrue(result.message.contains("my-morning-news"))
    }

    // ---- voice resolution ----

    @Test
    fun `with no configured voice the model's own default voice is used`() {
        val backend = FakeBackend(
            models = listOf(
                AudioModel(
                    id = "kokoro",
                    category = ModelCategory.TTS,
                    voices = listOf(VoiceInfo(id = "bf_emma"), VoiceInfo(id = "af_heart"))
                )
            )
        )
        synthesize(tool(settings = apiSettings.copy(ttsVoice = ""), backend = backend))
        assertEquals("bf_emma", backend.synthesizedWith.single().third)
    }

    @Test
    fun `an explicit voice argument beats the configured one`() {
        val backend = FakeBackend()
        synthesize(tool(backend = backend), voice = "bm_george")
        assertEquals("bm_george", backend.synthesizedWith.single().third)
    }

    @Test
    fun `no voice anywhere and an unreachable model list is an actionable error`() {
        val result = synthesize(tool(settings = apiSettings.copy(ttsVoice = "")))
        assertFalse(result.success)
        assertTrue(result.message.contains("voice"))
    }

    // ---- TTS endpoint misbehaviour ----

    @Test
    fun `a failed TTS request names the segment and writes nothing`() {
        val backend = FakeBackend(answer = { Result.failure(IOException("HTTP 401: bad key")) })
        val result = synthesize(tool(backend = backend))
        assertFalse(result.success)
        assertTrue(result.message.contains("segment 1"))
        assertTrue(result.message.contains("HTTP 401"))
        assertFalse(File(GotchaStorage.podcastsRoot(), "episode.m4a").exists())
    }

    @Test
    fun `a non-WAV response is diagnosed instead of assembled`() {
        val backend = FakeBackend(answer = { Result.success("{\"error\":\"no such voice\"}".toByteArray()) })
        val result = synthesize(tool(backend = backend))
        assertFalse(result.success)
        assertTrue(result.message.contains("not WAV"))
    }

    @Test
    fun `a long script reaches the API as multiple whole-sentence segments`() {
        val backend = FakeBackend(answer = { Result.failure(IOException("stop after capture")) })
        val script = (1..400).joinToString(" ") { "Sentence number $it carries some words along." }
        synthesize(tool(backend = backend), script = script)
        // Only the first segment is attempted (it fails), but its size proves chunking ran.
        val sent = backend.synthesizedWith.single().first
        assertTrue(sent.length <= PodcastTool.MAX_CHUNK_CHARS)
        assertTrue(sent.endsWith("."))
    }
}
