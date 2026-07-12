package com.gotcha.tools

import android.app.NotificationManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class DeviceTool(private val context: Context) {

    /** Turn the camera flash on or off. No permission needed for CameraManager.setTorchMode. */
    fun toggleTorch(on: Boolean, durationSeconds: Int?): ToolResult {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ToolResult.error("This device has no controllable flashlight.")

            cm.setTorchMode(cameraId, on)
            if (on && durationSeconds != null && durationSeconds > 0) {
                val ms = durationSeconds.coerceIn(1, 300) * 1000L
                Handler(Looper.getMainLooper()).postDelayed({
                    try { cm.setTorchMode(cameraId, false) } catch (_: Exception) {}
                }, ms)
                ToolResult.ok("Flashlight turned on for ${durationSeconds}s.")
            } else {
                ToolResult.ok("Flashlight turned ${if (on) "on" else "off"}.")
            }
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            if ("in use" in msg || "already" in msg) {
                ToolResult.error("Another app may be using the camera. Close other camera apps and try again.")
            } else {
                ToolResult.error("Could not toggle the flashlight: ${e.message}")
            }
        }
    }

    /** Set a volume stream to a percentage. Reports previous level. */
    fun setVolume(stream: String, percent: Int, showUi: Boolean): ToolResult {
        if (percent !in 0..100) return ToolResult.error("Volume must be between 0 and 100 (got $percent).")
        val streamType = when (stream.lowercase().trim()) {
            "media", "music" -> AudioManager.STREAM_MUSIC
            "ring", "ringer" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call", "voice" -> AudioManager.STREAM_VOICE_CALL
            else -> return ToolResult.error(
                "Unknown volume stream '$stream'. Use media, ring, alarm, notification, or call."
            )
        }
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(streamType)
            val previousPct = (am.getStreamVolume(streamType) * 100) / max
            val target = (percent * max) / 100
            val flags = if (showUi) AudioManager.FLAG_SHOW_UI else 0
            am.setStreamVolume(streamType, target, flags)
            ToolResult.ok("Set $stream volume from ${previousPct}% to ${percent}%.")
        } catch (e: SecurityException) {
            ToolResult.permissionNeeded(
                ToolResult.DND_ACCESS,
                "Changing this volume needs Do Not Disturb access. I have opened that settings page — please enable it and ask again."
            )
        } catch (e: Exception) {
            ToolResult.error("Could not set the volume: ${e.message}")
        }
    }

    /** Read current volume level(s). */
    fun getVolume(stream: String?): ToolResult {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val streams = if (stream != null) listOf(stream.lowercase().trim()) else
                listOf("media", "ring", "alarm", "notification", "call")
            val results = streams.map { name ->
                val type = when (name) {
                    "media", "music" -> AudioManager.STREAM_MUSIC
                    "ring", "ringer" -> AudioManager.STREAM_RING
                    "alarm" -> AudioManager.STREAM_ALARM
                    "notification" -> AudioManager.STREAM_NOTIFICATION
                    "call", "voice" -> AudioManager.STREAM_VOICE_CALL
                    else -> return ToolResult.error("Unknown stream '$name'. Use media, ring, alarm, notification, or call.")
                }
                val max = am.getStreamMaxVolume(type)
                val current = am.getStreamVolume(type)
                val pct = (current * 100) / max
                "$name: ${pct}%"
            }
            ToolResult.ok(results.joinToString("\n"))
        } catch (e: Exception) {
            ToolResult.error("Could not read volume: ${e.message}")
        }
    }

    /** Set the ringer mode: normal / vibrate / silent. Silent & vibrate need DND access on N+. */
    fun setRingerMode(mode: String): ToolResult {
        val ringerMode = when (mode.lowercase().trim()) {
            "normal", "loud" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent", "mute" -> AudioManager.RINGER_MODE_SILENT
            else -> return ToolResult.error("Unknown ringer mode '$mode'. Use normal, vibrate, or silent.")
        }
        if (ringerMode != AudioManager.RINGER_MODE_NORMAL && !hasDndAccess()) {
            return ToolResult.permissionNeeded(
                ToolResult.DND_ACCESS,
                "Silencing the ringer needs Do Not Disturb access. I have opened that settings page — please enable it and ask again."
            )
        }
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = ringerMode
            ToolResult.ok("Ringer set to ${mode.lowercase().trim()}.")
        } catch (e: SecurityException) {
            ToolResult.permissionNeeded(
                ToolResult.DND_ACCESS,
                "Changing the ringer needs Do Not Disturb access. I have opened that settings page — please enable it and ask again."
            )
        } catch (e: Exception) {
            ToolResult.error("Could not set the ringer mode: ${e.message}")
        }
    }

    /** Vibrate with optional intensity and pattern (needs VIBRATE, an install-time permission). */
    fun vibrate(durationMs: Int, intensity: Int, pattern: String?): ToolResult {
        val vibrator = getVibrator() ?: return ToolResult.error("This device has no vibrator.")
        val amplitude = (intensity.coerceIn(0, 100) * 255) / 100
        val effect = if (pattern != null) {
            patternEffect(pattern.lowercase().trim(), amplitude)
        } else {
            val ms = durationMs.coerceIn(1, 5000).toLong()
            if (amplitude == 0) VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
            else VibrationEffect.createOneShot(ms, amplitude)
        }
        return try {
            vibrator.vibrate(effect)
            val desc = if (pattern != null) " (pattern: $pattern)" else " for ${durationMs.coerceIn(1, 5000)}ms"
            ToolResult.ok("Vibrated$desc.")
        } catch (e: Exception) {
            ToolResult.error("Could not vibrate: ${e.message}")
        }
    }

    private fun patternEffect(pattern: String, amplitude: Int): VibrationEffect {
        val timings = when (pattern) {
            "short" -> longArrayOf(0, 100)
            "long" -> longArrayOf(0, 1000)
            "double" -> longArrayOf(0, 200, 800, 200)
            "sos" -> longArrayOf(0, 200, 200, 200, 200, 200, 600, 600, 600, 200, 600, 200, 600, 600, 200, 200, 200, 200, 200)
            else -> longArrayOf(0, 500)
        }
        val amps = if (amplitude == 0) null else IntArray(timings.size) { amplitude }
        return if (amps != null) {
            VibrationEffect.createWaveform(timings, amps, -1)
        } else {
            @Suppress("DEPRECATION")
            VibrationEffect.createWaveform(timings, -1)
        }
    }

    private fun getVibrator(): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
        } catch (_: Exception) { null }
    }

    /** Turn Do Not Disturb on or off (needs DND / notification-policy access). */
    fun setDnd(enabled: Boolean): ToolResult {
        if (!hasDndAccess()) {
            return ToolResult.permissionNeeded(
                ToolResult.DND_ACCESS,
                "Do Not Disturb control needs notification-policy access. I have opened that settings page — please enable it and ask again."
            )
        }
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.setInterruptionFilter(
                if (enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            ToolResult.ok("Do Not Disturb turned ${if (enabled) "on" else "off"}.")
        } catch (e: Exception) {
            ToolResult.error("Could not change Do Not Disturb: ${e.message}")
        }
    }

    private fun hasDndAccess(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }
}
