package com.gotcha.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPackageManager

/** `list_installed_apps` against a ShadowPackageManager seeded with known launchable apps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class AppsToolTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tool = AppsTool(context)

    private lateinit var packageManager: ShadowPackageManager

    @Before
    fun seedApps() {
        packageManager = shadowOf(context.packageManager)
        installLaunchableApp("com.example.notes", "Notes", system = false)
        installLaunchableApp("com.example.camera", "Camera", system = true)
        installLaunchableApp("com.other.weather", "Weather Live", system = false)
    }

    private fun installLaunchableApp(packageName: String, label: String, system: Boolean) {
        val applicationInfo = ApplicationInfo().apply {
            this.packageName = packageName
            this.name = label
            nonLocalizedLabel = label
            if (system) flags = flags or ApplicationInfo.FLAG_SYSTEM
        }
        val packageInfo = android.content.pm.PackageInfo().apply {
            this.packageName = packageName
            this.applicationInfo = applicationInfo
        }
        packageManager.installPackage(packageInfo)

        val component = ComponentName(packageName, "$packageName.MainActivity")
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        )
    }

    @Test
    fun `list_installed_apps lists launchable apps with their package names`() {
        val result = tool.listInstalledApps(null)

        assertTrue(result.message, result.success)
        assertTrue(result.message, result.message.contains("com.example.notes"))
        assertTrue(result.message, result.message.contains("com.other.weather"))
    }

    @Test
    fun `list_installed_apps marks system apps`() {
        val result = tool.listInstalledApps(null)

        val cameraLine = result.message.lines().first { it.contains("com.example.camera") }
        assertTrue("system app not marked: $cameraLine", cameraLine.contains("[system]"))

        val notesLine = result.message.lines().first { it.contains("com.example.notes") }
        assertFalse("user app marked as system: $notesLine", notesLine.contains("[system]"))
    }

    @Test
    fun `list_installed_apps reports user and system counts`() {
        val result = tool.listInstalledApps(null)

        assertTrue("expected a total line in:\n${result.message}", result.message.contains("Total:"))
        assertTrue(result.message, result.message.contains("user"))
        assertTrue(result.message, result.message.contains("system"))
    }

    @Test
    fun `list_installed_apps search filters by package name`() {
        val result = tool.listInstalledApps("weather")

        assertTrue(result.message, result.success)
        assertTrue(result.message, result.message.contains("com.other.weather"))
        assertFalse(
            "search returned an unrelated app:\n${result.message}",
            result.message.contains("com.example.notes")
        )
    }

    @Test
    fun `list_installed_apps search is case-insensitive`() {
        val lower = tool.listInstalledApps("weather").message
        val upper = tool.listInstalledApps("WEATHER").message

        assertTrue(upper, upper.contains("com.other.weather"))
        assertTrue("case changed the result set", lower.contains("com.other.weather"))
    }

    @Test
    fun `list_installed_apps reports no matches without failing`() {
        val result = tool.listInstalledApps("definitely-not-installed")

        assertTrue("an empty search result is not an error: ${result.message}", result.success)
        assertTrue(result.message, result.message.contains("No apps matching"))
    }
}
