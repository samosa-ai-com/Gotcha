package com.gotcha.auth

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.browser.customtabs.CustomTabsService
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Samosa sign-in prefers Chrome's Custom Tab over the default browser: some default browsers
 * (Aloha, 2026-11-15) leave Google's account chooser on a blank page, so the sign-in never returns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomTabBrowserTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val url = Uri.parse("https://dashboard.samosa-ai.com/?oauth_client=gotcha-android")

    /** Gotcha opens sign-in from an Activity (a Custom Tab needs one). */
    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    /** Registers [pkg] as a browser that offers the Custom Tabs service. */
    private fun installCustomTabsBrowser(pkg: String) {
        val service = ServiceInfo().apply {
            packageName = pkg
            name = "$pkg.CustomTabsService"
        }
        val info = ResolveInfo().apply { serviceInfo = service }
        val pm = shadowOf(app.packageManager)
        pm.addResolveInfoForIntent(Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION), info)
        pm.addServiceIfNotPresent(ComponentName(pkg, service.name))
        pm.addIntentFilterForService(
            ComponentName(pkg, service.name),
            IntentFilter(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION)
        )
    }

    @Test
    fun `chrome is chosen when installed, even if another browser is the default`() {
        installCustomTabsBrowser("com.aloha.browser")
        installCustomTabsBrowser("com.android.chrome")

        assertEquals("com.android.chrome", CustomTabBrowser.preferredBrowser(app))
        val activity = activity()
        assertTrue(CustomTabBrowser.open(activity, url))
        val started = shadowOf(activity).nextStartedActivity
        assertEquals("com.android.chrome", started.`package`)
        assertEquals(url, started.data)
    }

    @Test
    fun `without chrome the default custom tabs browser is used as before`() {
        installCustomTabsBrowser("com.aloha.browser")

        assertNull(CustomTabBrowser.preferredBrowser(app))
        val activity = activity()
        assertTrue(CustomTabBrowser.open(activity, url))
        val started = shadowOf(activity).nextStartedActivity
        assertNull(started.`package`)
        assertEquals(url, started.data)
    }

    @Test
    fun `chrome beta counts when stable chrome is missing`() {
        installCustomTabsBrowser("com.chrome.beta")

        assertEquals("com.chrome.beta", CustomTabBrowser.preferredBrowser(app))
    }
}
