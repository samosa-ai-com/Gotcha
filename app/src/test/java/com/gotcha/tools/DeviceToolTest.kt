package com.gotcha.tools

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `set_volume`, `get_volume` and `set_ringer_mode` against Robolectric's ShadowAudioManager.
 *
 * These tools translate between the LLM's percentages/labels and the platform's per-stream
 * integer scales — arithmetic that is easy to get subtly wrong and invisible in manual QA.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class DeviceToolTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tool = DeviceTool(context)

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ---- set_volume ----

    @Test
    fun `set_volume writes the scaled level to the right stream`() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        val result = tool.setVolume("media", 50, showUi = false)

        assertTrue(result.message, result.success)
        assertEquals(max / 2, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun `set_volume maps aliases to the same stream`() {
        tool.setVolume("music", 100, showUi = false)
        val viaAlias = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        tool.setVolume("media", 100, showUi = false)

        assertEquals(viaAlias, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun `set_volume reports the previous level`() {
        tool.setVolume("media", 100, showUi = false)

        val result = tool.setVolume("media", 0, showUi = false)

        assertTrue("expected the old level in: ${result.message}", result.message.contains("100%"))
        assertTrue("expected the new level in: ${result.message}", result.message.contains("0%"))
    }

    @Test
    fun `set_volume rejects out-of-range percentages`() {
        assertFalse(tool.setVolume("media", -1, showUi = false).success)
        assertFalse(tool.setVolume("media", 101, showUi = false).success)
    }

    @Test
    fun `set_volume rejects an unknown stream`() {
        val result = tool.setVolume("subwoofer", 50, showUi = false)

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("Unknown volume stream"))
    }

    @Test
    fun `set_volume accepts every documented stream name`() {
        listOf("media", "ring", "alarm", "notification", "call").forEach { stream ->
            assertTrue("'$stream' was rejected", tool.setVolume(stream, 50, showUi = false).success)
        }
    }

    // ---- get_volume ----

    @Test
    fun `get_volume reports the level as a percentage`() {
        tool.setVolume("media", 100, showUi = false)

        val result = tool.getVolume("media")

        assertTrue(result.message, result.success)
        assertEquals("media: 100%", result.message)
    }

    @Test
    fun `get_volume with no stream reports every stream`() {
        val result = tool.getVolume(null)

        assertTrue(result.message, result.success)
        listOf("media", "ring", "alarm", "notification", "call").forEach {
            assertTrue("'$it' missing from:\n${result.message}", result.message.contains("$it:"))
        }
    }

    @Test
    fun `get_volume rejects an unknown stream`() {
        assertFalse(tool.getVolume("subwoofer").success)
    }

    // ---- set_ringer_mode ----

    @Test
    fun `set_ringer_mode normal needs no DND access`() {
        val result = tool.setRingerMode("normal")

        assertTrue(result.message, result.success)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
    }

    @Test
    fun `set_ringer_mode silent asks for DND access when it is not granted`() {
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(false)

        val result = tool.setRingerMode("silent")

        assertFalse(result.message, result.success)
        assertEquals(ToolResult.DND_ACCESS, result.needsPermission)
    }

    @Test
    fun `set_ringer_mode silent works once DND access is granted`() {
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)

        val result = tool.setRingerMode("silent")

        assertTrue(result.message, result.success)
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
    }

    @Test
    fun `set_ringer_mode vibrate works once DND access is granted`() {
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)

        assertTrue(tool.setRingerMode("vibrate").success)
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
    }

    @Test
    fun `set_ringer_mode rejects an unknown mode`() {
        val result = tool.setRingerMode("loudest")

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("Unknown ringer mode"))
    }
}
