package com.gotcha.tools

import android.app.NotificationManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class DeviceTool(private val context: Context) {

    /** Turn the camera flash on or off (no permission needed for CameraManager.setTorchMode). */
    fun toggleTorch(on: Boolean): ToolResult {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ToolResult.error("This device has no controllable flashlight.")
            cm.setTorchMode(cameraId, on)
            ToolResult.ok("Flashlight turned ${if (on) "on" else "off"}.")
        } catch (e: Exception) {
            ToolResult.error("Could not toggle the flashlight: ${e.message}")
        }
    }

    /** Set a volume stream to a percentage (no permission, though muting ring may need DND access). */
    fun setVolume(stream: String, percent: Int): ToolResult {
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
            val target = (percent * max) / 100
            am.setStreamVolume(streamType, target, 0)
            ToolResult.ok("Set $stream volume to $percent%.")
        } catch (e: SecurityException) {
            ToolResult.permissionNeeded(
                ToolResult.DND_ACCESS,
                "Changing this volume needs Do Not Disturb access. I have opened that settings page — please enable it and ask again."
            )
        } catch (e: Exception) {
            ToolResult.error("Could not set the volume: ${e.message}")
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

    /** Vibrate for a number of milliseconds (needs VIBRATE, an install-time permission). */
    fun vibrate(durationMs: Int): ToolResult {
        val ms = durationMs.coerceIn(1, 5000).toLong()
        return try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!vibrator.hasVibrator()) return ToolResult.error("This device has no vibrator.")
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            ToolResult.ok("Vibrated for ${ms}ms.")
        } catch (e: Exception) {
            ToolResult.error("Could not vibrate: ${e.message}")
        }
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
