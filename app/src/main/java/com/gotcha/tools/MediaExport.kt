package com.gotcha.tools

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * Runs one Media3 [Transformer] export and suspends until it finishes.
 *
 * Bridging Transformer into a `suspend` function is most of the work here.
 * Transformer is callback-driven and must be built, started and cancelled on a
 * thread with a [android.os.Looper], while [ToolExecutor] dispatches every tool
 * on `Dispatchers.IO`, which has none. So the export is hopped onto the main
 * dispatcher and its listener resumed through [suspendCancellableCoroutine].
 *
 * The whole call is bounded by [TIMEOUT_MS]. A transmux finishes in seconds, but
 * a re-encode of a long 4K video does not, and an export that never calls back
 * would otherwise wedge the agent loop with no way out.
 */
@OptIn(UnstableApi::class)
class MediaExport(private val context: Context) {

    companion object {
        /**
         * Ceiling on a single export. Generous enough for a real re-encode on a
         * slow device, short enough that a wedged codec surfaces as an error the
         * model can report rather than a hang the user has to force-quit.
         */
        const val TIMEOUT_MS = 10L * 60 * 1000
    }

    /**
     * Outcome of an export, in the two shapes the caller cares about: whether it
     * failed, and whether the codecs ran.
     *
     * @param videoEncoder name of the video encoder used, or null when the video
     *   track was stream-copied (or absent). Same for [audioEncoder].
     */
    data class Outcome(
        val error: ToolResult?,
        val videoEncoder: String?,
        val audioEncoder: String?
    ) {
        fun failure(): ToolResult? = error

        /** True when neither track went through a codec — a pure container rewrite. */
        val wasTransmuxed: Boolean get() = videoEncoder == null && audioEncoder == null

        /**
         * Appended to a success message. Whether the edit was lossless is the thing
         * a user most often wants to know afterwards and can never tell by looking,
         * so it is stated in the result rather than left implicit.
         */
        fun qualityNote(): String = if (wasTransmuxed) {
            " The streams were copied rather than re-encoded, so there is no quality loss."
        } else {
            val used = listOfNotNull(videoEncoder, audioEncoder).joinToString(", ")
            " NOTE: this was re-encoded ($used) rather than copied, so it lost a little quality. " +
                "Re-encoding the same file repeatedly compounds that."
        }
    }

    /**
     * Exports [input] to [output], optionally clipped to [clip] and with either
     * track dropped. With no clip and no removals this is a plain container
     * rewrite, which Transformer still performs as a stream copy.
     */
    @Suppress("LongParameterList")
    suspend fun run(
        input: File,
        output: File,
        clip: LongRange? = null,
        removeAudio: Boolean = false,
        removeVideo: Boolean = false,
        videoEffects: List<Effect> = emptyList(),
        audioProcessors: List<AudioProcessor> = emptyList(),
        videoBitrateBps: Int? = null
    ): Outcome {
        val outcome = withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                export(
                    input = input,
                    output = output,
                    clip = clip,
                    removeAudio = removeAudio,
                    removeVideo = removeVideo,
                    videoEffects = videoEffects,
                    audioProcessors = audioProcessors,
                    videoBitrateBps = videoBitrateBps
                )
            }
        }
        return outcome ?: timedOut(output)
    }

    /**
     * A half-written output is worse than none: it looks like a successful edit
     * to anything that only checks for existence, so it is deleted before the
     * failure is reported.
     */
    private fun timedOut(output: File): Outcome {
        runCatching { if (output.exists()) output.delete() }
        return Outcome(
            error = ToolResult.error(
                "The export did not finish within ${TIMEOUT_MS / 60_000} minutes and was abandoned. " +
                    "This usually means the file is very long or very high-resolution. Trim it to a shorter " +
                    "window first, and tell the user why."
            ),
            videoEncoder = null,
            audioEncoder = null
        )
    }

    @Suppress("LongParameterList")
    private suspend fun export(
        input: File,
        output: File,
        clip: LongRange?,
        removeAudio: Boolean,
        removeVideo: Boolean,
        videoEffects: List<Effect>,
        audioProcessors: List<AudioProcessor>,
        videoBitrateBps: Int?
    ): Outcome = suspendCancellableCoroutine { continuation ->
        val edited = EditedMediaItem.Builder(mediaItem(input, clip))
            .setRemoveAudio(removeAudio)
            .setRemoveVideo(removeVideo)
            .setEffects(Effects(ImmutableList.copyOf(audioProcessors), ImmutableList.copyOf(videoEffects)))
            .build()

        val transformer = transformerBuilder(output, videoBitrateBps, continuation)
            .build()

        continuation.invokeOnCancellation {
            // cancel() must run on the thread that built the Transformer.
            runCatching { transformer.cancel() }
        }
        transformer.start(edited, output.absolutePath)
    }

    /**
     * Joins [inputs] end to end into [output].
     *
     * A concatenation is the one operation that cannot be expressed as an edit of
     * a single item, so it builds a [Composition] instead. Transformer re-encodes
     * whatever it must to make mismatched sources line up, which is why
     * [MediaEditTool] warns about the cost before calling this.
     */
    suspend fun concat(inputs: List<File>, output: File): Outcome {
        val outcome = withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.Main) { concatOnLooper(inputs, output) }
        }
        return outcome ?: timedOut(output)
    }

    private suspend fun concatOnLooper(
        inputs: List<File>,
        output: File
    ): Outcome = suspendCancellableCoroutine { continuation ->
        val sequence = EditedMediaItemSequence(
            inputs.map { EditedMediaItem.Builder(mediaItem(it, clip = null)).build() }
        )
        val composition = Composition.Builder(ImmutableList.of(sequence)).build()

        val transformer = transformerBuilder(output, videoBitrateBps = null, continuation = continuation)
            .build()

        continuation.invokeOnCancellation { runCatching { transformer.cancel() } }
        transformer.start(composition, output.absolutePath)
    }

    private fun mediaItem(file: File, clip: LongRange?): MediaItem =
        MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .apply {
                clip?.let {
                    setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(it.first)
                            .setEndPositionMs(it.last)
                            .build()
                    )
                }
            }
            .build()

    private fun transformerBuilder(
        output: File,
        videoBitrateBps: Int?,
        continuation: kotlinx.coroutines.CancellableContinuation<Outcome>
    ): Transformer.Builder {
        val builder = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    if (continuation.isActive) {
                        continuation.resume(
                            Outcome(
                                error = null,
                                videoEncoder = result.videoEncoderName,
                                audioEncoder = result.audioEncoderName
                            )
                        )
                    }
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException
                ) {
                    runCatching { if (output.exists()) output.delete() }
                    if (continuation.isActive) {
                        continuation.resume(
                            Outcome(
                                error = ToolResult.error(describeExportError(exception, output)),
                                videoEncoder = null,
                                audioEncoder = null
                            )
                        )
                    }
                }
            })
        videoBitrateBps?.let { bitrate ->
            builder.setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                    .build()
            )
        }
        return builder
    }

    /**
     * Turns an [ExportException] into something a model can act on. The raw
     * messages are codec-level ("Decoder init failed: OMX.…") and read as an app
     * bug rather than as a property of the file.
     */
    private fun describeExportError(exception: ExportException, input: File): String {
        val message = exception.message.orEmpty()
        val cause = exception.cause?.message.orEmpty()
        val combined = "$message $cause"
        return when {
            combined.contains("DRM", ignoreCase = true) || combined.contains("crypto", ignoreCase = true) ->
                "'${input.name}' is DRM-protected, so it cannot be edited. Tell the user plainly; there is no " +
                    "retry that will work."
            exception.errorCode == ExportException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "'${input.name}' could not be read while exporting — it may have been moved or deleted mid-edit."
            exception.errorCode == ExportException.ERROR_CODE_IO_NO_PERMISSION ->
                "Permission was refused while reading or writing during the export of '${input.name}'."
            combined.contains("Decoder init", ignoreCase = true) ||
                combined.contains("Encoder init", ignoreCase = true) ->
                "This device's codecs could not handle '${input.name}' (${combined.trim()}). The format is " +
                    "readable but not editable here; ffmpeg via run_termux_command is the fallback."
            else -> "The export failed: ${combined.trim().ifEmpty { "no detail reported" }}."
        }
    }
}
