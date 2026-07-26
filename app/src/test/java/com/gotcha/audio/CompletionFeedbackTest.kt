package com.gotcha.audio

import android.content.Context
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.testsupport.FakeAndroidKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The reply alert is the only signal a user gets when they aren't watching the
 * screen, so both halves matter: that it fires, and that switching it off
 * actually silences it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompletionFeedbackTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val vibrator: Vibrator
        get() = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        vibrator.cancel()
    }

    @Test
    fun `replyArrived vibrates when the vibration alert is enabled`() {
        CompletionFeedback.replyArrived(context, vibrate = true, chime = false)
        assertTrue(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun `replyArrived stays silent when both alerts are disabled`() {
        CompletionFeedback.replyArrived(context, vibrate = false, chime = false)
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun `error always vibrates regardless of the notification settings`() {
        CompletionFeedback.error(context)
        assertTrue(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun `reply and error use different vibration patterns`() {
        CompletionFeedback.replyArrived(context, vibrate = true, chime = false)
        val replyMs = shadowOf(vibrator).milliseconds
        vibrator.cancel()
        CompletionFeedback.error(context)
        // Same-length buzzes would be indistinguishable without looking at the
        // screen, which is the entire point of the signal.
        assertTrue(shadowOf(vibrator).milliseconds != replyMs)
    }

    @Test
    fun `notification alert defaults to vibration only`() {
        val defaults = Settings()
        assertTrue(defaults.notifyVibrationEnabled)
        assertFalse(defaults.notifyChimeEnabled)
    }

    @Test
    fun `notification settings survive a save and load round-trip`() {
        val repo = SettingsRepository(context)
        repo.save(
            Settings(
                notifyVibrationEnabled = false,
                notifyChimeEnabled = true
            )
        )
        val loaded = repo.load()
        assertEquals(false, loaded.notifyVibrationEnabled)
        assertEquals(true, loaded.notifyChimeEnabled)
    }
}
