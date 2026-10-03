package com.gotcha.audio

import com.gotcha.i18n.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechLanguageCheckTest {

    private val englishOnlyKokoro = AudioModel(
        id = "kokoro",
        category = ModelCategory.TTS,
        voices = listOf(VoiceInfo("af_heart"), VoiceInfo("am_adam"))
    )
    private val kokoroWithHindi = AudioModel(
        id = "kokoro",
        category = ModelCategory.TTS,
        voices = listOf(VoiceInfo("af_heart"), VoiceInfo("hf_alpha"))
    )

    private fun tts(
        model: AudioModel,
        language: Language,
        voice: String = "",
        provider: AudioProvider = AudioProvider.SAMOSA_AI
    ) = SpeechLanguageCheck.ttsWarning(provider, model.id, voice, listOf(model), language)

    // ---- Text to speech (issue #113's acceptance case) ----

    @Test
    fun `Hindi with an English-only TTS model warns and says what happens`() {
        val warning = tts(englishOnlyKokoro, Language.HINDI)
        requireNotNull(warning)
        assertTrue(warning, "no Hindi voice" in warning)
        assertTrue(warning, "read by its English voice af_heart" in warning)
    }

    @Test
    fun `a model with a voice in the language does not warn`() {
        assertNull(tts(kokoroWithHindi, Language.HINDI))
        assertNull(tts(englishOnlyKokoro, Language.ENGLISH))
    }

    @Test
    fun `a hand-picked voice in another language warns and points at the matching voice`() {
        val warning = tts(kokoroWithHindi, Language.HINDI, voice = "af_heart")
        requireNotNull(warning)
        assertTrue(warning, "af_heart speaks English" in warning)
        assertTrue(warning, "Clear the voice" in warning)
    }

    @Test
    fun `a hand-picked voice in the language does not warn`() {
        assertNull(tts(kokoroWithHindi, Language.HINDI, voice = "hf_alpha"))
    }

    @Test
    fun `server-sent voice languages win over the id`() {
        val model = AudioModel(
            id = "custom",
            category = ModelCategory.TTS,
            voices = listOf(VoiceInfo(id = "af_heart", language = "hi-IN"))
        )
        assertNull(tts(model, Language.HINDI))
    }

    @Test
    fun `model languages decide when no voice says what it speaks`() {
        val englishOnly = AudioModel("piper-en", ModelCategory.TTS, languages = listOf("en"))
        val multilingual = AudioModel("xtts", ModelCategory.TTS, languages = listOf("multilingual"))
        assertTrue(tts(englishOnly, Language.HINDI) != null)
        assertNull(tts(multilingual, Language.HINDI))
    }

    @Test
    fun `nothing known about the model means no warning`() {
        val opaque = AudioModel("opaque", ModelCategory.TTS, voices = listOf(VoiceInfo("Voice One")))
        assertNull(tts(opaque, Language.HINDI))
        assertNull(SpeechLanguageCheck.ttsSupports(opaque, Language.HINDI))
    }

    @Test
    fun `Android Built-in and an unknown model are not judged`() {
        assertNull(tts(englishOnlyKokoro, Language.HINDI, provider = AudioProvider.ANDROID))
        assertNull(
            SpeechLanguageCheck.ttsWarning(AudioProvider.API, "missing", "", listOf(englishOnlyKokoro), Language.HINDI)
        )
    }

    // ---- Speech to text ----

    private val whisperEnHi = AudioModel("whisper", ModelCategory.STT, languages = listOf("en", "hi"))

    private fun stt(override: String, language: Language, model: AudioModel = whisperEnHi) =
        SpeechLanguageCheck.sttWarnings(AudioProvider.SAMOSA_AI, model.id, override, listOf(model), language)

    @Test
    fun `an override that disagrees with the voice language warns`() {
        val warnings = stt("en", Language.HINDI)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0], "forced to English (en)" in warnings[0])
    }

    @Test
    fun `an override in the voice language, or none, does not warn`() {
        assertTrue(stt("hi", Language.HINDI).isEmpty())
        assertTrue(stt("", Language.HINDI).isEmpty())
    }

    @Test
    fun `an STT model that does not list the language warns`() {
        val warnings = stt("", Language.JAPANESE)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0], "does not list Japanese" in warnings[0])
    }

    @Test
    fun `a multilingual STT model or one with no languages does not warn`() {
        assertTrue(stt("", Language.JAPANESE, AudioModel("w", ModelCategory.STT, listOf("multilingual"))).isEmpty())
        assertTrue(stt("", Language.JAPANESE, AudioModel("w", ModelCategory.STT)).isEmpty())
    }

    @Test
    fun `Android speech-to-text ignores the override, so it is not judged`() {
        assertTrue(
            SpeechLanguageCheck.sttWarnings(AudioProvider.ANDROID, "", "en", emptyList(), Language.HINDI).isEmpty()
        )
    }

    // ---- Voice choice ----

    @Test
    fun `the default voice for a language reads Kokoro ids`() {
        assertEquals("hf_alpha", kokoroWithHindi.defaultVoiceFor(Language.HINDI))
        assertEquals("af_heart", kokoroWithHindi.defaultVoiceFor(Language.ENGLISH))
        assertEquals("af_heart", englishOnlyKokoro.defaultVoiceFor(Language.HINDI))
    }

    @Test
    fun `language codes match by primary subtag or name`() {
        assertTrue(Language.ENGLISH.matchesCode("en-us"))
        assertTrue(Language.PORTUGUESE.matchesCode("pt_BR"))
        assertTrue(Language.HINDI.matchesCode("Hindi"))
        assertFalse(Language.ENGLISH.matchesCode("es"))
        assertFalse(Language.ENGLISH.matchesCode(""))
    }
}
