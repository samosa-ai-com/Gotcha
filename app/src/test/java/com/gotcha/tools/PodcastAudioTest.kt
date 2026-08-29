package com.gotcha.tools

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The two pure halves of podcast synthesis: how a script is split for the TTS
 * API, and how the returned WAV bytes are measured. Both decide user-visible
 * outcomes — a bad split is audible at every join, and a misread header makes
 * the duration guard let a two-hour script through.
 */
class PodcastAudioTest {

    // ---- chunking ----

    @Test
    fun `a short script stays in one chunk`() {
        assertEquals(listOf("One sentence. And another."), PodcastAudio.chunk("One sentence. And another.", 100))
    }

    @Test
    fun `chunks break at sentence boundaries, never inside one`() {
        val text = "First sentence here. Second sentence here. Third sentence here."
        val chunks = PodcastAudio.chunk(text, 45)
        assertEquals(listOf("First sentence here. Second sentence here.", "Third sentence here."), chunks)
    }

    @Test
    fun `no chunk exceeds the limit`() {
        val text = (1..50).joinToString(" ") { "Sentence number $it is right here." }
        val chunks = PodcastAudio.chunk(text, 120)
        assertTrue(chunks.isNotEmpty())
        chunks.forEach { assertTrue("'${it.take(40)}…' is ${it.length} chars", it.length <= 120) }
    }

    @Test
    fun `nothing is lost or reordered by chunking`() {
        val text = (1..30).joinToString(" ") { "Sentence $it ends now." }
        val rejoined = PodcastAudio.chunk(text, 60).joinToString(" ")
        assertEquals(text, rejoined)
    }

    @Test
    fun `a single sentence longer than the limit is split at word boundaries`() {
        val words = (1..40).joinToString(" ") { "word$it" }
        val chunks = PodcastAudio.chunk(words, 50)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(it.length <= 50) }
        assertEquals(words, chunks.joinToString(" "))
    }

    @Test
    fun `an unbroken run longer than the limit is hard-cut rather than looping forever`() {
        val chunk = PodcastAudio.chunk("a".repeat(120), 50)
        assertTrue(chunk.size >= 3)
        chunk.forEach { assertTrue(it.length <= 50) }
    }

    @Test
    fun `blank input produces no chunks`() {
        assertEquals(emptyList<String>(), PodcastAudio.chunk("   \n  ", 100))
    }

    // ---- WAV parsing ----

    @Test
    fun `a canonical PCM WAV parses to its real duration`() {
        // 1 second of 24kHz mono 16-bit: 48000 data bytes.
        val wav = buildWav(sampleRate = 24_000, channels = 1, bitsPerSample = 16, dataBytes = 48_000)
        val info = PodcastAudio.wavInfo(wav)!!
        assertEquals(24_000, info.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(1000L, info.durationMs)
    }

    @Test
    fun `sample rate is read, not assumed`() {
        // The same byte count at 44.1kHz stereo is a much shorter clip; a
        // hardcoded 24kHz-mono assumption would report ~4x the real duration.
        val wav = buildWav(sampleRate = 44_100, channels = 2, bitsPerSample = 16, dataBytes = 176_400)
        assertEquals(1000L, PodcastAudio.wavInfo(wav)!!.durationMs)
    }

    @Test
    fun `chunks before fmt and data are walked over, not tripped over`() {
        val wav = buildWav(
            sampleRate = 16_000,
            channels = 1,
            bitsPerSample = 16,
            dataBytes = 32_000,
            leadingJunkChunk = true
        )
        assertEquals(1000L, PodcastAudio.wavInfo(wav)!!.durationMs)
    }

    @Test
    fun `a data chunk with a lying size falls back to the bytes that actually arrived`() {
        val wav = buildWav(
            sampleRate = 24_000,
            channels = 1,
            bitsPerSample = 16,
            dataBytes = 48_000,
            declaredDataSize = 0
        )
        assertEquals(1000L, PodcastAudio.wavInfo(wav)!!.durationMs)
    }

    @Test
    fun `non-WAV responses are rejected, not misparsed`() {
        assertNull(PodcastAudio.wavInfo("{\"error\": \"invalid model\"}".toByteArray()))
        assertNull(PodcastAudio.wavInfo("<html>502 Bad Gateway</html>".toByteArray()))
        assertNull(PodcastAudio.wavInfo(ByteArray(10)))
        assertNull(PodcastAudio.wavInfo(ByteArray(0)))
        // MP3 sync frame — a provider that ignored response_format.
        assertNull(PodcastAudio.wavInfo(byteArrayOf(0xFF.toByte(), 0xFB.toByte()) + ByteArray(100)))
    }

    // ---- appending silence ----

    @Test
    fun `appending silence extends the duration by exactly that much`() {
        val speech = buildWav(sampleRate = 24_000, channels = 1, bitsPerSample = 16, dataBytes = 48_000)
        val padded = PodcastAudio.appendSilence(speech, 300)!!
        val info = PodcastAudio.wavInfo(padded)!!
        assertEquals(1300L, info.durationMs)
        assertEquals(24_000, info.sampleRate)
        assertEquals(1, info.channels)
    }

    @Test
    fun `the pause inherits the speech's own rate and channel count`() {
        // 500ms of 44.1kHz stereo is 88200 bytes, not the 24kHz-mono 24000.
        val speech = buildWav(sampleRate = 44_100, channels = 2, bitsPerSample = 16, dataBytes = 176_400)
        val padded = PodcastAudio.appendSilence(speech, 500)!!
        assertEquals(speech.size + 88_200, padded.size)
        assertEquals(1500L, PodcastAudio.wavInfo(padded)!!.durationMs)
    }

    @Test
    fun `the original audio is preserved byte for byte ahead of the pause`() {
        val speech = buildWav(
            sampleRate = 24_000,
            channels = 1,
            bitsPerSample = 16,
            dataBytes = 4_800,
            dataFill = { (it % 251).toByte() }
        )
        val padded = PodcastAudio.appendSilence(speech, 100)!!
        val spoken = PodcastAudio.wavInfo(speech)!!.dataBytes.toInt()
        val dataStart = speech.size - spoken
        assertArrayEquals(
            speech.copyOfRange(dataStart, speech.size),
            padded.copyOfRange(dataStart, dataStart + spoken)
        )
    }

    @Test
    fun `the appended region is actual silence`() {
        val speech = buildWav(
            sampleRate = 24_000,
            channels = 1,
            bitsPerSample = 16,
            dataBytes = 4_800,
            dataFill = { 0x7F }
        )
        val padded = PodcastAudio.appendSilence(speech, 100)!!
        val tail = padded.copyOfRange(speech.size, padded.size)
        assertTrue("nothing was appended", tail.isNotEmpty())
        assertTrue("appended region is not silent", tail.all { it == 0.toByte() })
    }

    @Test
    fun `a zero or negative pause returns the audio untouched`() {
        val speech = buildWav(sampleRate = 24_000, channels = 1, bitsPerSample = 16, dataBytes = 4_800)
        assertArrayEquals(speech, PodcastAudio.appendSilence(speech, 0))
        assertArrayEquals(speech, PodcastAudio.appendSilence(speech, -100))
    }

    @Test
    fun `appending to something that is not a WAV reports failure instead of corrupting it`() {
        assertNull(PodcastAudio.appendSilence("{\"error\":\"nope\"}".toByteArray(), 300))
        assertNull(PodcastAudio.appendSilence(ByteArray(8), 300))
    }

    @Test
    fun `a padded WAV survives a second round of padding`() {
        // Proves the patched size fields are honest enough to parse again.
        val speech = buildWav(sampleRate = 24_000, channels = 1, bitsPerSample = 16, dataBytes = 24_000)
        val once = PodcastAudio.appendSilence(speech, 200)!!
        val twice = PodcastAudio.appendSilence(once, 300)!!
        assertEquals(1000L, PodcastAudio.wavInfo(twice)!!.durationMs)
    }

    @Test
    fun `padding walks past a leading chunk to find the real data chunk`() {
        val speech = buildWav(
            sampleRate = 24_000,
            channels = 1,
            bitsPerSample = 16,
            dataBytes = 24_000,
            leadingJunkChunk = true
        )
        assertEquals(700L, PodcastAudio.wavInfo(PodcastAudio.appendSilence(speech, 200)!!)!!.durationMs)
    }

    // ---- synthetic WAV builder ----

    @Suppress("LongParameterList")
    private fun buildWav(
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        dataBytes: Int,
        leadingJunkChunk: Boolean = false,
        declaredDataSize: Int = dataBytes,
        dataFill: ((Int) -> Byte)? = null
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) = repeat(4) { out.write((v shr (8 * it)) and 0xFF) }
        fun le16(v: Int) = repeat(2) { out.write((v shr (8 * it)) and 0xFF) }

        val byteRate = sampleRate * channels * bitsPerSample / 8
        ascii("RIFF")
        le32(0)
        ascii("WAVE")
        if (leadingJunkChunk) {
            ascii("LIST")
            le32(4)
            ascii("INFO")
        }
        ascii("fmt ")
        le32(16)
        le16(1)
        le16(channels)
        le32(sampleRate)
        le32(byteRate)
        le16(channels * bitsPerSample / 8)
        le16(bitsPerSample)
        ascii("data")
        le32(declaredDataSize)
        out.write(if (dataFill == null) ByteArray(dataBytes) else ByteArray(dataBytes) { dataFill(it) })
        return out.toByteArray()
    }
}
