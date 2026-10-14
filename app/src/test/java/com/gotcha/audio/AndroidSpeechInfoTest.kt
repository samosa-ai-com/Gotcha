package com.gotcha.audio

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.gotcha.i18n.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSpeechInfoTest {

    private fun voice(name: String, network: Boolean = false, notInstalled: Boolean = false) = Voice(
        name,
        Locale.forLanguageTag("ru-RU"),
        Voice.QUALITY_NORMAL,
        Voice.LATENCY_NORMAL,
        network,
        if (notInstalled) setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) else emptySet()
    )

    @Test
    fun `voices are labelled by where they run`() {
        assertEquals(SpeechAvailability.ON_DEVICE, AndroidSpeechInfo.voiceOf(voice("ru-local")).availability)
        assertEquals(SpeechAvailability.ONLINE, AndroidSpeechInfo.voiceOf(voice("ru-net", network = true)).availability)
        assertEquals(
            SpeechAvailability.DOWNLOADABLE,
            AndroidSpeechInfo.voiceOf(voice("ru-local", notInstalled = true)).availability
        )
    }

    @Test
    fun `the best voice decides text-to-speech availability`() {
        val online = AndroidVoice("a", SpeechAvailability.ONLINE)
        val local = AndroidVoice("b", SpeechAvailability.ON_DEVICE)
        assertEquals(
            SpeechAvailability.ON_DEVICE,
            AndroidSpeechInfo.ttsAvailability(listOf(online, local), TextToSpeech.LANG_AVAILABLE)
        )
        assertEquals(
            SpeechAvailability.ONLINE,
            AndroidSpeechInfo.ttsAvailability(listOf(online), TextToSpeech.LANG_AVAILABLE)
        )
    }

    @Test
    fun `an engine that lists no voices is judged by isLanguageAvailable`() {
        assertEquals(
            SpeechAvailability.DOWNLOADABLE,
            AndroidSpeechInfo.ttsAvailability(emptyList(), TextToSpeech.LANG_MISSING_DATA)
        )
        assertEquals(
            SpeechAvailability.UNSUPPORTED,
            AndroidSpeechInfo.ttsAvailability(emptyList(), TextToSpeech.LANG_NOT_SUPPORTED)
        )
        assertEquals(
            SpeechAvailability.AVAILABLE,
            AndroidSpeechInfo.ttsAvailability(emptyList(), TextToSpeech.LANG_COUNTRY_AVAILABLE)
        )
    }

    @Test
    fun `recognizer availability prefers the device, then the server, then a download`() {
        fun of(
            installed: List<String> = emptyList(),
            online: List<String> = emptyList(),
            down: List<String> = emptyList()
        ) = AndroidSpeechInfo.recognizerAvailability(Language.RUSSIAN, installed, online, down)

        assertEquals(SpeechAvailability.ON_DEVICE, of(installed = listOf("ru-RU"), online = listOf("ru-RU")))
        assertEquals(SpeechAvailability.ONLINE, of(online = listOf("ru-RU"), down = listOf("ru-RU")))
        assertEquals(SpeechAvailability.DOWNLOADABLE, of(down = listOf("ru-RU")))
        assertEquals(SpeechAvailability.UNSUPPORTED, of(online = listOf("en-US")))
        assertNull("empty lists say nothing", of())
    }

    @Test
    fun `the installed variant of a language is picked, its own tag first`() {
        assertEquals("en-US", AndroidSpeechInfo.pickInstalledTag(Language.ENGLISH, listOf("en-IN", "en-US")))
        assertEquals("en-IN", AndroidSpeechInfo.pickInstalledTag(Language.ENGLISH, listOf("de-DE", "en-IN")))
        assertEquals("pt_BR", AndroidSpeechInfo.pickInstalledTag(Language.PORTUGUESE, listOf("pt_BR")))
        assertNull(AndroidSpeechInfo.pickInstalledTag(Language.RUSSIAN, listOf("en-IN")))
    }

    @Test
    fun `an online-only recognizer doesn't count when on-device only is on`() {
        val online = AndroidSpeechStatus(recognizerAvailability = SpeechAvailability.ONLINE)
        assertEquals(true, online.recognizerSupports)
        assertEquals(false, online.copy(sttOnDeviceOnly = true).recognizerSupports)
    }

    @Test
    fun `a voice that isn't downloaded can't speak yet`() {
        assertEquals(false, AndroidSpeechStatus(ttsAvailability = SpeechAvailability.DOWNLOADABLE).ttsSupports)
        assertEquals(true, AndroidSpeechStatus(ttsAvailability = SpeechAvailability.ONLINE).ttsSupports)
        assertNull(AndroidSpeechStatus().ttsSupports)
    }
}
