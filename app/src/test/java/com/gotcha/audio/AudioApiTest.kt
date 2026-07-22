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
        assertEquals(listOf("af_heart", "af_bella", "am_adam"), models[0].voices)
        assertEquals("af_heart", models[0].defaultVoice)

        val recordedRequest = server.takeRequest()
        assertEquals("Bearer custom-audio-key", recordedRequest.getHeader("Authorization"))
    }

    @Test
    fun `listAudioModels parses object array voices`() {
        val jsonResponse = """
            {
              "data": [
                {
                  "id": "tts-model-1",
                  "task": "tts",
                  "voices": [
                    {"id": "voice_alpha", "name": "Alpha"},
                    {"id": "voice_beta", "name": "Beta"}
                  ]
                }
              ]
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(jsonResponse))

        val models = audioApi.listAudioModels()
        assertEquals(1, models.size)
        assertEquals(listOf("voice_alpha", "voice_beta"), models[0].voices)
    }
}
