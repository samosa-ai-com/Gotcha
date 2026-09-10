package com.gotcha.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import android.provider.Settings as AndroidSettings

@RunWith(RobolectricTestRunner::class)
class LanguageScreenTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [34])
    fun `openLanguageSettings targets per-app locale settings on API 34`() {
        openLanguageSettings(context)
        val intent = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull(intent)
        assertEquals(AndroidSettings.ACTION_APP_LOCALE_SETTINGS, intent.action)
        assertEquals(Uri.parse("package:${context.packageName}"), intent.data)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    @Config(sdk = [30])
    fun `openLanguageSettings falls back to device locale settings on API 30`() {
        openLanguageSettings(context)
        val intent = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull(intent)
        assertEquals(AndroidSettings.ACTION_LOCALE_SETTINGS, intent.action)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `openLanguageSettings shows toast when activities cannot be launched`() {
        val failingContext = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent?) {
                throw android.content.ActivityNotFoundException("Activity not found")
            }
        }
        openLanguageSettings(failingContext)
        assertEquals("Could not open language settings.", ShadowToast.getTextOfLatestToast())
    }
}
