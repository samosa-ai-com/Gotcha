package com.gotcha.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Command construction for `media_convert`. Pure JVM: [FfmpegCommand] touches no
 * Android API, and this is where the consequences of a mistake are worst — the
 * output is shell source handed to `sh -c` inside Termux, so a quoting hole is a
 * command-injection hole, and a wrong path prefix is an error message that
 * blames a missing file.
 */
class FfmpegCommandTest {

    // ---- shell quoting: the security-relevant half ----

    @Test
    fun `ordinary paths are wrapped in single quotes`() {
        assertEquals("'/sdcard/Music/song.mp3'", FfmpegCommand.shellQuote("/sdcard/Music/song.mp3"))
    }

    @Test
    fun `spaces survive quoting as one argument`() {
        assertEquals("'/sdcard/My Music/a song.m4a'", FfmpegCommand.shellQuote("/sdcard/My Music/a song.m4a"))
    }

    @Test
    fun `an embedded single quote cannot break out of the string`() {
        // The POSIX idiom: close, escaped quote, reopen.
        assertEquals("""'it'\''s.mp3'""", FfmpegCommand.shellQuote("it's.mp3"))
    }

    @Test
    fun `shell metacharacters are inert once quoted`() {
        listOf(
            "a;rm -rf ~;.mp3",
            "\$(reboot).mp3",
            "`id`.mp3",
            "a&&b.mp3",
            "a|b.mp3",
            "a\nb.mp3",
            "*.mp3",
            "~/secret.mp3"
        ).forEach { hostile ->
            val quoted = FfmpegCommand.shellQuote(hostile)
            assertTrue(hostile, quoted.startsWith("'") && quoted.endsWith("'"))
            // Nothing between the outer quotes may terminate the string except the
            // escaped form, which is the only way a bare quote is allowed to appear.
            val inner = quoted.substring(1, quoted.length - 1)
            assertFalse(hostile, inner.replace("""'\''""", "").contains("'"))
        }
    }

    @Test
    fun `a filename that is only quotes still round-trips`() {
        assertEquals("""''\'''\'''""", FfmpegCommand.shellQuote("''"))
    }

    // ---- path translation ----

    @Test
    fun `shared storage is rewritten to the sdcard prefix Termux needs`() {
        assertEquals("/sdcard/Music/a.m4a", FfmpegCommand.termuxPath("/storage/emulated/0/Music/a.m4a"))
        assertEquals("/sdcard", FfmpegCommand.termuxPath("/storage/emulated/0"))
    }

    @Test
    fun `a path merely starting with the same characters is not rewritten`() {
        // "/storage/emulated/0extra" is a different tree; a prefix match without
        // the separator would silently corrupt it.
        assertEquals("/storage/emulated/0extra/a.mp3", FfmpegCommand.termuxPath("/storage/emulated/0extra/a.mp3"))
    }

    @Test
    fun `paths outside shared storage are left alone`() {
        assertEquals("/data/data/com.gotcha/files/a.m4a", FfmpegCommand.termuxPath("/data/data/com.gotcha/files/a.m4a"))
    }

    @Test
    fun `reachability follows the same boundary as translation`() {
        assertTrue(FfmpegCommand.isReachableFromTermux("/storage/emulated/0/Download/a.m4a"))
        assertTrue(FfmpegCommand.isReachableFromTermux("/storage/emulated/0"))
        assertFalse(FfmpegCommand.isReachableFromTermux("/data/data/com.gotcha/files/a.m4a"))
        assertFalse(FfmpegCommand.isReachableFromTermux("/storage/emulated/0extra/a.mp3"))
    }

    // ---- bitrate validation ----

    @Test
    fun `bitrates are accepted with or without the k suffix`() {
        assertEquals("192k", FfmpegCommand.normaliseBitrate("192k"))
        assertEquals("192k", FfmpegCommand.normaliseBitrate("192"))
        assertEquals("320k", FfmpegCommand.normaliseBitrate(" 320K "))
    }

    @Test
    fun `a non-bitrate is rejected rather than quoted and passed on`() {
        listOf("high", "192kbps", "-192", "1e3", "192k; rm -rf ~", "").forEach {
            assertNull(it, FfmpegCommand.normaliseBitrate(it))
        }
        assertNull(FfmpegCommand.normaliseBitrate(null))
    }

    @Test
    fun `implausible bitrates are rejected at both ends`() {
        assertNull(FfmpegCommand.normaliseBitrate("0"))
        assertNull(FfmpegCommand.normaliseBitrate("9999"))
    }

    // ---- command assembly ----

    private fun audio(
        input: String = "/storage/emulated/0/Music/in.m4a",
        output: String = "/storage/emulated/0/Music/out.mp3",
        extension: String = "mp3",
        bitrate: String? = null
    ): String = FfmpegCommand.buildAudioConversion(
        input,
        output,
        FfmpegCommand.audioTargetFor(extension)!!,
        bitrate
    )

    @Test
    fun `the mp3 conversion names lame and a default bitrate`() {
        val command = audio()
        assertEquals(
            "ffmpeg -nostdin -loglevel error -y -i '/sdcard/Music/in.m4a' -vn " +
                "-c:a libmp3lame -b:a 192k '/sdcard/Music/out.mp3'",
            command
        )
    }

    @Test
    fun `an explicit bitrate overrides the target default`() {
        assertTrue(audio(bitrate = "320k").contains("-b:a 320k"))
    }

    @Test
    fun `lossless targets carry no bitrate even when one is supplied`() {
        listOf("wav", "flac").forEach { ext ->
            val command = audio(output = "/storage/emulated/0/Music/out.$ext", extension = ext, bitrate = "320k")
            assertFalse(ext, command.contains("-b:a"))
        }
    }

    @Test
    fun `every audio target produces a runnable command`() {
        FfmpegCommand.AUDIO_TARGETS.keys.forEach { ext ->
            val command = audio(output = "/storage/emulated/0/Music/out.$ext", extension = ext)
            assertTrue(ext, command.startsWith("ffmpeg -nostdin -loglevel error -y -i "))
            assertTrue(ext, command.endsWith("'/sdcard/Music/out.$ext'"))
            assertTrue(ext, command.contains("-c:a"))
        }
    }

    @Test
    fun `non-interactive flags are always present`() {
        // Without -nostdin and -y a headless ffmpeg blocks on a prompt until the
        // Termux timeout, and reports a hang rather than the question it asked.
        val command = audio()
        assertTrue(command.contains("-nostdin"))
        assertTrue(command.contains("-y"))
        assertTrue(command.contains("-loglevel error"))
    }

    @Test
    fun `video is dropped so cover art cannot block the muxer`() {
        assertTrue(audio().contains("-vn"))
    }

    @Test
    fun `hostile filenames stay inert in the assembled command`() {
        val command = audio(input = "/storage/emulated/0/Music/a';reboot;'.m4a")
        assertTrue(command.contains("""'/sdcard/Music/a'\'';reboot;'\''.m4a'"""))
    }

    @Test
    fun `target lookup is case insensitive and rejects the unknown`() {
        assertEquals("mp3", FfmpegCommand.audioTargetFor("MP3")?.extension)
        assertNull(FfmpegCommand.audioTargetFor("xyz"))
    }

    @Test
    fun `the probe does not depend on ffmpeg being present`() {
        assertEquals("command -v ffmpeg", FfmpegCommand.PROBE_COMMAND)
    }
}
