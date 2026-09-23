package com.gotcha.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
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
class DailyTipNotifierTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager = application.getSystemService(NotificationManager::class.java)
    private val tip = DAILY_TIPS.first()

    @Before
    fun clearTray() {
        manager.cancelAll()
    }

    @Test
    fun `posts the tip on its own channel, opening it on tap`() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(DailyTipNotifier(application).notify(tip))

        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(DailyTipNotifier.CHANNEL_ID, notification.channelId)
        assertEquals(tip.title, notification.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals(tip.body, notification.extras.getString(NotificationCompat.EXTRA_TEXT))
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(tip.id, intent.getStringExtra(DailyTipNotifier.EXTRA_DAILY_TIP_ID))
    }

    @Test
    fun `a newer tip replaces the unread one`() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        DailyTipNotifier(application).notify(DAILY_TIPS[0])
        DailyTipNotifier(application).notify(DAILY_TIPS[1])
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(DAILY_TIPS[1].title, notification.extras.getString(NotificationCompat.EXTRA_TITLE))
    }

    @Test
    fun `nothing is posted without the permission`() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(DailyTipNotifier(application).notify(tip))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
