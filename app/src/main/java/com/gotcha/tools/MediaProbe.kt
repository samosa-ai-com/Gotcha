package com.gotcha.tools

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.util.Locale

/**
 * What a media file actually contains, read without decoding a single frame.
 *
 * Every [MediaEditTool] operation needs this before it can decide anything:
 * `trim` needs the duration to clamp against, `remove_audio` needs to know an
 * audio track exists, and the model needs the whole picture to choose an
 * operation at all. Reading it is cheap — [MediaExtractor] parses the container
 * header and [MediaMetadataRetriever] the metadata atoms, neither of which
 * starts a codec.
 *
 * [hasVideo] and [hasAudio] are the load-bearing fields. An .mp4 with no audio
 * track and an .m4a are both legitimate inputs, and the operations that make no
 * sense for them have to be refused with a real explanation rather than writing
 * an empty file.
 */
data class MediaProbe(
    val durationMs: Long,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val videoMime: String?,
    val audioMime: String?,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val frameRate: Int,
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitrateBps: Long
) {

    /** True when the container holds audio and nothing else — an .m4a, .mp3, .wav. */
    val isAudioOnly: Boolean get() = hasAudio && !hasVideo

    /**
     * Display dimensions, i.e. after the container's rotation metadata is applied.
     * A portrait phone video is stored as 1920x1080 with rotation=90, and reporting
     * the stored numbers makes the model think it is landscape.
     */
    val displayWidth: Int get() = if (rotationDegrees % 180 == 90) height else width
    val displayHeight: Int get() = if (rotationDegrees % 180 == 90) width else height

    /** One-line summary for a tool result, written for the model to reason over. */
    @Suppress("ComplexMethod")
    fun describe(): String {
        val parts = mutableListOf<String>()
        parts += if (durationMs > 0) MediaTimeSpec.format(durationMs) else "unknown duration"
        if (hasVideo) {
            val video = buildString {
                append("video ${displayWidth}x$displayHeight")
                if (frameRate > 0) append(" @ ${frameRate}fps")
                videoMime?.let { append(" (${it.removePrefix("video/")})") }
                if (rotationDegrees != 0) append(", rotated $rotationDegrees°")
            }
            parts += video
        } else {
            parts += "no video track"
        }
        if (hasAudio) {
            val audio = buildString {
                append("audio")
                if (channelCount > 0) append(" ${channelDescription()}")
                if (sampleRateHz > 0) append(" ${"%.1f".format(Locale.ROOT, sampleRateHz / 1000.0)}kHz")
                audioMime?.let { append(" (${it.removePrefix("audio/")})") }
            }
            parts += audio
        } else {
            parts += "no audio track"
        }
        if (bitrateBps > 0) parts += "${bitrateBps / 1000} kbps"
        return parts.joinToString(", ")
    }

    private fun channelDescription(): String = when (channelCount) {
        1 -> "mono"
        2 -> "stereo"
        else -> "$channelCount-channel"
    }

    companion object {

        /**
         * Reads [file]'s container header. Throws whatever the platform throws for
         * an unreadable file — [MediaEditTool] translates those into model-facing
         * messages, since the raw exceptions are unhelpful ("Failed to instantiate
         * extractor" for a DRM file, for instance).
         */
        fun of(file: File): MediaProbe {
            val extractor = MediaExtractor()
            var hasVideo = false
            var hasAudio = false
            var videoMime: String? = null
            var audioMime: String? = null
            var width = 0
            var height = 0
            var frameRate = 0
            var sampleRate = 0
            var channels = 0
            try {
                extractor.setDataSource(file.absolutePath)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    when {
                        mime.startsWith("video/") && !hasVideo -> {
                            hasVideo = true
                            videoMime = mime
                            width = format.intOrZero(MediaFormat.KEY_WIDTH)
                            height = format.intOrZero(MediaFormat.KEY_HEIGHT)
                            frameRate = format.intOrZero(MediaFormat.KEY_FRAME_RATE)
                        }
                        mime.startsWith("audio/") && !hasAudio -> {
                            hasAudio = true
                            audioMime = mime
                            sampleRate = format.intOrZero(MediaFormat.KEY_SAMPLE_RATE)
                            channels = format.intOrZero(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                }
            } finally {
                extractor.release()
            }

            val retriever = MediaMetadataRetriever()
            var durationMs = 0L
            var rotation = 0
            var bitrate = 0L
            try {
                retriever.setDataSource(file.absolutePath)
                durationMs = retriever.longOrZero(MediaMetadataRetriever.METADATA_KEY_DURATION)
                rotation = retriever.longOrZero(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt()
                bitrate = retriever.longOrZero(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            } finally {
                runCatching { retriever.release() }
            }

            return MediaProbe(
                durationMs = durationMs,
                hasVideo = hasVideo,
                hasAudio = hasAudio,
                videoMime = videoMime,
                audioMime = audioMime,
                width = width,
                height = height,
                rotationDegrees = Math.floorMod(rotation, 360),
                frameRate = frameRate,
                sampleRateHz = sampleRate,
                channelCount = channels,
                bitrateBps = bitrate
            )
        }

        /** [MediaFormat] throws rather than defaulting when a key is absent. */
        private fun MediaFormat.intOrZero(key: String): Int =
            if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0

        private fun MediaMetadataRetriever.longOrZero(key: Int): Long =
            extractMetadata(key)?.toLongOrNull() ?: 0L
    }
}
