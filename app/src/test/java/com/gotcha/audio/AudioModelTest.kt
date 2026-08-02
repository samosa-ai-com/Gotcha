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
    fun `provider_type field is honored for audio hints`() {
        assertEquals(ModelCategory.TTS, AudioModel.categorize("m", task = null, providerType = "tts"))
        assertEquals(ModelCategory.STT, AudioModel.categorize("m", task = null, providerType = "STT"))
        assertEquals(ModelCategory.LLM, AudioModel.categorize("m", task = null, providerType = "llm"))
    }

    @Test
    fun `task field is honored for LLM hints`() {
        assertEquals(ModelCategory.LLM, AudioModel.categorize("m", task = "text-generation"))
        assertEquals(ModelCategory.LLM, AudioModel.categorize("m", task = "chat-completion"))
        assertEquals(ModelCategory.LLM, AudioModel.categorize("m", task = "chat"))
        assertEquals(ModelCategory.LLM, AudioModel.categorize("m", task = "language-model"))
    }

    @Test
    fun `provider_type overrides name heuristics`() {
        // Name says TTS (kokoro) but provider_type says LLM — provider_type wins.
        assertEquals(ModelCategory.LLM, AudioModel.categorize("kokoro-82m", task = null, providerType = "llm"))
        // Name says STT (whisper) but provider_type says TTS — provider_type wins.
        assertEquals(ModelCategory.TTS, AudioModel.categorize("whisper-1", task = null, providerType = "tts"))
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
            voices = listOf(VoiceInfo(id = "bm_lewis"), VoiceInfo(id = "af_heart"))
        )
        assertEquals("bm_lewis", model.defaultVoice)
    }

    @Test
    fun `AudioProvider has the expected dropdown labels`() {
        assertEquals("Android Built-in", AudioProvider.ANDROID.label)
        assertEquals("Samosa AI", AudioProvider.SAMOSA_AI.label)
        assertEquals("External API", AudioProvider.API.label)
        assertEquals("None", AudioProvider.NONE.label)
    }

    @Test
    fun `AudioProvider dropdown order is Android then Samosa then API then None`() {
        val labels = AudioProvider.entries.map { it.label }
        assertEquals(
            listOf("Android Built-in", "Samosa AI", "External API", "None"),
            labels
        )
    }

    @Test
    fun `isApiBased is true for Samosa AI and External API`() {
        assertEquals(true, AudioProvider.SAMOSA_AI.isApiBased())
        assertEquals(true, AudioProvider.API.isApiBased())
    }

    @Test
    fun `isApiBased is false for Android and None`() {
        assertEquals(false, AudioProvider.ANDROID.isApiBased())
        assertEquals(false, AudioProvider.NONE.isApiBased())
    }
}
