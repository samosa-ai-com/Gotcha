package com.gotcha.audio

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AudioApiTest {

    private lateinit var server: MockWebServer
    private lateinit var audioApi: AudioApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        audioApi = AudioApi(
            baseUrl = server.url("/v1").toString(),
            apiKey = "custom-audio-key"
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `listAudioModels parses string array voices`() {
        val jsonResponse = """
            {
              "data": [
                {
                  "id": "kokoro-tts",
                  "task": "text-to-speech",
                  "voices": ["af_heart", "af_bella", "am_adam"]
                }
              ]
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(jsonResponse))

        val models = audioApi.listAudioModels()
        assertEquals(1, models.size)
        assertEquals("kokoro-tts", models[0].id)
        assertEquals(ModelCategory.TTS, models[0].category)
        val expectedVoices = listOf(
            VoiceInfo(id = "af_heart"),
            VoiceInfo(id = "af_bella"),
            VoiceInfo(id = "am_adam")
        )
        assertEquals(expectedVoices, models[0].voices)
        assertEquals("af_heart", models[0].defaultVoice)

        val recordedRequest = server.takeRequest()
        assertEquals("Bearer custom-audio-key", recordedRequest.getHeader("Authorization"))
    }

    @Test
    fun `listAudioModels parses object array voices with language and gender`() {
        val jsonResponse = """
            {
              "data": [
                {
                  "id": "speaches-ai/Kokoro-82M-v1.0-ONNX",
                  "task": "text-to-speech",
                  "language": ["multilingual"],
                  "voices": [
                    {"id": "af_heart", "name": "af_heart", "language": "en-us", "gender": "female"},
                    {"id": "am_adam", "name": "am_adam", "language": "en-us", "gender": "male"}
                  ]
                },
                {
                  "id": "faster-whisper-medium",
                  "task": "automatic-speech-recognition",
                  "language": ["en", "es", "fr"]
                }
              ]
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(jsonResponse))

        val models = audioApi.listAudioModels()
        assertEquals(2, models.size)

        val tts = models[0]
        assertEquals("speaches-ai/Kokoro-82M-v1.0-ONNX", tts.id)
        assertEquals(listOf("multilingual"), tts.languages)
        assertEquals(2, tts.voices.size)
        assertEquals("af_heart", tts.voices[0].id)
        assertEquals("en-us", tts.voices[0].language)
        assertEquals("female", tts.voices[0].gender)
        assertEquals("af_heart (en-us, female)", tts.voices[0].displayLabel)

        val stt = models[1]
        assertEquals("faster-whisper-medium", stt.id)
        assertEquals(ModelCategory.STT, stt.category)
        assertEquals(listOf("en", "es", "fr"), stt.languages)
    }
}
