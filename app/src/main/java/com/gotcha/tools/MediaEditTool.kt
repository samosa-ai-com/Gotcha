package com.gotcha.tools

import android.content.Context
import java.io.File

/**
 * On-device audio and video editing on top of Media3 Transformer.
 *
 * Covers the operations that do not require re-typesetting a frame: reporting
 * what a file contains, trimming a window out of it, and splitting the audio
 * and video tracks apart. Media3 transmuxes rather than re-encodes when an edit
 * does not touch pixels, so these are stream copies — a trim of a long video
 * finishes in seconds and loses no quality.
 *
 * Audio and video are the same code path throughout: a Transformer export of an
 * .m4a is a video export with no video track. The operations that genuinely
 * cannot apply to one or the other ([removeAudio] of an audio-only file) are
 * refused against [MediaProbe] rather than silently writing an empty file.
 *
 * Everything reads and writes through [FileResolver], so shared-storage paths
 * raise the usual "All files access" permission result rather than throwing.
 * Only available to Operator (writes files).
 */
@Suppress("TooManyFunctions")
class MediaEditTool(private val context: Context) {

    private val resolver = FileResolver(context)

    companion object {
        /**
         * Per-file input cap. Far higher than [PdfTool.MAX_PDF_BYTES] because the
         * transmux path streams through the container rather than holding it in
         * memory; this guards against a runaway path, not against heap pressure.
         */
        const val MAX_MEDIA_BYTES = 2L * 1024 * 1024 * 1024

        val OPERATIONS = listOf("info", "trim", "extract_audio", "remove_audio")

        /**
         * Transformer's muxer writes an MP4 container whatever the output path is
         * named, so anything else would be a mislabelled file that some players
         * refuse and the user cannot diagnose. .m4a is the audio-only spelling of
         * the same container, which is why it is allowed alongside .mp4.
         */
        val OUTPUT_EXTENSIONS = listOf("mp4", "m4a")
    }

    /**
     * Single entry point; [operation] selects the branch. Arguments that do not
     * apply to the chosen operation are ignored rather than rejected, so a model
     * that over-supplies (a `start` on an info call) still succeeds.
     *
     * Suspending because a Transformer export is asynchronous and can outlast any
     * reasonable blocking call — see [MediaExport].
     */
    @Suppress("LongParameterList", "ReturnCount")
    suspend fun edit(
        operation: String,
        input: String? = null,
        output: String? = null,
        start: String? = null,
        end: String? = null,
        overwrite: Boolean = false
    ): ToolResult {
        val op = operation.trim().lowercase()
        if (op !in OPERATIONS) {
            return ToolResult.error(
                "Unknown operation '$operation'. Valid operations: ${OPERATIONS.joinToString(", ")}."
            )
        }
        val source = input ?: return missing("input", op)
        return try {
            when (op) {
                "info" -> info(source)
                "trim" -> trim(source, output ?: return missing("output", op), start, end, overwrite)
                "extract_audio" -> extractAudio(source, output ?: return missing("output", op), overwrite)
                else -> removeAudio(source, output ?: return missing("output", op), overwrite)
            }
        } catch (e: Exception) {
            ToolResult.error(describeFailure(e, source))
        }
    }

    // ---- operations ----

    private fun info(input: String): ToolResult {
        val file = resolveInput(input).unwrap { return it }
        val probe = MediaProbe.of(file)
        return ToolResult.ok(
            "'${file.canonicalPath}': ${probe.describe()}, ${resolver.formatSize(file.length())}."
        )
    }

    private suspend fun trim(
        input: String,
        output: String,
        start: String?,
        end: String?,
        overwrite: Boolean
    ): ToolResult {
        if (start == null && end == null) {
            return ToolResult.error(
                "trim needs at least one of 'start' and 'end' — with neither, the output would be a copy of the input."
            )
        }
        val file = resolveInput(input).unwrap { return it }
        val probe = MediaProbe.of(file)
        val target = resolveOutput(output, file, overwrite).unwrap { return it }
        val window = MediaTimeSpec.parseRange(start, end, probe.durationMs)
            .getOrElse { return ToolResult.error(it.message.orEmpty()) }

        val export = MediaExport(context).run(
            input = file,
            output = target,
            clip = window
        )
        export.failure()?.let { return it }

        val length = window.last - window.first
        return ToolResult.ok(
            "Trimmed ${MediaTimeSpec.format(window.first)}–${MediaTimeSpec.format(window.last)} " +
                "(${MediaTimeSpec.format(length)}) out of '${file.name}' into '${target.canonicalPath}' — " +
                "${resolver.formatSize(target.length())}.${export.qualityNote()}"
        )
    }

    private suspend fun extractAudio(input: String, output: String, overwrite: Boolean): ToolResult {
        val file = resolveInput(input).unwrap { return it }
        val probe = MediaProbe.of(file)
        if (!probe.hasAudio) {
            return ToolResult.error(
                "'${file.name}' has no audio track, so there is nothing to extract. Call operation='info' to see " +
                    "what a file contains before choosing an operation."
            )
        }
        val target = resolveOutput(output, file, overwrite).unwrap { return it }
        val export = MediaExport(context).run(input = file, output = target, removeVideo = true)
        export.failure()?.let { return it }
        return ToolResult.ok(
            "Extracted the audio track of '${file.name}' into '${target.canonicalPath}' — " +
                "${MediaTimeSpec.format(probe.durationMs)}, ${resolver.formatSize(target.length())}." +
                export.qualityNote()
        )
    }

    private suspend fun removeAudio(input: String, output: String, overwrite: Boolean): ToolResult {
        val file = resolveInput(input).unwrap { return it }
        val probe = MediaProbe.of(file)
        if (probe.isAudioOnly) {
            return ToolResult.error(
                "'${file.name}' is an audio-only file, so removing its audio would leave an empty file. " +
                    "Did you mean to trim it, or to delete it with delete_file?"
            )
        }
        if (!probe.hasAudio) {
            return ToolResult.error("'${file.name}' already has no audio track — there is nothing to remove.")
        }
        val target = resolveOutput(output, file, overwrite).unwrap { return it }
        val export = MediaExport(context).run(input = file, output = target, removeAudio = true)
        export.failure()?.let { return it }
        return ToolResult.ok(
            "Muted '${file.name}' into '${target.canonicalPath}' — video kept, audio track dropped, " +
                "${resolver.formatSize(target.length())}.${export.qualityNote()}"
        )
    }

    // ---- shared plumbing ----

    private fun resolveInput(path: String): Result<File> {
        val file = when (val resolved = resolver.resolveForRead(path)) {
            is FileResolver.ResolveResult.PermissionNeeded -> return Result.failure(ResultCarrier(resolved.result))
            is FileResolver.ResolveResult.Error -> return fail(resolved.message)
            is FileResolver.ResolveResult.Ok -> resolved.file
        }
        resolver.checkReadPermission(file)?.let { return Result.failure(ResultCarrier(it)) }
        if (!file.exists()) {
            return fail(
                "Media file '$path' does not exist (resolved: ${file.canonicalPath}). " +
                    "You may use list_files or glob to find it."
            )
        }
        if (!file.isFile) return fail("'$path' is not a regular file.")
        if (file.length() > MAX_MEDIA_BYTES) {
            return fail(
                "'${file.name}' is ${resolver.formatSize(file.length())}; " +
                    "the limit is ${resolver.formatSize(MAX_MEDIA_BYTES)}."
            )
        }
        return Result.success(file)
    }

    /**
     * Unlike [PdfTool], writing back over the input is refused outright rather
     * than routed through a temp file: Transformer reads the source while the
     * muxer writes the target, so an in-place edit truncates the file mid-read
     * and loses both copies.
     */
    private fun resolveOutput(path: String, input: File, overwrite: Boolean): Result<File> {
        val file = when (val resolved = resolver.resolveForWrite(path)) {
            is FileResolver.ResolveResult.PermissionNeeded -> return Result.failure(ResultCarrier(resolved.result))
            is FileResolver.ResolveResult.Error -> return fail(resolved.message)
            is FileResolver.ResolveResult.Ok -> resolved.file
        }
        resolver.checkWritePermission(file)?.let { return Result.failure(ResultCarrier(it)) }
        if (file.isDirectory) {
            return fail("Output '$path' is a directory. Give the full path of the file to write.")
        }
        if (file.canonicalPath == input.canonicalPath) {
            return fail(
                "Output cannot be the same file as the input — the encoder writes it while still reading from it, " +
                    "which would destroy both. Write to a new path such as " +
                    "'${input.nameWithoutExtension}-edited.${input.extension}' and tell the user where it went."
            )
        }
        val extension = file.extension.lowercase()
        if (extension !in OUTPUT_EXTENSIONS) {
            return fail(
                "Output '$path' ends in '.$extension', but this tool always writes an MP4 container — " +
                    "name it .mp4 for video or .m4a for audio-only. Converting to ${extension.ifEmpty { "that format" }} " +
                    "needs ffmpeg via run_termux_command."
            )
        }
        if (file.exists() && !overwrite) {
            return fail(
                "'${file.canonicalPath}' already exists. Pass overwrite=true to replace it, " +
                    "or choose a different output path."
            )
        }
        file.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                return fail("Could not create the output directory '${parent.canonicalPath}'.")
            }
        }
        return Result.success(file)
    }

    /**
     * The platform's own messages here are unusable by a model — a DRM-protected
     * file reports "Failed to instantiate extractor", which reads like a bug in
     * the app rather than a file it is never going to be allowed to open.
     */
    private fun describeFailure(e: Exception, path: String): String {
        val name = e.javaClass.simpleName
        val message = e.message.orEmpty()
        return when {
            message.contains("DRM", ignoreCase = true) || message.contains("crypto", ignoreCase = true) ->
                "'$path' is DRM-protected, so it cannot be read or edited. This applies to anything bought or " +
                    "streamed from a store — tell the user plainly rather than retrying."
            name == "IOException" || message.contains("extractor", ignoreCase = true) ->
                "Could not open '$path' as a media file. It may be corrupt, incomplete, or in a container Android " +
                    "cannot read. Call operation='info' to check, and note that exotic formats (MKV, OGG) may need " +
                    "ffmpeg via run_termux_command instead."
            else -> "Media operation failed: ${message.ifEmpty { name }}"
        }
    }

    private fun missing(param: String, operation: String) =
        ToolResult.error("Missing required parameter '$param' for operation '$operation'.")

    /** Carries an already-formed [ToolResult] (permission or validation) out of a helper. */
    private class ResultCarrier(val result: ToolResult) : Exception(result.message)

    private fun fail(message: String): Result<File> = Result.failure(ResultCarrier(ToolResult.error(message)))

    /** Unwraps a [ResultCarrier] failure back into the [ToolResult] the caller returns. */
    private inline fun Result<File>.unwrap(onFailure: (ToolResult) -> Nothing): File =
        fold(
            onSuccess = { it },
            onFailure = { e -> onFailure((e as? ResultCarrier)?.result ?: ToolResult.error(e.message.orEmpty())) }
        )
}
