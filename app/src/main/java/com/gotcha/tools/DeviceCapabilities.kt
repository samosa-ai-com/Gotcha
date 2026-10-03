package com.gotcha.tools

import android.content.Context
import android.text.TextUtils
import com.gotcha.service.GotchaAccessibilityService
import java.io.File

/**
 * What the accessibility service can actually do right now.
 *
 * Two states would not be enough. "Listed in the settings" and "bound and
 * usable" come apart routinely — the OS unbinds the service under memory
 * pressure, across an app update, and when the master switch goes off while the
 * component stays listed — and the advice differs: [OFF] means enable it,
 * [ENABLED_BUT_NOT_BOUND] means toggle it off and on. Collapsing them is what
 * made a disabled setting surface as an unrelated failure (issue #76).
 */
enum class AccessibilityState {
    /** Bound and serving calls. */
    AVAILABLE,

    /** The user switched it on, but no live service is bound to talk to. */
    ENABLED_BUT_NOT_BOUND,

    /** Not switched on. */
    OFF
}

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
        // Gated on the service being *bound*, not on the setting listing it.
        // A listed-but-unbound service cannot serve a single call, and offering
        // the tools anyway is what produced the "reached N steps without
        // completing the task" dead end in AppNavigatorSession.
        if (accessibilityState(context) == AccessibilityState.AVAILABLE) add(Capability.ACCESSIBILITY)
        if (notificationListenerEnabled(context)) add(Capability.NOTIFICATION_LISTENER)
        if (deviceAdminActive(context)) add(Capability.DEVICE_ADMIN)
        if (rootAvailable()) add(Capability.ROOT)
        if (termuxUsable(context)) add(Capability.TERMUX)
        if (healthConnectPresent(context)) add(Capability.HEALTH_CONNECT)
        if (overlayAllowed(context)) add(Capability.OVERLAY)
    }

    /** Tools to withhold from the model right now. */
    fun hiddenToolNames(context: Context): Set<String> =
        CapabilityCatalog.hiddenTools(available(context))

    /**
     * The one probe for accessibility. Everything else — tool gating, the `<env>`
     * line, the permissions screen, the feature tour — routes here, because three
     * copies of this string comparison drifted into three different answers.
     *
     * A bound service is authoritative: if calls are being served, no reading of
     * the settings string can make that false.
     */
    fun accessibilityState(context: Context): AccessibilityState = when {
        GotchaAccessibilityService.instance != null -> AccessibilityState.AVAILABLE
        accessibilityEnabled(context) -> AccessibilityState.ENABLED_BUT_NOT_BOUND
        else -> AccessibilityState.OFF
    }

    /**
     * Whether the user has switched the service on — which is not the same as it
     * being usable; see [accessibilityState].
     *
     * Both halves matter. `ENABLED_ACCESSIBILITY_SERVICES` keeps listing the
     * component after the master switch goes off, so reading only the list
     * reports a service that the framework has already stopped.
     */
    fun accessibilityEnabled(context: Context): Boolean =
        accessibilityMasterSwitchOn(context) && accessibilityServiceListed(context)

    private fun accessibilityMasterSwitchOn(context: Context): Boolean = runCatching {
        android.provider.Settings.Secure.getInt(
            context.contentResolver,
            android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1
    }.getOrDefault(false)

    /**
     * Whether our component appears in the enabled-services list.
     *
     * Parsed rather than substring-matched, and tolerant of both spellings: the
     * Settings UI writes the flattened long form, but a backup restore, an `adb
     * settings put`, and some OEM ROMs write the short form
     * (`com.gotcha/.service.GotchaAccessibilityService`). Matching only the long
     * form reported "off" on a device where the service was bound and working.
     */
    private fun accessibilityServiceListed(context: Context): Boolean = runCatching {
        val listed = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val pkg = context.packageName
        val cls = GotchaAccessibilityService::class.java.name
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(listed)
        splitter.any { entry ->
            val trimmed = entry.trim()
            trimmed.equals("$pkg/$cls", ignoreCase = true) ||
                trimmed.equals("$pkg/.${cls.substringAfter("$pkg.")}", ignoreCase = true)
        }
    }.getOrDefault(false)

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
     * Whether Termux is installed *and* exposes the RUN_COMMAND plugin API — deliberately not
     * whether the permission is granted, for the same reason [healthConnectPresent] ignores its
     * grants: the permission is requested on demand by [TermuxTool], so gating on it would hide
     * the only tool that can ever raise the prompt. The plugin API has no such cycle; it is a
     * fixed property of which Termux build is installed, and the Google Play build ships without
     * it, so gating on it keeps the tool out of the model's hands on devices where no grant could
     * ever make it work. Needs `<package android:name="com.termux"/>` in the manifest's
     * `<queries>` block to see past targetSdk 30+ package visibility.
     */
    fun termuxUsable(context: Context): Boolean = TermuxTool(context).status().usable

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
