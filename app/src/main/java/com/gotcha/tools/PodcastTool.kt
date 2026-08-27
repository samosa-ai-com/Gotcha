package com.gotcha.tools

import android.content.Context
import com.gotcha.audio.AudioApi
import com.gotcha.audio.AudioModel
import com.gotcha.audio.AudioProvider
import com.gotcha.audio.ModelCategory
import com.gotcha.audio.SpeechTextSanitizer
import com.gotcha.data.GotchaStorage
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Turns written text into spoken audio files — podcasts.
 *
 * Two entry points share one pipeline: [synthesize] narrates a script in a
 * single voice, [synthesizeDialogue] alternates two host voices with a short
 * silence between turns. Both reuse the OpenAI-compatible `/audio/speech`
 * endpoint the voice features already talk through, but deliberately not via
 * [com.gotcha.audio.TtsEngine]: that class is hardwired to play what it
 * fetches, while this one needs the raw WAV bytes on disk. Text is sanitized
 * exactly as spoken replies are, split at sentence boundaries (the endpoint
 * has input-length limits a whole script would blow through), synthesized
 * segment by segment into the cache dir, and joined on-device by [MediaExport]
 * into an `.m4a` under `Gotcha/Podcasts`.
 *
 * `.m4a` is the canonical output because it needs nothing beyond what ships in
 * the app. MP3 exists only through Termux's ffmpeg ([MediaConvertTool]), so a
 * requested `.mp3` quietly degrades to `.m4a` when Termux is unavailable — a
 * finished podcast in the wrong container beats no podcast.
 *
 * Requires an API-based TTS provider: Android's built-in engine has no
 * file-output path here. Only available to Operator (writes files).
 */
class PodcastTool(
    private val context: Context,
    private val loadSettings: () -> Settings = { SettingsRepository(context).load() },
    private val ttsBackendFactory: (baseUrl: String, apiKey: String) -> TtsBackend = { url, key ->
        ApiTtsBackend(AudioApi(url, key, timeoutSeconds = TTS_TIMEOUT_SECONDS))
    },
    private val mediaExport: MediaExport = MediaExport(context),
    private val mediaConvert: MediaConvertTool = MediaConvertTool(context),
    private val termuxUsable: () -> Boolean = { DeviceCapabilities.termuxUsable(context) }
) {

    /** The two calls synthesis needs from the TTS API, extracted for tests. */
    interface TtsBackend {
        fun listModels(): List<AudioModel>
        fun synthesize(text: String, model: String, voice: String): Result<ByteArray>
    }

    private class ApiTtsBackend(private val api: AudioApi) : TtsBackend {
        override fun listModels(): List<AudioModel> = api.listAudioModels()
        override fun synthesize(text: String, model: String, voice: String): Result<ByteArray> =
            api.synthesize(text, model, voice)
    }

    /** One turn of a dialogue script, as the model writes it. */
    data class DialogueLine(val speaker: String, val text: String)

    private val resolver = FileResolver(context)

    companion object {
        /**
         * Script ceiling. At a typical ~15 chars/second of speech this is
         * ~25 minutes of audio, safely inside [MAX_TOTAL_DURATION_MS] — the
         * cap that actually matters is on the audio, this one just refuses the
         * obviously hopeless script before any API spend.
         */
        const val MAX_SCRIPT_CHARS = 24_000

        /**
         * Per-request text limit. OpenAI-compatible speech endpoints cap
         * `input` around 4096 chars; staying well under leaves room for
         * providers that count differently.
         */
        const val MAX_CHUNK_CHARS = 3_000

        /** Same ceiling as media_edit's re-encode guard — assembly re-encodes everything. */
        const val MAX_TOTAL_DURATION_MS = MediaEditTool.MAX_REENCODE_DURATION_MS

        /**
         * Breathing room between dialogue turns. Long enough to read as a
         * speaker change, short enough not to read as dead air.
         */
        const val DIALOGUE_GAP_MS = 300L

        private const val TTS_TIMEOUT_SECONDS = 120L
        private const val FORMAT_M4A = "m4a"
        private const val FORMAT_MP3 = "mp3"
        private const val SPEAKER_A = "A"
        private const val SPEAKER_B = "B"

        /** Intermediate for the MP3 detour. Ends in `.m4a` so ffmpeg and the salvage rename both see AAC. */
        private const val TMP_SUFFIX = ".podcast-tmp.m4a"

        private const val ERROR_DETAIL_CHARS = 200
    }

    private sealed interface Resolved {
        data class Ok(val file: File) : Resolved
        data class Failed(val result: ToolResult) : Resolved
    }

    /** A single TTS request: what to say, and with which model and voice. */
    private data class SpeechSegment(val text: String, val model: String, val voice: String)

    /**
     * Everything one synthesis run carries between its stages. [groups] are
     * lists of segments; the silence gap is inserted *between groups*, never
     * between the chunks of one over-long piece of text.
     */
    private data class SynthesisJob(
        val backend: TtsBackend,
        val groups: List<List<SpeechSegment>>,
        val gapMs: Long,
        val tempDir: File,
        val destination: File,
        val wantMp3: Boolean,
        val baseName: String,
        val overwrite: Boolean,
        val notes: String,
        val voiceLabel: String
    )

    // ---- entry points ----

    @Suppress("ReturnCount", "LongParameterList")
    suspend fun synthesize(
        script: String,
        outputName: String,
        model: String? = null,
        voice: String? = null,
        format: String? = null,
        overwrite: Boolean = false
    ): ToolResult {
        val settings = loadSettings()
        credentialError(settings)?.let { return it }
        val requestedFormat = normaliseFormat(format, outputName) ?: return unsupportedFormat(format, outputName)
        val resolvedModel = (model ?: settings.ttsApiModel).trim()
        if (resolvedModel.isBlank()) return noModelConfigured()

        val text = SpeechTextSanitizer.sanitize(script)
        scriptError(text.length, emptyText = text.isBlank())?.let { return it }

        val backend = ttsBackendFactory(settings.effectiveTtsBaseUrl, settings.effectiveTtsApiKey)
        val resolvedVoice = resolveVoice(voice, settings.ttsVoice, backend, resolvedModel)
            ?: return noVoiceResolvable(resolvedModel)

        val segments = PodcastAudio.chunk(text, MAX_CHUNK_CHARS)
            .map { SpeechSegment(it, resolvedModel, resolvedVoice) }
        return runJob(
            backend = backend,
            groups = listOf(segments),
            gapMs = 0L,
            outputName = outputName,
            requestedFormat = requestedFormat,
            overwrite = overwrite,
            notes = "",
            voiceLabel = "voice '$resolvedVoice'"
        )
    }

    @Suppress("ReturnCount", "LongParameterList")
    suspend fun synthesizeDialogue(
        lines: List<DialogueLine>,
        outputName: String,
        hostAVoice: String? = null,
        hostBVoice: String? = null,
        hostAModel: String? = null,
        hostBModel: String? = null,
        format: String? = null,
        overwrite: Boolean = false
    ): ToolResult {
        val settings = loadSettings()
        credentialError(settings)?.let { return it }
        val requestedFormat = normaliseFormat(format, outputName) ?: return unsupportedFormat(format, outputName)
        val modelA = (hostAModel ?: settings.ttsApiModel).trim()
        val modelB = (hostBModel ?: modelA).trim()
        if (modelA.isBlank()) return noModelConfigured()

        val turns = lines.map { it.copy(text = SpeechTextSanitizer.sanitize(it.text)) }
            .filter { it.text.isNotBlank() }
        val badSpeaker = turns.firstOrNull { it.speaker != SPEAKER_A && it.speaker != SPEAKER_B }
        if (badSpeaker != null) {
            return ToolResult.error(
                "speaker '${badSpeaker.speaker}' is not a host — every line's speaker must be exactly " +
                    "'$SPEAKER_A' or '$SPEAKER_B'."
            )
        }
        scriptError(turns.sumOf { it.text.length }, emptyText = turns.isEmpty())?.let { return it }

        val backend = ttsBackendFactory(settings.effectiveTtsBaseUrl, settings.effectiveTtsApiKey)
        val voiceA = resolveVoice(hostAVoice, settings.podcastHostAVoice.ifBlank { settings.ttsVoice }, backend, modelA)
            ?: return noVoiceResolvable(modelA)
        val voiceB = resolveHostBVoice(hostBVoice, settings, backend, modelB, voiceA)
        val sameVoiceNote = if (voiceA == voiceB) {
            " Both hosts ended up with voice '$voiceA' — no second voice could be found automatically; pass " +
                "host_b_voice or set one in Settings → Speech to make them distinguishable."
        } else {
            ""
        }

        val groups = turns.map { turn ->
            val (model, voice) = if (turn.speaker == SPEAKER_A) modelA to voiceA else modelB to voiceB
            PodcastAudio.chunk(turn.text, MAX_CHUNK_CHARS).map { SpeechSegment(it, model, voice) }
        }
        return runJob(
            backend = backend,
            groups = groups,
            gapMs = DIALOGUE_GAP_MS,
            outputName = outputName,
            requestedFormat = requestedFormat,
            overwrite = overwrite,
            notes = sameVoiceNote,
            voiceLabel = "voices '$voiceA' (host A) and '$voiceB' (host B)"
        )
    }

    // ---- the shared pipeline ----

    @Suppress("ReturnCount", "LongParameterList")
    private suspend fun runJob(
        backend: TtsBackend,
        groups: List<List<SpeechSegment>>,
        gapMs: Long,
        outputName: String,
        requestedFormat: String,
        overwrite: Boolean,
        notes: String,
        voiceLabel: String
    ): ToolResult {
        val wantMp3 = requestedFormat == FORMAT_MP3 && termuxUsable()
        val degradeNote = if (requestedFormat == FORMAT_MP3 && !wantMp3) {
            " MP3 was requested, but that conversion runs through Termux's ffmpeg, which is not available on " +
                "this device — the podcast was written as .m4a instead, which plays everywhere on Android."
        } else {
            ""
        }
        val baseName = GotchaStorage.slugify(File(outputName).nameWithoutExtension)
        val extension = if (wantMp3) FORMAT_MP3 else FORMAT_M4A
        val destination = when (val r = resolveDestination(baseName, extension, overwrite)) {
            is Resolved.Failed -> return r.result
            is Resolved.Ok -> r.file
        }
        val tempDir = File(context.cacheDir, "podcast_${System.currentTimeMillis()}")
        return try {
            produce(
                SynthesisJob(
                    backend = backend, groups = groups, gapMs = gapMs, tempDir = tempDir,
                    destination = destination, wantMp3 = wantMp3, baseName = baseName,
                    overwrite = overwrite, notes = notes + degradeNote, voiceLabel = voiceLabel
                )
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Suppress("ReturnCount")
    private suspend fun produce(job: SynthesisJob): ToolResult {
        job.tempDir.mkdirs()
        val totalSegments = job.groups.sumOf { it.size }
        val files = mutableListOf<File>()
        val silenceByFormat = mutableMapOf<Pair<Int, Int>, File>()
        var totalMs = 0L
        var index = 0
        var lastWav: PodcastAudio.WavInfo? = null
        for ((groupIndex, group) in job.groups.withIndex()) {
            for (segment in group) {
                coroutineContext.ensureActive()
                index++
                val bytes = job.backend.synthesize(segment.text, segment.model, segment.voice).getOrElse { e ->
                    return ToolResult.error(
                        "The TTS request failed on segment $index of $totalSegments: " +
                            "${e.message?.take(ERROR_DETAIL_CHARS) ?: "no detail"}. Nothing was written."
                    )
                }
                val info = PodcastAudio.wavInfo(bytes) ?: return ToolResult.error(
                    "The TTS endpoint returned something that is not WAV audio for segment $index. " +
                        "The model name or voice may be wrong for this provider — check them against the " +
                        "provider's model list."
                )
                totalMs += info.durationMs
                if (totalMs > MAX_TOTAL_DURATION_MS) return audioTooLong()
                files += File(job.tempDir, "segment_%03d.wav".format(index)).apply { writeBytes(bytes) }
                lastWav = info
            }
            val wav = lastWav
            if (job.gapMs > 0 && groupIndex < job.groups.lastIndex && wav != null) {
                files += silenceByFormat.getOrPut(wav.sampleRate to wav.channels) {
                    File(job.tempDir, "gap_${wav.sampleRate}_${wav.channels}.wav").apply {
                        writeBytes(PodcastAudio.silenceWav(job.gapMs, wav.sampleRate, wav.channels))
                    }
                }
                totalMs += job.gapMs
            }
        }

        val m4aOut = if (job.wantMp3) {
            File(job.destination.parentFile, "${job.baseName}$TMP_SUFFIX").apply { delete() }
        } else {
            job.destination
        }
        assemble(files, job.tempDir, m4aOut)?.let { return it }

        val (finalFile, conversionNote) = if (job.wantMp3) convertToMp3(m4aOut, job) else job.destination to ""
        GotchaStorage.publishToGallery(context, finalFile)
        return ToolResult.ok(
            "Created the podcast at '${finalFile.canonicalPath}' — ${MediaTimeSpec.format(totalMs)} of audio, " +
                "${resolver.formatSize(finalFile.length())}, synthesized as $totalSegments segment(s) with " +
                "${job.voiceLabel} and assembled on-device. It is visible in the phone's Files app under " +
                "Gotcha/Podcasts." + job.notes + conversionNote
        )
    }

    /**
     * Joins the segment WAVs into [output] as AAC. Temp files skip
     * [FileResolver] on purpose: they live in the app's own cache, where a
     * permission check has nothing to say — the shared-storage checks already
     * ran on the final destination. Batching counts every file, silence gaps
     * included — a 10-line dialogue is already 19 concat inputs.
     */
    private suspend fun assemble(wavs: List<File>, tempDir: File, output: File): ToolResult? {
        output.parentFile?.mkdirs()
        if (wavs.size == 1) return mediaExport.run(input = wavs[0], output = output).failure()
        var inputs: List<File> = wavs
        var round = 0
        while (inputs.size > MediaEditTool.MAX_CONCAT_INPUTS) {
            inputs = inputs.chunked(MediaEditTool.MAX_CONCAT_INPUTS).mapIndexed { batchIndex, batch ->
                val partial = File(tempDir, "batch_${round}_$batchIndex.m4a")
                val outcome = if (batch.size == 1) {
                    mediaExport.run(input = batch[0], output = partial)
                } else {
                    mediaExport.concat(batch, partial)
                }
                outcome.failure()?.let { return it }
                partial
            }
            round++
        }
        return mediaExport.concat(inputs, output).failure()
    }

    /**
     * The Termux detour. A failure here does not fail the run: the synthesis
     * money is already spent and the audio is already good, so it is salvaged
     * as `.m4a` with a note saying what happened and how to finish the job.
     */
    private suspend fun convertToMp3(m4a: File, job: SynthesisJob): Pair<File, String> {
        val result = mediaConvert.convert(m4a.absolutePath, job.destination.absolutePath, overwrite = job.overwrite)
        if (result.success) {
            m4a.delete()
            return job.destination to ""
        }
        val fallback = File(job.destination.parentFile, "${job.baseName}.$FORMAT_M4A")
        val kept = if ((job.overwrite || !fallback.exists()) && m4a.renameTo(fallback)) fallback else m4a
        return kept to " The MP3 conversion step failed " +
            "(${result.message.take(ERROR_DETAIL_CHARS)}) — the finished audio was kept as '${kept.name}'; " +
            "media_convert can turn it into an MP3 once that is fixed."
    }

    // ---- validation ----

    private fun credentialError(settings: Settings): ToolResult? {
        if (!settings.ttsProvider.isApiBased()) {
            return ToolResult.error(
                "The Text-to-Speech provider is '${settings.ttsProvider.label}', which can only speak aloud — " +
                    "it cannot write audio to a file. Ask the user to set Settings → Speech → Text-to-Speech " +
                    "to Samosa AI or External API, then try again."
            )
        }
        if (settings.effectiveTtsBaseUrl.isBlank()) {
            return ToolResult.error(
                if (settings.ttsProvider == AudioProvider.SAMOSA_AI) {
                    "TTS is set to Samosa AI but there is no active session. Ask the user to sign in again " +
                        "in Settings, then retry."
                } else {
                    "TTS is set to External API but no base URL is configured. Ask the user to complete " +
                        "Settings → Speech → Text-to-Speech, then retry."
                }
            )
        }
        return null
    }

    private fun scriptError(sanitizedLength: Int, emptyText: Boolean): ToolResult? = when {
        emptyText -> ToolResult.error(
            "The script is empty once markdown, code and emoji are stripped for speech. " +
                "Write the narration as plain prose and try again."
        )
        sanitizedLength > MAX_SCRIPT_CHARS -> ToolResult.error(
            "The script is $sanitizedLength characters; the limit is $MAX_SCRIPT_CHARS (~25 minutes of " +
                "speech). Split it into parts and synthesize each as its own file."
        )
        else -> null
    }

    private fun audioTooLong(): ToolResult = ToolResult.error(
        "The synthesized audio passed ${MediaTimeSpec.format(MAX_TOTAL_DURATION_MS)} before the " +
            "script was finished. Shorten the script or split it into parts."
    )

    /** The format arg wins; a recognisable extension on output_name is honoured when the arg is absent. */
    private fun normaliseFormat(format: String?, outputName: String): String? {
        val requested = format?.trim()?.lowercase().takeUnless { it.isNullOrBlank() }
            ?: File(outputName).extension.trim().lowercase().ifBlank { FORMAT_M4A }
        return requested.takeIf { it == FORMAT_M4A || it == FORMAT_MP3 }
    }

    private fun unsupportedFormat(format: String?, outputName: String): ToolResult = ToolResult.error(
        "format '${format ?: File(outputName).extension}' is not supported — a podcast is written as 'm4a' " +
            "(the default, no setup needed) or 'mp3' (via Termux's ffmpeg). For other formats, create the " +
            ".m4a first and convert it with media_convert."
    )

    private fun noModelConfigured(): ToolResult = ToolResult.error(
        "No TTS model is configured. Pass model explicitly, or ask the user to pick one in " +
            "Settings → Speech → Text-to-Speech."
    )

    private fun noVoiceResolvable(model: String): ToolResult = ToolResult.error(
        "No TTS voice is set and the API's voice list for '$model' could not be fetched. " +
            "Pass voice explicitly, or ask the user to pick one in Settings → Speech."
    )

    private fun resolveVoice(
        voiceArg: String?,
        configuredVoice: String,
        backend: TtsBackend,
        model: String
    ): String? {
        val explicit = (voiceArg ?: configuredVoice).trim()
        if (explicit.isNotBlank()) return explicit
        return ttsModels(backend).let { models ->
            (models.firstOrNull { it.id == model } ?: models.firstOrNull())?.defaultVoice
        }
    }

    /**
     * Host B must differ from host A to be worth having. With nothing
     * configured, the first voice in the model's list that is not A's is
     * chosen — deterministic for a given provider, unlike "the second voice",
     * which silently collides when A already *is* the second voice.
     */
    private fun resolveHostBVoice(
        voiceArg: String?,
        settings: Settings,
        backend: TtsBackend,
        model: String,
        voiceA: String
    ): String {
        val explicit = (voiceArg ?: settings.podcastHostBVoice).trim()
        if (explicit.isNotBlank()) return explicit
        val models = ttsModels(backend)
        val voices = (models.firstOrNull { it.id == model } ?: models.firstOrNull())?.voices.orEmpty()
        return voices.firstOrNull { it.id != voiceA }?.id ?: voiceA
    }

    private fun ttsModels(backend: TtsBackend): List<AudioModel> =
        runCatching { backend.listModels() }.getOrDefault(emptyList())
            .filter { it.category == ModelCategory.TTS }

    @Suppress("ReturnCount")
    private fun resolveDestination(baseName: String, extension: String, overwrite: Boolean): Resolved {
        val target = File(GotchaStorage.podcastsRoot(), "$baseName.$extension")
        val file = when (val r = resolver.resolveForWrite(target.absolutePath)) {
            is FileResolver.ResolveResult.PermissionNeeded -> return Resolved.Failed(r.result)
            is FileResolver.ResolveResult.Error -> return Resolved.Failed(ToolResult.error(r.message))
            is FileResolver.ResolveResult.Ok -> r.file
        }
        resolver.checkWritePermission(file)?.let { return Resolved.Failed(it) }
        if (file.exists() && !overwrite) {
            return Resolved.Failed(
                ToolResult.error(
                    "'${file.canonicalPath}' already exists. Pass overwrite=true to replace it, or choose a " +
                        "different output_name."
                )
            )
        }
        file.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                return Resolved.Failed(ToolResult.error("Could not create '${parent.canonicalPath}'."))
            }
        }
        return Resolved.Ok(file)
    }
}
