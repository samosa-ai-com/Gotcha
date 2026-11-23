package com.gotcha.tools

import android.content.Context
import com.gotcha.data.SettingsRepository
import com.gotcha.notifications.LocalNotificationScheduler

/**
 * Saves a settings change the user approved, then does what the Settings screen
 * does alongside the same change.
 *
 * Applied onto a fresh load, so a concurrent write elsewhere (the Settings
 * screen, the assistive ball) is not clobbered by the snapshot the prompt was
 * built from. Most settings are read where they are used and need nothing
 * more; the daily tip is an alarm armed for a time, so a new time re-arms it.
 */
fun saveApprovedSettings(context: Context, repository: SettingsRepository, plan: SettingsChangePlan) {
    val before = repository.load()
    val after = plan.applyTo(before)
    repository.save(after)
    if (after.dailyTipMinuteOfDay != before.dailyTipMinuteOfDay) {
        LocalNotificationScheduler.scheduleTip(context, after.dailyTipMinuteOfDay)
    }
}
