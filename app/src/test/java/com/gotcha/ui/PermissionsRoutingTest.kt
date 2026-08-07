package com.gotcha.ui

import android.content.Context
import androidx.health.connect.client.PermissionController
import androidx.test.core.app.ApplicationProvider
import com.gotcha.tools.HealthPermissionState
import com.gotcha.tools.HealthTool
import com.gotcha.tools.ToolResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the permission routing table (`openSpecialAccess`) against the exact
 * regression it has already had once: a permission row whose toggle has no
 * route and therefore silently does nothing — the Health Connect row before
 * `ToolResult.HEALTH_CONNECT` was routed.
 *
 * Firing an intent needs a device; picking *which* intent to fire does not,
 * which is the point of keeping the table as a `when` that maps markers to
 * intents.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PermissionsRoutingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val packageName = context.packageName

    @Test
    fun everySpecialMarkerInTheCatalogHasARoute() {
        val markers = allPermissionGroups()
            .flatMap { it.items }
            .mapNotNull { it.specialMarker }
            .toSet()
        assertTrue("catalog must declare at least one special-access marker", markers.isNotEmpty())

        markers.forEach { marker ->
            // A missing case falls through to `else -> null` and the toggle does nothing —
            // exactly the bug this test exists for.
            val intent = openSpecialAccess(context, marker, packageName)
            when (marker) {
                // Env-dependent no-ops: nothing in the test environment can be launched,
                // but the route must still degrade cleanly instead of throwing.
                ToolResult.VPN_CONSENT,
                ToolResult.TERMUX_ACCESS -> Unit
                ToolResult.HEALTH_CONNECT -> assertEquals(
                    "with no provider installed the toggle must steer to the Play listing",
                    "market://details?id=com.google.android.apps.healthdata",
                    intent?.getData()?.toString()
                )
                else -> assertNotNull("marker '$marker' must have a route", intent)
            }
        }
    }

    @Test
    fun healthConnectPermissionScreenIsTheProviderTargetedIntent() {
        // The exact intent the HEALTH_CONNECT route fires when the provider is available:
        // PermissionController.createRequestPermissionResultContract().createIntent(...). The
        // route itself can't be driven to this branch on the JVM (getSdkStatus needs a real
        // Health Connect provider), so pin down the intent it would fire.
        val intent = PermissionController.createRequestPermissionResultContract()
            .createIntent(context, HealthTool.PERMISSIONS)

        assertEquals(
            "the permission request must target the Health Connect provider",
            "com.google.android.apps.healthdata",
            intent.getPackage()
        )
    }

    @Test
    fun refreshIsASafeNoOpWhenHealthConnectIsAbsent() = runTest {
        // Health Connect is not installed under Robolectric; the resume-refresh
        // path added to PermissionsSection must not throw on such devices.
        assertFalse(HealthPermissionState.refresh(context))
    }
}
