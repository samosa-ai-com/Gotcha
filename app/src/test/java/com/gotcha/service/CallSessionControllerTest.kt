package com.gotcha.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.gotcha.agent.MessageKind
import com.gotcha.audio.AudioProvider
import com.gotcha.audio.SttEngine
import com.gotcha.audio.TtsEngine
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.testsupport.FakeAndroidKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallSessionControllerTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var sttEngine: SttEngine
    private lateinit var ttsEngine: TtsEngine
    private lateinit var controller: CallSessionController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        shadowOf(application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        settingsRepository = SettingsRepository(application)
        sttEngine = SttEngine(application)
        ttsEngine = TtsEngine(application)
        controller = CallSessionController(
            appContext = application,
            scope = scope,
            settingsRepository = settingsRepository,
            sttEngine = sttEngine,
            ttsEngine = ttsEngine
        )
    }

    @Test
    fun `startCall succeeds validation with Samosa AI audio provider when token is present`() {
        settingsRepository.save(
            Settings(
                provider = LlmProvider.SAMOSA_AI,
                samosaSessionToken = "samosa-jwt-token",
                sttProvider = AudioProvider.SAMOSA_AI,
                sttApiModel = "whisper-1",
                ttsProvider = AudioProvider.SAMOSA_AI,
                ttsApiModel = "tts-1"
            )
        )
        var errorMessage: String? = null
        controller.onError = { errorMessage = it }

        val started = controller.startCall()
        assertTrue("Expected startCall to succeed but got error: $errorMessage", started)
        assertTrue(errorMessage == null)
    }

    @Test
    fun `startCall fails validation with Samosa AI audio provider when token is missing`() {
        settingsRepository.save(
            Settings(
                provider = LlmProvider.OPENAI_COMPATIBLE,
                baseUrl = "https://api.openai.com/v1",
                apiKey = "llm-key",
                samosaSessionToken = "",
                sttProvider = AudioProvider.SAMOSA_AI,
                sttApiModel = "whisper-1",
                ttsProvider = AudioProvider.SAMOSA_AI,
                ttsApiModel = "tts-1"
            )
        )
        var errorMessage: String? = null
        controller.onError = { errorMessage = it }

        val started = controller.startCall()
        assertFalse(started)
        assertTrue(
            "Expected STT error message but got: $errorMessage",
            errorMessage?.contains("Speech-to-text Samosa AI is not configured") == true
        )
    }

    @Test
    fun `startCall succeeds validation with API audio provider when valid URL is present`() {
        settingsRepository.save(
            Settings(
                provider = LlmProvider.OPENAI_COMPATIBLE,
                baseUrl = "https://api.openai.com/v1",
                apiKey = "llm-key",
                sttProvider = AudioProvider.API,
                sttApiBaseUrl = "https://api.openai.com/v1",
                sttApiModel = "whisper-1",
                ttsProvider = AudioProvider.API,
                ttsApiBaseUrl = "https://api.openai.com/v1",
                ttsApiModel = "tts-1"
            )
        )
        var errorMessage: String? = null
        controller.onError = { errorMessage = it }

        val started = controller.startCall()
        assertTrue("Expected startCall to succeed but got error: $errorMessage", started)
        assertTrue(errorMessage == null)
    }

    @Test
    fun `buildClient returns the same instance while settings are unchanged`() {
        settingsRepository.save(
            Settings(
                provider = LlmProvider.OPENAI_COMPATIBLE,
                baseUrl = "https://api.openai.com/v1",
                apiKey = "llm-key"
            )
        )

        val first = controller.buildClient()
        val second = controller.buildClient()

        assertTrue("cached LLMClient must be reused for an unchanged fingerprint", first === second)
    }

    @Test
    fun `buildClient rebuilds when the settings fingerprint changes`() {
        settingsRepository.save(
            Settings(
                provider = LlmProvider.OPENAI_COMPATIBLE,
                baseUrl = "https://api.openai.com/v1",
                apiKey = "llm-key"
            )
        )
        val first = controller.buildClient()

        settingsRepository.save(
            Settings(
                provider = LlmProvider.OPENAI_COMPATIBLE,
                baseUrl = "https://api.openai.com/v1",
                apiKey = "changed-key"
            )
        )
        val second = controller.buildClient()

        assertTrue("a changed API key must force a client rebuild", first !== second)
    }

    @Test
    fun `startWakeWordCall transitions out of idle when configuration is valid`() {
        settingsRepository.save(
            Settings(
                provider = LlmProvider.SAMOSA_AI,
                samosaSessionToken = "samosa-jwt-token",
                sttProvider = AudioProvider.SAMOSA_AI,
                sttApiModel = "whisper-1",
                ttsProvider = AudioProvider.SAMOSA_AI,
                ttsApiModel = "tts-1"
            )
        )

        val started = controller.startWakeWordCall()

        assertTrue("startWakeWordCall should reuse the valid startCall path", started)
        assertTrue(
            "A wake-word call must immediately leave the idle state",
            controller.isActive()
        )
    }

    @Test
    fun `startWakeWordCall fails when configuration is invalid`() {
        // No provider / API key configured — buildClient() returns null and
        // the call reports an error, so startWakeWordCall must short-circuit.
        var errorMessage: String? = null
        controller.onError = { errorMessage = it }

        val started = controller.startWakeWordCall()

        assertFalse("startWakeWordCall must surface configuration errors", started)
        assertTrue(errorMessage != null)
        assertFalse("The call controller must stay idle on a failed wake-word start", controller.isActive())
    }

    @Test
    fun `screenContextNote asks the user to enable accessibility when capture is unavailable`() {
        val note = controller.screenContextNote(
            captureAvailable = false,
            screenText = null,
            blankScreen = false
        )
        assertTrue(
            "the accessibility-off note must tell the user to enable the service",
            note.contains("accessibility service is turned off") &&
                note.contains("Accessibility → Gotcha")
        )
    }

    @Test
    fun `screenContextNote prefers screen text and the blank-screen note over the generic fallback`() {
        val withText = controller.screenContextNote(
            captureAvailable = false,
            screenText = "Settings → About",
            blankScreen = false
        )
        assertTrue(withText.contains("Current screen text:") && withText.contains("Settings → About"))

        val blank = controller.screenContextNote(
            captureAvailable = true,
            screenText = null,
            blankScreen = true
        )
        assertTrue(blank.contains("screen was blank or off"))

        val failed = controller.screenContextNote(
            captureAvailable = true,
            screenText = null,
            blankScreen = false
        )
        assertTrue(failed.contains("could not be captured"))
    }

    @Test
    fun `hands-free input waits for a pending question and never for a confirmation overlay`() {
        // Call-started announcement done → READY means "awaiting spoken input".
        assertTrue(controller.awaitingHandsFreeInput(CallState.READY, questionPending = false))
        assertTrue(controller.awaitingHandsFreeInput(CallState.READY, questionPending = true))

        // Agent question pending → the gate is held, so the mic opens.
        assertTrue(controller.awaitingHandsFreeInput(CallState.WAITING_USER, questionPending = true))

        // A destructive-action confirmation prompt also sets WAITING_USER, but
        // holds no questionGate — the mic must NOT auto-open (opening it and
        // letting finishTurn fall through to a fresh turn would run two agent
        // turns concurrently while awaitConfirmation is still suspended).
        assertFalse(controller.awaitingHandsFreeInput(CallState.WAITING_USER, questionPending = false))

        // Agent is busy answering / speaking → not awaiting input.
        assertFalse(controller.awaitingHandsFreeInput(CallState.THINKING, questionPending = false))
        assertFalse(controller.awaitingHandsFreeInput(CallState.THINKING, questionPending = true))
        assertFalse(controller.awaitingHandsFreeInput(CallState.SPEAKING, questionPending = true))
    }

    @Test
    fun `mic start failure surfaces an error and records it in the transcript`() {
        val errors = mutableListOf<String>()
        controller.onError = { errors.add(it) }

        controller.onMicStartFailed()

        assertEquals(1, errors.size)
        assertTrue(
            "expected a mic-start message but got: ${errors.firstOrNull()}",
            errors.firstOrNull()?.contains("Couldn't start the microphone") == true
        )
        assertTrue(
            "mic-start failure must be recorded as an error transcript entry",
            controller.transcript.value.any {
                it.kind == MessageKind.ERROR && it.text.contains("Couldn't start the microphone")
            }
        )
    }

    @Test
    fun `narration tts failure is reported once per call, repeats are not surfaced`() {
        val errors = mutableListOf<String>()
        controller.onError = { errors.add(it) }

        controller.onNarrationTtsFailed()
        controller.onNarrationTtsFailed()

        assertEquals(
            "a broken narration TTS must not spam the user with repeated cards",
            1,
            errors.size
        )
        assertTrue(
            "expected the shared TTS wording but got: ${errors.firstOrNull()}",
            errors.firstOrNull()?.contains("check your Text-to-Speech settings") == true
        )
    }
}
