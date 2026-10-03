package com.gotcha.ui

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.provider.Settings as AndroidSettings

/**
 * Guards the sentence shown before each runtime permission dialog (issue #79).
 *
 * The system dialog says what is being asked for but never why, and Gotcha no
 * longer asks for anything at launch — so the only thing standing between "an
 * app wants your call log" and the user is [runtimePermissionAsk]. A permission
 * with no sentence of its own falls back to something generic, which is exactly
 * the scary-at-startup prompt this issue was filed about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionAskTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyRuntimePermissionInTheCatalogSaysWhyItIsNeeded() {
        val runtimePermissions = allPermissionGroups()
            .flatMap { it.items }
            .flatMap { listOfNotNull(it.androidPermission) + it.extraPermissions }
            // Termux's RUN_COMMAND is a runtime permission but never travels this
            // path: it needs a second, in-Termux step, so it is routed as a
            // special access (ToolResult.TERMUX_ACCESS) with its own journey.
            .filter { it.startsWith("android.permission.") }
            .toSet()
        assertTrue("the catalog must declare runtime permissions", runtimePermissions.isNotEmpty())

        runtimePermissions.forEach { permission ->
            val ask = runtimePermissionAsk(permission)
            assertTrue(
                "'$permission' has no rationale of its own — it fell back to a generic sentence",
                ask.rationale.startsWith("Gotcha needs") && !ask.rationale.contains("to do what you asked")
            )
            assertFalse(
                "'$permission' must be named in the user's words, not as a permission string",
                ask.title.contains("android.permission")
            )
        }
    }

    @Test
    fun theAskNamesTheCapabilityTheSettingsRowNames() {
        // One vocabulary for one permission: the dialog that asks for it and the
        // Settings row that shows it granted have to be recognisably the same thing.
        val ask = runtimePermissionAsk(android.Manifest.permission.READ_CALL_LOG)
        assertEquals("Call Log", ask.title)
    }

    @Test
    fun anUnknownPermissionStillProducesAReadableAsk() {
        // A tool reporting something the catalog has never heard of must still
        // yield a dialog a human can act on, not a raw permission string.
        val ask = runtimePermissionAsk("android.permission.BODY_SENSORS")
        assertEquals("Body Sensors", ask.title)
        assertTrue(ask.rationale.startsWith("Gotcha needs"))
    }

    @Test
    fun revokingSteersToThisAppsOwnSettingsPage() {
        // Android lets no app drop its own grants; every "turn this off" path —
        // the Settings switch and the permanently-denied dialog — has to land on
        // the one screen that can, for this package and no other.
        val intent = openAppPermissionSettings(context, context.packageName)
        assertEquals(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.data?.toString())
        assertTrue(
            "launched from a non-activity context, so it needs its own task",
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0
        )
    }
}
