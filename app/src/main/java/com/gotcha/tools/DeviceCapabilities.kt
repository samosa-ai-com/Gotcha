package com.gotcha.tools

import android.content.Context
import java.io.File

/**
 * Runtime probes for the [Capability] set. Cheap enough to call once per LLM
 * round: everything here is a settings string read or a service lookup, except
 * root — see [rootAvailable].
 *
 * Single source of truth on purpose. `AgentEngine.buildEnvironmentString()`
 * renders these same values into the `<env>` block, so the status the model
 * reads and the tools it is offered can never disagree.
 */
object DeviceCapabilities {

    /** Paths a `su` binary lives at on the common root solutions. */
    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sbin/su",
        "/vendor/bin/su",
        "/su/bin/su",
        // Magisk is present even when the su binary itself is path-hidden.
        "/data/adb/magisk/busybox"
    )

    @Volatile
    private var cachedRoot: Boolean? = null

    fun available(context: Context): Set<Capability> = buildSet {
        if (accessibilityEnabled(context)) add(Capability.ACCESSIBILITY)
        if (notificationListenerEnabled(context)) add(Capability.NOTIFICATION_LISTENER)
        if (deviceAdminActive(context)) add(Capability.DEVICE_ADMIN)
        if (rootAvailable()) add(Capability.ROOT)
        if (healthConnectPresent(context)) add(Capability.HEALTH_CONNECT)
        if (overlayAllowed(context)) add(Capability.OVERLAY)
    }

    /** Tools to withhold from the model right now. */
    fun hiddenToolNames(context: Context): Set<String> =
        CapabilityCatalog.hiddenTools(available(context))

    fun accessibilityEnabled(context: Context): Boolean = secureListContains(
        context,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        "${context.packageName}/com.gotcha.service.GotchaAccessibilityService"
    )

    fun notificationListenerEnabled(context: Context): Boolean =
        secureListContains(context, "enabled_notification_listeners", context.packageName)

    fun deviceAdminActive(context: Context): Boolean = runCatching {
        context.getSystemService(android.app.admin.DevicePolicyManager::class.java)?.isAdminActive(
            android.content.ComponentName(
                context,
                com.gotcha.service.GotchaDeviceAdminReceiver::class.java
            )
        ) ?: false
    }.getOrDefault(false)

    fun overlayAllowed(context: Context): Boolean =
        runCatching { android.provider.Settings.canDrawOverlays(context) }.getOrDefault(false)

    /**
     * Whether Health Connect exists on the device — deliberately *not* whether
     * its permissions are granted.
     *
     * [HealthPermissionState] is a cache that starts false on every process
     * start and is only filled by a health tool actually running. Gating on it
     * would hide the health tools at launch, which would stop HealthTool from
     * ever running, which would leave the cache false: a user who had granted
     * the permission would silently lose the feature after every restart.
     * Availability is synchronous and has no such cycle, and leaving the tools
     * exposed while ungranted is what lets HealthTool raise the permission
     * prompt.
     */
    fun healthConnectPresent(context: Context): Boolean = runCatching {
        androidx.health.connect.client.HealthConnectClient.getSdkStatus(context) ==
            androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE
    }.getOrDefault(false)

    /**
     * Presence of a `su` binary, cached for the process.
     *
     * Deliberately a file-existence check rather than [RootTool.checkRoot]:
     * actually running `su` pops the Magisk grant dialog, and this runs on every
     * round. A false positive only means two extra schemas are offered and the
     * real probe fails later; `check_root` stays exposed to do it properly.
     */
    fun rootAvailable(): Boolean = cachedRoot ?: run {
        val found = runCatching { SU_PATHS.any { File(it).exists() } }.getOrDefault(false)
        cachedRoot = found
        found
    }

    /**
     * Records what the real `su` probe found. [rootAvailable] only looks for the
     * binary at the usual paths, so a device where `su` lives elsewhere would
     * keep the root tools hidden forever; running `check_root` — which stays
     * exposed precisely for this — corrects the guess.
     */
    fun setRootAvailable(value: Boolean) {
        cachedRoot = value
    }

    /** Clears the root cache. Only used by tests. */
    fun resetRootCacheForTesting() {
        cachedRoot = null
    }

    private fun secureListContains(context: Context, key: String, needle: String): Boolean =
        runCatching {
            (android.provider.Settings.Secure.getString(context.contentResolver, key) ?: "")
                .contains(needle, ignoreCase = true)
        }.getOrDefault(false)
}
