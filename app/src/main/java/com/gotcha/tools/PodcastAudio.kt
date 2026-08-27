package com.gotcha.tools

import java.io.File

/**
 * Pure helpers for podcast synthesis: script chunking and WAV header parsing.
 *
 * Kept free of Android types so the logic that decides how a script is split
 * and how long the synthesized audio actually is can be tested on the JVM.
 * [PodcastTool] owns everything with side effects.
 */
object PodcastAudio {

    /** RIFF header (12) + minimal fmt chunk (24) + data chunk header (8). */
    private const val MIN_WAV_BYTES = 44
    private const val RIFF_HEADER_BYTES = 12
    private const val CHUNK_HEADER_BYTES = 8
    private const val FMT_CHUNK_MIN_BYTES = 16
    private const val MILLIS_PER_SECOND = 1000L

    private val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+")

    /**
     * Splits [text] into chunks of at most [maxChars], breaking at sentence
     * boundaries so the TTS engine never receives a half sentence — a cut
     * mid-sentence is audible as a dropped word at every segment join.
     *
     * A single sentence longer than [maxChars] (rare, but a model can produce
     * one) is split at the last word boundary that fits, and as a hard cut only
     * when even one word does not fit.
     */
    fun chunk(text: String, maxChars: Int): List<String> {
        require(maxChars > 0) { "maxChars must be positive" }
        val sentences = text.trim().split(SENTENCE_BREAK).filter { it.isNotBlank() }
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            for (piece in splitOversize(sentence, maxChars)) {
                if (current.isNotEmpty() && current.length + 1 + piece.length > maxChars) {
                    chunks.add(current.toString())
                    current.setLength(0)
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(piece)
            }
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }

    /** [sentence] as-is when it fits, otherwise word-boundary pieces of at most [maxChars]. */
    private fun splitOversize(sentence: String, maxChars: Int): List<String> {
        if (sentence.length <= maxChars) return listOf(sentence)
        val pieces = mutableListOf<String>()
        var rest = sentence
        while (rest.length > maxChars) {
            val cut = rest.lastIndexOf(' ', maxChars).takeIf { it > 0 } ?: maxChars
            pieces.add(rest.take(cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) pieces.add(rest)
        return pieces
    }

    /**
     * What a WAV header says about its audio. Parsed from the file itself
     * rather than assumed, because TTS providers return whatever sample rate
     * they like (16, 24 or 44.1 kHz have all been seen) and a guessed rate
     * would make every duration estimate off by that ratio.
     */
    data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val byteRate: Int,
        val dataBytes: Long
    ) {
        val durationMs: Long get() = if (byteRate > 0) dataBytes * MILLIS_PER_SECOND / byteRate else 0L
    }

    /**
     * Parses [bytes] as a RIFF/WAVE file by walking its chunks — the fmt and
     * data chunks are not at fixed offsets in every real-world WAV (a LIST or
     * fact chunk can precede them). Returns null for anything that is not a
     * well-formed WAV, which is how [PodcastTool] detects a TTS endpoint that
     * answered with JSON, MP3 or an HTML error page instead of the requested
     * WAV.
     */
    @Suppress("ReturnCount")
    fun wavInfo(bytes: ByteArray): WavInfo? {
        if (bytes.size < MIN_WAV_BYTES) return null
        if (!bytes.ascii(0, "RIFF") || !bytes.ascii(8, "WAVE")) return null

        var offset = RIFF_HEADER_BYTES
        var info: WavInfo? = null
        var dataBytes = -1L
        while (offset + CHUNK_HEADER_BYTES <= bytes.size) {
            val chunkSize = bytes.leInt(offset + 4).toLong() and 0xFFFFFFFFL
            when {
                bytes.ascii(offset, "fmt ") && chunkSize >= FMT_CHUNK_MIN_BYTES &&
                    offset + CHUNK_HEADER_BYTES + FMT_CHUNK_MIN_BYTES <= bytes.size -> {
                    val body = offset + CHUNK_HEADER_BYTES
                    info = WavInfo(
                        sampleRate = bytes.leInt(body + 4),
                        channels = bytes.leShort(body + 2),
                        bitsPerSample = bytes.leShort(body + 14),
                        byteRate = bytes.leInt(body + 8),
                        dataBytes = 0L
                    )
                }
                bytes.ascii(offset, "data") -> {
                    // A streamed WAV may declare a bogus size (0 or 0xFFFFFFFF);
                    // what actually arrived is the honest answer.
                    val remaining = (bytes.size - offset - CHUNK_HEADER_BYTES).toLong()
                    dataBytes = if (chunkSize in 1..remaining) chunkSize else remaining
                }
            }
            // Chunks are word-aligned: an odd-sized chunk is padded by one byte.
            offset += CHUNK_HEADER_BYTES + chunkSize.toInt() + (chunkSize % 2).toInt()
            if (offset < 0) return null // overflow from a hostile size field
        }
        val fmt = info ?: return null
        if (dataBytes < 0 || fmt.byteRate <= 0 || fmt.sampleRate <= 0) return null
        return fmt.copy(dataBytes = dataBytes)
    }

    /** [wavInfo] for a file already on disk. */
    fun wavInfo(file: File): WavInfo? = runCatching { wavInfo(file.readBytes()) }.getOrNull()

    private const val PCM_FORMAT_CODE = 1
    private const val PCM16_BYTES_PER_SAMPLE = 2
    private const val FMT_CHUNK_BYTES = 16
    private const val BITS_PER_BYTE = 8

    /**
     * A canonical PCM16 WAV of digital silence, used as the breathing gap
     * between dialogue turns. Generated at the [sampleRate]/[channels] of the
     * speech it sits between rather than at some fixed rate, so the joiner
     * sees a homogeneous sequence instead of resampling a mismatched clip at
     * every turn boundary.
     */
    fun silenceWav(durationMs: Long, sampleRate: Int, channels: Int): ByteArray {
        require(durationMs >= 0 && sampleRate > 0 && channels > 0) { "invalid silence parameters" }
        val frameBytes = channels * PCM16_BYTES_PER_SAMPLE
        val dataBytes = (durationMs * sampleRate / MILLIS_PER_SECOND).toInt() * frameBytes
        val out = java.io.ByteArrayOutputStream(MIN_WAV_BYTES + dataBytes)
        fun ascii(s: String) = s.forEach { out.write(it.code) }
        fun le32(v: Int) = repeat(4) { out.write((v shr (BITS_PER_BYTE * it)) and 0xFF) }
        fun le16(v: Int) = repeat(2) { out.write((v shr (BITS_PER_BYTE * it)) and 0xFF) }

        ascii("RIFF")
        le32(MIN_WAV_BYTES - CHUNK_HEADER_BYTES + dataBytes)
        ascii("WAVE")
        ascii("fmt ")
        le32(FMT_CHUNK_BYTES)
        le16(PCM_FORMAT_CODE)
        le16(channels)
        le32(sampleRate)
        le32(sampleRate * frameBytes)
        le16(frameBytes)
        le16(PCM16_BYTES_PER_SAMPLE * BITS_PER_BYTE)
        ascii("data")
        le32(dataBytes)
        out.write(ByteArray(dataBytes))
        return out.toByteArray()
    }

    private fun ByteArray.ascii(offset: Int, expected: String): Boolean {
        if (offset + expected.length > size) return false
        return expected.withIndex().all { (i, c) -> this[offset + i] == c.code.toByte() }
    }

    private fun ByteArray.leShort(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.leInt(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
}
