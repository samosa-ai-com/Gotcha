package com.gotcha.updater

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.notifications.LocalNotificationStore
import com.gotcha.notifications.LocalNotifier
import com.gotcha.notifications.NotificationCategory
import com.gotcha.notifications.NotificationTarget
import com.gotcha.testsupport.FakeAndroidKeyStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** The background update check and its notification (issue #126). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateNotificationsTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager = application.getSystemService(NotificationManager::class.java)
    private val zone = ZoneId.systemDefault()
    private val noon = LocalDate.now(zone).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val server = MockWebServer()

    /** Past the interval and, unlike noon + 12 h, outside the default quiet hours. */
    private val nextNoon = noon + 24L * 60L * 60L * 1000L

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        manager.cancelAll()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        SettingsRepository(application).save(Settings())
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun manifest(versionCode: Int, url: String = trustedUrl(versionCode)) =
        MockResponse().setBody(
            """{"versionCode":$versionCode,"versionName":"1.$versionCode.0","downloadUrl":"$url"}"""
        )

    private fun trustedUrl(versionCode: Int) =
        "https://github.com/samosa-ai-com/Gotcha/releases/download/v1.$versionCode.0/gotcha.apk"

    private fun check(now: Long = noon, online: Boolean = true) = runBlocking {
        runUpdateCheck(
            application,
            now = now,
            manager = AppUpdateManager(),
            manifestUrl = server.url("/update.json").toString(),
            installedVersionCode = 7,
            zone = zone,
            isOnline = { online }
        )
    }

    private fun posted() = shadowOf(manager).allNotifications

    // ---- the pure rules ----

    @Test
    fun `notifies only for a trusted version newer than both installed and announced`() {
        val info = AppUpdateInfo(versionCode = 8, versionName = "1.8.0", downloadUrl = trustedUrl(8))
        assertTrue(shouldNotifyUpdate(7, info, lastNotifiedVersionCode = 0, trustedDownload = true))
        assertFalse(shouldNotifyUpdate(8, info, 0, trustedDownload = true))
        assertFalse(shouldNotifyUpdate(9, info, 0, trustedDownload = true))
        assertFalse(shouldNotifyUpdate(7, info, lastNotifiedVersionCode = 8, trustedDownload = true))
        assertFalse(shouldNotifyUpdate(7, info, 0, trustedDownload = false))
    }

    @Test
    fun `a check is due after the interval, or when the clock went back`() {
        assertTrue(updateCheckDue(now = noon, lastCheckedAt = 0L))
        assertFalse(updateCheckDue(now = noon, lastCheckedAt = noon - UPDATE_CHECK_INTERVAL_MS + 1))
        assertTrue(updateCheckDue(now = noon, lastCheckedAt = noon - UPDATE_CHECK_INTERVAL_MS))
        assertTrue(updateCheckDue(now = noon, lastCheckedAt = noon + 60_000L))
    }

    // ---- the check ----

    @Test
    fun `a newer version is notified once, filed in the inbox, and opens the update`() {
        server.enqueue(manifest(8))
        server.enqueue(manifest(8))

        assertEquals(UpdateCheckOutcome.NOTIFIED, check())
        assertEquals(UpdateCheckOutcome.UP_TO_DATE, check(nextNoon))

        assertEquals(1, posted().size)
        val notification = posted().single()
        assertEquals(UpdateNotifier.CHANNEL_ID, notification.channelId)
        val tap = shadowOf(notification.contentIntent).savedIntent
        assertTrue(tap.getBooleanExtra(LocalNotifier.EXTRA_OPEN_UPDATE, false))
        val entry = LocalNotificationStore(application).entries().single()
        assertEquals(NotificationCategory.APP_UPDATE, entry.categoryOrNull)
        assertEquals(NotificationTarget.Update, entry.target)
    }

    @Test
    fun `the next version is notified again`() {
        server.enqueue(manifest(8))
        server.enqueue(manifest(9))

        assertEquals(UpdateCheckOutcome.NOTIFIED, check())
        assertEquals(UpdateCheckOutcome.NOTIFIED, check(nextNoon))
        assertEquals(2, LocalNotificationStore(application).entries().size)
    }

    @Test
    fun `fetches at most once per interval`() {
        server.enqueue(manifest(7))

        assertEquals(UpdateCheckOutcome.UP_TO_DATE, check())
        assertEquals(UpdateCheckOutcome.NOT_DUE, check(noon + UPDATE_CHECK_INTERVAL_MS - 1))
        assertEquals(1, server.requestCount)
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `switched off, nothing is fetched`() {
        SettingsRepository(application).save(Settings(updateNotificationsEnabled = false))

        assertEquals(UpdateCheckOutcome.OFF, check())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `without the notification permission, nothing is fetched or recorded`() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertEquals(UpdateCheckOutcome.CANNOT_POST, check())
        assertEquals(0, server.requestCount)

        // Granted later: the same version is still announced.
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        server.enqueue(manifest(8))
        assertEquals(UpdateCheckOutcome.NOTIFIED, check(noon + 1))
    }

    @Test
    fun `offline or a failed fetch leaves the next check free to try`() {
        assertEquals(UpdateCheckOutcome.OFFLINE, check(online = false))
        assertEquals(0, server.requestCount)

        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(UpdateCheckOutcome.FAILED, check(noon + 1))

        server.enqueue(manifest(8))
        assertEquals(UpdateCheckOutcome.NOTIFIED, check(noon + 2))
    }

    @Test
    fun `a download the updater would refuse is not announced`() {
        server.enqueue(manifest(8, url = "https://evil.example.com/gotcha.apk"))

        assertEquals(UpdateCheckOutcome.UP_TO_DATE, check())
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `quiet hours hold the check until they end`() {
        SettingsRepository(application).save(
            Settings(quietHoursEnabled = true, quietHoursStartMinute = 11 * 60, quietHoursEndMinute = 13 * 60)
        )

        assertEquals(UpdateCheckOutcome.QUIET_HOURS, check())
        assertEquals(0, server.requestCount)

        server.enqueue(manifest(8))
        assertEquals(UpdateCheckOutcome.NOTIFIED, check(noon + 2 * 60 * 60 * 1000L))
    }
}
