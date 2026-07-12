package com.gotcha.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioModelTest {

    @Test
    fun `task field takes precedence over name heuristics`() {
        // Name says TTS (kokoro) but the API's task field says STT — task wins.
        assertEquals(ModelCategory.STT, AudioModel.categorize("kokoro-82m", "automatic-speech-recognition"))
        // Name says STT (whisper) but task says TTS — task wins.
        assertEquals(ModelCategory.TTS, AudioModel.categorize("whisper-clone", "text-to-speech"))
    }

    @Test
    fun `known task field values are mapped`() {
        assertEquals(ModelCategory.TTS, AudioModel.categorize("m", "text-to-speech"))
        assertEquals(ModelCategory.TTS, AudioModel.categorize("m", "TTS"))
        assertEquals(ModelCategory.STT, AudioModel.categorize("m", "automatic-speech-recognition"))
        assertEquals(ModelCategory.STT, AudioModel.categorize("m", "speech-to-text"))
        assertEquals(ModelCategory.STT, AudioModel.categorize("m", "transcription"))
    }

    @Test
    fun `unknown task falls back to name heuristics`() {
        assertEquals(ModelCategory.STT, AudioModel.categorize("whisper-large-v3", "something-else"))
    }

    @Test
    fun `name heuristics categorize STT models`() {
        assertEquals(ModelCategory.STT, AudioModel.categorize("whisper-large-v3"))
        assertEquals(ModelCategory.STT, AudioModel.categorize("faster-STT-en"))
        assertEquals(ModelCategory.STT, AudioModel.categorize("nova-transcription"))
    }

    @Test
    fun `name heuristics categorize TTS models`() {
        assertEquals(ModelCategory.TTS, AudioModel.categorize("kokoro-82m"))
        assertEquals(ModelCategory.TTS, AudioModel.categorize("tts-1-hd"))
        assertEquals(ModelCategory.TTS, AudioModel.categorize("neural-speech-2"))
        assertEquals(ModelCategory.TTS, AudioModel.categorize("my-voice-model"))
    }

    @Test
    fun `unrecognized models are UNKNOWN`() {
        assertEquals(ModelCategory.UNKNOWN, AudioModel.categorize("gpt-4o"))
        assertEquals(ModelCategory.UNKNOWN, AudioModel.categorize("llama-3-8b"))
    }

    @Test
    fun `default voice falls back to af_heart when no voices are listed`() {
        val model = AudioModel(id = "kokoro-82m", category = ModelCategory.TTS)
        assertEquals("af_heart", model.defaultVoice)
    }

    @Test
    fun `default voice is the first listed voice`() {
        val model = AudioModel(
            id = "kokoro-82m",
            category = ModelCategory.TTS,
            voices = listOf("bm_lewis", "af_heart")
        )
        assertEquals("bm_lewis", model.defaultVoice)
    }
}
