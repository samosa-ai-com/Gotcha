package com.gotcha.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import java.io.File

/**
 * Context-dependent updater paths: the trusted-URL gate on the public
 * [AppUpdateManager.downloadUpdate] entry point and the install-permission flow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdateManagerRobolectricTest {

    private val updateManager = AppUpdateManager()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun downloadUpdate_untrustedUrl_returnsFailureBeforeAnyNetworkUse() = runTest {
        val info = AppUpdateInfo(
            versionCode = 2,
            versionName = "1.0.1",
            downloadUrl = "https://evil.example.com/app.apk"
        )

        val result = updateManager.downloadUpdate(context, info)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun downloadUpdate_httpScheme_returnsFailure() = runTest {
        val info = AppUpdateInfo(
            versionCode = 2,
            versionName = "1.0.1",
            downloadUrl = "http://github.com/samosa-ai-com/Gotcha/releases/download/v1.0.1/app.apk"
        )

        val result = updateManager.downloadUpdate(context, info)

        assertTrue(result.isFailure)
    }

    @Test
    fun installUpdate_withoutInstallPermission_returnsFalseAndOffersSettings() {
        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        val apkFile = apkInCache()

        val installed = updateManager.installUpdate(context, apkFile)

        assertFalse(installed)
        val intent = ShadowApplication.getInstance().nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals(Uri.parse("package:${context.packageName}"), intent.data)
    }

    @Test
    fun installUpdate_withInstallPermission_launchesPackageInstaller() {
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        val apkFile = apkInCache()

        val installed = updateManager.installUpdate(context, apkFile)

        assertTrue(installed)
        val intent = ShadowApplication.getInstance().nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content", intent.data?.scheme)
        assertEquals("${context.packageName}.fileprovider", intent.data?.authority)
    }

    @Test
    fun installUpdate_apkOutsideFileProviderMapping_returnsFalseInsteadOfCrashing() {
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        // File in the cache root, but file_paths.xml only maps cacheDir/updates/.
        val apkFile = File(context.cacheDir, "gotcha-orphan.apk").apply { writeBytes(byteArrayOf(1)) }

        val installed = updateManager.installUpdate(context, apkFile)

        assertFalse(installed)
    }

    @Test
    fun openInstallPermissionSettings_launchesUnknownAppSources() {
        updateManager.openInstallPermissionSettings(context)

        val intent = ShadowApplication.getInstance().nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals(Uri.parse("package:${context.packageName}"), intent.data)
    }

    private fun apkInCache(): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        return File(dir, "gotcha-test.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    }
}
