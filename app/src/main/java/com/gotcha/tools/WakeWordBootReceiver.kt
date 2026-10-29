package com.gotcha.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.gotcha.data.SettingsRepository
import com.gotcha.service.AssistiveBallService

/** Restores the user-enabled always-on listener after a device reboot. */
class WakeWordBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = SettingsRepository(context).load()
        if (!settings.assistiveBallEnabled || !settings.wakeWordEnabled) return
        // Microphone-type foreground services throw without RECORD_AUDIO
        // (issue #148): stay off quietly after a reboot that cleared the
        // grant. The toggle asks again when the user re-enables the ball.
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                context,
                AssistiveBallService.startIntent(context)
            )
        }
    }
}
