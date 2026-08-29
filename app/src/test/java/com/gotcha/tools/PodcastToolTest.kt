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

    // ---- dialogue ----

    private fun dialogue(
        tool: PodcastTool,
        lines: List<PodcastTool.DialogueLine>,
        hostAVoice: String? = null,
        hostBVoice: String? = null,
        gapMs: Long? = null
    ): ToolResult = runBlocking {
        tool.synthesizeDialogue(lines, "roundtable", hostAVoice, hostBVoice, gapMs = gapMs)
    }

    private val twoTurns = listOf(
        PodcastTool.DialogueLine("A", "Welcome to the show."),
        PodcastTool.DialogueLine("B", "Great to be here.")
    )

    /** 100ms of 24kHz mono PCM16 — a valid WAV for the fake backend to return. */
    private fun fakeWav(): ByteArray {
        val dataBytes = 4_800
        val out = java.io.ByteArrayOutputStream()
        fun ascii(s: String) = s.forEach { out.write(it.code) }
        fun le32(v: Int) = repeat(4) { out.write((v shr (8 * it)) and 0xFF) }
        fun le16(v: Int) = repeat(2) { out.write((v shr (8 * it)) and 0xFF) }
        ascii("RIFF")
        le32(36 + dataBytes)
        ascii("WAVE")
        ascii("fmt ")
        le32(16)
        le16(1)
        le16(1)
        le32(24_000)
        le32(48_000)
        le16(2)
        le16(16)
        ascii("data")
        le32(dataBytes)
        out.write(ByteArray(dataBytes))
        return out.toByteArray()
    }

    /** Succeeds [okCalls] times with a real WAV, then fails — lets voice capture cross turns. */
    private fun backendFailingAfter(okCalls: Int, models: List<AudioModel> = emptyList()): FakeBackend {
        var calls = 0
        return FakeBackend(models = models).apply {
            answer = {
                if (calls++ < okCalls) Result.success(fakeWav()) else Result.failure(IOException("stop here"))
            }
        }
    }

    @Test
    fun `a dialogue line with an unknown speaker is refused before any API call`() {
        val backend = FakeBackend()
        val lines = twoTurns + PodcastTool.DialogueLine("C", "Who am I?")
        val result = dialogue(tool(backend = backend), lines)
        assertFalse(result.success)
        assertTrue(result.message.contains("'C'"))
        assertTrue(backend.synthesizedWith.isEmpty())
    }

    @Test
    fun `an empty dialogue is refused before any API call`() {
        val backend = FakeBackend()
        val result = dialogue(tool(backend = backend), emptyList())
        assertFalse(result.success)
        assertTrue(backend.synthesizedWith.isEmpty())
    }

    @Test
    fun `the hosts get distinct voices by default, host B from the model's own list`() {
        val backend = backendFailingAfter(
            okCalls = 1,
            models = listOf(
                AudioModel(
                    id = "kokoro",
                    category = ModelCategory.TTS,
                    voices = listOf(VoiceInfo(id = "af_heart"), VoiceInfo(id = "bm_george"))
                )
            )
        )
        dialogue(tool(backend = backend), twoTurns)
        assertEquals(listOf("af_heart", "bm_george"), backend.synthesizedWith.map { it.third })
    }

    @Test
    fun `configured host voices win over derived defaults`() {
        val backend = backendFailingAfter(okCalls = 1)
        val settings = apiSettings.copy(podcastHostAVoice = "am_adam", podcastHostBVoice = "bf_emma")
        dialogue(tool(settings = settings, backend = backend), twoTurns)
        assertEquals(listOf("am_adam", "bf_emma"), backend.synthesizedWith.map { it.third })
    }

    @Test
    fun `explicit host voice arguments beat the configured ones`() {
        val backend = backendFailingAfter(okCalls = 1)
        val settings = apiSettings.copy(podcastHostAVoice = "am_adam", podcastHostBVoice = "bf_emma")
        dialogue(tool(settings = settings, backend = backend), twoTurns, hostAVoice = "aa", hostBVoice = "bb")
        assertEquals(listOf("aa", "bb"), backend.synthesizedWith.map { it.third })
    }

    // ---- pacing ----

    @Test
    fun `a line without pause_ms falls back to the episode default`() {
        assertEquals(
            PodcastTool.DIALOGUE_GAP_MS,
            PodcastTool.resolvePause(requested = null, default = PodcastTool.DIALOGUE_GAP_MS)
        )
    }

    @Test
    fun `a line's own pause_ms overrides the episode default`() {
        assertEquals(750L, PodcastTool.resolvePause(requested = 750L, default = PodcastTool.DIALOGUE_GAP_MS))
    }

    @Test
    fun `a zero pause is honoured, not treated as unset`() {
        // The difference that matters: "run these turns together" must not silently become 300ms.
        assertEquals(0L, PodcastTool.resolvePause(requested = 0L, default = PodcastTool.DIALOGUE_GAP_MS))
    }

    @Test
    fun `an absurd or negative pause is clamped rather than obeyed`() {
        assertEquals(PodcastTool.MAX_TURN_PAUSE_MS, PodcastTool.resolvePause(999_999L, PodcastTool.DIALOGUE_GAP_MS))
        assertEquals(0L, PodcastTool.resolvePause(-5_000L, PodcastTool.DIALOGUE_GAP_MS))
    }

    @Test
    fun `a hallucinated gap_ms cannot stretch the episode through the lines that inherit it`() {
        val episodeGap = PodcastTool.resolvePause(60_000L, PodcastTool.DIALOGUE_GAP_MS)
        assertEquals(PodcastTool.MAX_TURN_PAUSE_MS, episodeGap)
        assertEquals(PodcastTool.MAX_TURN_PAUSE_MS, PodcastTool.resolvePause(null, episodeGap))
    }

    @Test
    fun `a dialogue with custom pacing still reaches the API with its text intact`() {
        val backend = backendFailingAfter(okCalls = 1)
        val paced = listOf(
            PodcastTool.DialogueLine("A", "So here's the thing.", pauseMs = 800),
            PodcastTool.DialogueLine("B", "Go on.", pauseMs = 150)
        )
        dialogue(tool(backend = backend), paced, gapMs = 250L)
        assertEquals(listOf("So here's the thing.", "Go on."), backend.synthesizedWith.map { it.first })
    }

    @Test
    fun `blank dialogue turns are dropped rather than synthesized as silence`() {
        val backend = FakeBackend(answer = { Result.failure(IOException("capture only")) })
        val lines = listOf(
            PodcastTool.DialogueLine("A", "```only code```"),
            PodcastTool.DialogueLine("B", "The real opener.")
        )
        dialogue(tool(backend = backend), lines)
        assertEquals("The real opener.", backend.synthesizedWith.single().first)
    }

    // ---- sharing ----

    private class ChooserCapture {
        var intent: android.content.Intent? = null
        val launcher: (android.content.Intent) -> Unit = { intent = it }
    }

    private fun shareTool(capture: ChooserCapture): PodcastTool =
        PodcastTool(context, loadSettings = { apiSettings }, startChooser = capture.launcher)

    @Test
    fun `sharing a missing file is an error and no chooser appears`() {
        val capture = ChooserCapture()
        val result = shareTool(capture).share(File(GotchaStorage.podcastsRoot(), "gone.m4a").path)
        assertFalse(result.success)
        assertTrue(capture.intent == null)
    }

    @Test
    fun `sharing a non-audio file is refused with the supported list`() {
        val capture = ChooserCapture()
        GotchaStorage.podcastsRoot().mkdirs()
        val file = File(GotchaStorage.podcastsRoot(), "notes.txt").apply { writeText("hi") }
        val result = shareTool(capture).share(file.path)
        assertFalse(result.success)
        assertTrue(result.message.contains(".m4a"))
        assertTrue(capture.intent == null)
    }

    @Test
    @Suppress("DEPRECATION") // getParcelableExtra's typed replacement needs API 33+ at the call site
    fun `sharing hands over a content URI chooser, never a file URI`() {
        val capture = ChooserCapture()
        GotchaStorage.podcastsRoot().mkdirs()
        val file = File(GotchaStorage.podcastsRoot(), "episode.m4a").apply { writeBytes(ByteArray(64)) }

        val result = shareTool(capture).share(file.path)

        assertTrue(result.message, result.success)
        val chooser = capture.intent!!
        assertEquals(android.content.Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
        assertEquals(android.content.Intent.ACTION_SEND, send.action)
        assertEquals("audio/mp4", send.type)
        val uri = send.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertTrue(send.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `a file outside the shareable folder is a move-it-first message, not a crash`() {
        val capture = ChooserCapture()
        val outside = File(context.cacheDir, "elsewhere.mp3").apply { writeBytes(ByteArray(16)) }
        val result = shareTool(capture).share(outside.path)
        assertFalse(result.success)
        assertTrue(capture.intent == null)
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
