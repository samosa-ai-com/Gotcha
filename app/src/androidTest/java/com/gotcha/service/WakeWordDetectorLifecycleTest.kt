package com.gotcha.service

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Guards the pause/release split from issue #37.
 *
 * `AssistiveBallService` pauses the listener on every call transition *and*
 * every TTS utterance, which before this split meant re-reading ~2.6 MB of
 * model bytes and rebuilding three ORT sessions each time the app spoke a
 * single sentence. The fix only holds if [WakeWordDetector.pause] genuinely
 * keeps the sessions, so that is asserted directly rather than inferred from
 * how long a resume takes.
 */
@RunWith(AndroidJUnit4::class)
class WakeWordDetectorLifecycleTest {

    @get:Rule
    val micPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val error = AtomicReference<String?>(null)
    private var started = CountDownLatch(1)
    private var detector: WakeWordDetector? = null

    @After
    fun tearDown() {
        detector?.release()
        scope.cancel()
    }

    @Test
    fun pauseKeepsTheModelsLoaded_releaseGivesThemBack() {
        val detector = newDetector()

        awaitStart(detector)
        assertEquals("cold start should load the models once", 1, detector.sessionLoadCount)

        // Three rounds of the churn a talkative session produces.
        repeat(3) { round ->
            detector.pause()
            assertTrue("detector still running after pause in round $round", !detector.isRunning())
            awaitStart(detector)
            assertEquals(
                "resume in round $round reloaded the models",
                1,
                detector.sessionLoadCount
            )
        }

        // release() is the other half of the contract: it must actually let go,
        // so switching the wake word off does not leave 2.6 MB of models resident.
        detector.release()
        awaitStart(detector)
        assertEquals("release should force a fresh load", 2, detector.sessionLoadCount)
        assertNull(error.get())
    }

    @Test
    fun pauseReleasesTheMicrophone() {
        val detector = newDetector()
        awaitStart(detector)
        detector.pause()

        // The privacy guarantee in privacy-data-retention.md §10.3 is that the
        // mic is handed back while the app's own TTS speaks — keeping the ONNX
        // sessions must not have quietly weakened that. If pause() still held
        // the recorder, this second AudioRecord could not start.
        val probe = android.media.AudioRecord(
            android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
            OnnxWakeWordPipeline.SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            OnnxWakeWordPipeline.FRAME_SIZE * 2 * 4
        )
        try {
            assertEquals(
                android.media.AudioRecord.STATE_INITIALIZED,
                probe.state
            )
            probe.startRecording()
            val buffer = ShortArray(OnnxWakeWordPipeline.FRAME_SIZE)
            assertTrue("could not read audio after pause", probe.read(buffer, 0, buffer.size) > 0)
        } finally {
            try {
                probe.stop()
            } catch (_: IllegalStateException) {
                // Never started.
            }
            probe.release()
        }
    }

    private fun newDetector(): WakeWordDetector {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return WakeWordDetector(
            context = context,
            scope = scope,
            sensitivityProvider = { WakeWordMatcher.DEFAULT_SENSITIVITY },
            onStarted = { started.countDown() },
            onDetected = {},
            onError = { message ->
                error.set(message)
                started.countDown()
            }
        ).also { detector = it }
    }

    private fun awaitStart(detector: WakeWordDetector) {
        started = CountDownLatch(1)
        assertTrue("start() reported a precondition failure", detector.start())
        assertTrue(
            "listener never started (error=${error.get()})",
            started.await(10, TimeUnit.SECONDS)
        )
        assertNull("listener reported an error", error.get())
    }
}
