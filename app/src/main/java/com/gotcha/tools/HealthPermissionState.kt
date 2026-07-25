package com.gotcha.tools

import android.content.Context
import androidx.health.connect.client.HealthConnectClient

/**
 * Last known Health Connect grant state.
 *
 * Health Connect only exposes granted permissions through a `suspend` call, but
 * the Settings permission list ([com.gotcha.ui.PermissionItems]) needs a
 * synchronous answer to render a row. This caches the most recent result:
 * [refresh] is called from [HealthTool] on every health tool call and from
 * MainActivity after the permission screen returns, so the row is accurate by
 * the time a user could look at it.
 */
object HealthPermissionState {

    @Volatile
    private var granted: Boolean = false

    /** Cached answer — false until the first [refresh]. */
    fun isGranted(): Boolean = granted

    /** Re-reads the grant state; safe to call when Health Connect is absent. */
    suspend fun refresh(context: Context): Boolean {
        granted = runCatching {
            if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
                false
            } else {
                HealthConnectClient.getOrCreate(context)
                    .permissionController
                    .getGrantedPermissions()
                    .any { it in HealthTool.PERMISSIONS }
            }
        }.getOrDefault(false)
        return granted
    }

    /** Records a result already known (e.g. straight from the permission launcher). */
    fun set(value: Boolean) {
        granted = value
    }
}
