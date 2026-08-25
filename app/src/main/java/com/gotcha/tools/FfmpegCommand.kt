package com.gotcha.tools

import java.util.Locale

/**
 * Builds the `ffmpeg` command lines that [MediaConvertTool] hands to Termux.
 *
 * Split out of the tool and free of Android APIs for the same reason
 * [MediaTimeSpec] is: this is where the bugs would be, and none of it needs a
 * device to test. Two parts carry real risk.
 *
 * First, quoting. [TermuxTool] runs its argument through `sh -c`, so every path
 * that reaches here is shell source. A file called `my song.mp3` would split into
 * two arguments unquoted, and one called `a';rm -rf ~;'.mp3` would do rather
 * worse. [shellQuote] is therefore applied to every interpolated value without
 * exception, and the format arguments themselves are drawn from a fixed table
 * rather than from anything the model wrote.
 *
 * Second, path translation. Gotcha addresses shared storage as
 * `/storage/emulated/0/…`, but inside Termux that path must be written
 * `/sdcard/…` — see the bundled `termux_filesystem` skill. Getting this wrong
 * produces a "No such file or directory" that looks like a missing file rather
 * than a wrong prefix.
 */
object FfmpegCommand {

    /** Android's canonical shared-storage root, as Gotcha's [FileResolver] reports it. */
    const val SHARED_STORAGE_ROOT = "/storage/emulated/0"

    /** The same tree as Termux must address it. */
    const val TERMUX_STORAGE_ROOT = "/sdcard"

    /** Default bitrate for the lossy targets that do not name their own. */
    private const val DEFAULT_BITRATE = "192k"

    /**
     * One conversion target: the codec arguments that produce it.
     *
     * @param codecArgs fixed arguments, never interpolated from model input.
     * @param bitrate default `-b:a` value, or null for the lossless targets where
     *   a bitrate is meaningless and ffmpeg would reject or ignore it.
     */
    data class Target(
        val extension: String,
        val codecArgs: List<String>,
        val bitrate: String?,
        val description: String
    ) {
        val isLossless: Boolean get() = bitrate == null
    }

    /**
     * Audio targets, keyed by output extension.
     *
     * MP3 is the point of this tool's existence: Android ships no MP3 encoder and
     * its muxer cannot write one, so `media_edit` can never produce it, while
     * Termux's ffmpeg carries libmp3lame. The rest come almost free once the
     * plumbing exists.
     */
    val AUDIO_TARGETS: Map<String, Target> = listOf(
        Target("mp3", listOf("-c:a", "libmp3lame"), DEFAULT_BITRATE, "MP3, the most widely playable lossy format"),
        Target("m4a", listOf("-c:a", "aac"), DEFAULT_BITRATE, "AAC in an MP4 container"),
        Target("aac", listOf("-c:a", "aac"), DEFAULT_BITRATE, "raw AAC stream"),
        Target("ogg", listOf("-c:a", "libvorbis"), DEFAULT_BITRATE, "Vorbis in an Ogg container"),
        Target("opus", listOf("-c:a", "libopus"), "128k", "Opus, the best quality per byte at low bitrates"),
        Target("wav", listOf("-c:a", "pcm_s16le"), null, "uncompressed PCM, large but lossless"),
        Target("flac", listOf("-c:a", "flac"), null, "FLAC, compressed but lossless")
    ).associateBy { it.extension }

    /** Bitrates the model may ask for: "192k", "320k", or a bare kbps number. */
    private val BITRATE_FORM = Regex("^(\\d{1,4})k?$", RegexOption.IGNORE_CASE)

    /**
     * Quotes [value] for `sh`.
     *
     * Single quotes suppress every form of shell expansion, so the only character
     * needing care is the single quote itself: the string is closed, an escaped
     * quote is emitted, and the string reopened. This is the standard POSIX idiom
     * and handles spaces, newlines, `$`, backticks, `;` and `&` alike.
     */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * Rewrites a Gotcha shared-storage path into the form Termux must use.
     * Anything outside shared storage is returned unchanged — [MediaConvertTool]
     * refuses those separately, with an explanation Termux itself cannot give.
     */
    fun termuxPath(canonicalPath: String): String = when {
        canonicalPath == SHARED_STORAGE_ROOT -> TERMUX_STORAGE_ROOT
        canonicalPath.startsWith("$SHARED_STORAGE_ROOT/") ->
            TERMUX_STORAGE_ROOT + canonicalPath.removePrefix(SHARED_STORAGE_ROOT)
        else -> canonicalPath
    }

    /** True when [canonicalPath] is somewhere Termux can reach at all. */
    fun isReachableFromTermux(canonicalPath: String): Boolean =
        canonicalPath == SHARED_STORAGE_ROOT || canonicalPath.startsWith("$SHARED_STORAGE_ROOT/")

    /**
     * Normalises a requested bitrate to ffmpeg's spelling, or null when it is not
     * a bitrate at all. Validated rather than passed through: it lands in a shell
     * command, and a quoted-but-nonsense value would fail deep inside ffmpeg with
     * a message the model cannot act on.
     */
    fun normaliseBitrate(bitrate: String?): String? {
        val trimmed = bitrate?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val match = BITRATE_FORM.matchEntire(trimmed) ?: return null
        val kbps = match.groupValues[1].toIntOrNull() ?: return null
        if (kbps !in MIN_BITRATE_KBPS..MAX_BITRATE_KBPS) return null
        return "${kbps}k"
    }

    const val MIN_BITRATE_KBPS = 8
    const val MAX_BITRATE_KBPS = 2000

    /**
     * The audio-conversion command.
     *
     * `-nostdin` because a headless ffmpeg that reads stdin blocks until Gotcha's
     * timeout and reports a hang rather than the prompt it is stuck on. `-y`
     * because the caller has already made the overwrite decision, and an
     * interactive "overwrite?" prompt would do the same. `-loglevel error` keeps
     * the result inside Termux's ~100KB output cap. `-vn` drops any video track,
     * without which ffmpeg tries to carry cover art into formats that cannot hold
     * it.
     */
    fun buildAudioConversion(
        inputCanonical: String,
        outputCanonical: String,
        target: Target,
        bitrate: String?
    ): String {
        val args = mutableListOf("ffmpeg", "-nostdin", "-loglevel", "error", "-y")
        args += listOf("-i", shellQuote(termuxPath(inputCanonical)))
        args += "-vn"
        args += target.codecArgs
        val effective = if (target.isLossless) null else bitrate ?: target.bitrate
        effective?.let { args += listOf("-b:a", it) }
        args += shellQuote(termuxPath(outputCanonical))
        return args.joinToString(" ")
    }

    /**
     * Probes for ffmpeg without running a conversion. `command -v` is a shell
     * builtin, so this costs nothing and works whether ffmpeg is a binary or a
     * shell function.
     */
    const val PROBE_COMMAND = "command -v ffmpeg"

    /** Lists the supported targets for an error message, longest-lived first. */
    fun describeAudioTargets(): String =
        AUDIO_TARGETS.values.joinToString(", ") { ".${it.extension}" }

    /** Looks up a target by output extension, case-insensitively. */
    fun audioTargetFor(extension: String): Target? =
        AUDIO_TARGETS[extension.lowercase(Locale.ROOT)]
}
