package com.gotcha.tools

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * How a settings destination is reached. The tier is dictated by what Android
 * permits a non-privileged app to do, not by preference — see the tier model in
 * `AGENTS.md`.
 *
 * Tier 1 (change it silently through an API) is not represented here: those are
 * ordinary tools on [DeviceTool] / [SystemTool]. This router owns tiers 2 and 3,
 * and hands anything it does not know back to tier 4 (`navigate_app`).
 */
enum class Route {
    /** A `Settings.Panel.*` sheet: slides up over the caller, one tap, no navigation. */
    PANEL,

    /** An `ACTION_*_SETTINGS` deep link: lands on the exact screen, then 1-2 taps. */
    DEEPLINK,

    /**
     * A deep link the agent must not follow without asking first.
     *
     * This is a guard against *accidental* silent changes and against injected
     * ones — the app reads screen text, notifications and email into the model's
     * context, all of which an attacker can influence. It is deliberately **not**
     * a security boundary: `run_root_command`, `write_secure_settings` and
     * `navigate_app` all reach the same screens by other routes.
     */
    CONFIRM_FIRST
}

/**
 * One row of the routing table.
 *
 * @param action the intent action to fire on a device new enough for it.
 * @param minSdk the API level [action] was introduced at; below this,
 *   [fallbackAction] is used instead.
 * @param hint where the control lives on the screen that opens. Returned to the
 *   agent so the navigator starts from a known position rather than re-deriving
 *   it from a screen read.
 */
data class SettingRoute(
    val key: String,
    val route: Route,
    val action: String,
    val label: String,
    val hint: String,
    val minSdk: Int = 0,
    val fallbackAction: String? = null
)

/**
 * Opens Android settings screens the app is not allowed to change silently.
 *
 * The table is a closed list on purpose: an unknown key returns an error naming
 * `navigate_app`, so a miss degrades to the old search-and-scroll behaviour
 * rather than dead-ending. Expect to add entries over time — that maintenance
 * cost was accepted when this replaced blind navigation as the default path.
 */
class SettingsRouter(private val context: Context) {

    fun open(key: String, confirmed: Boolean): ToolResult {
        val entry = when (val decision = decide(key, confirmed)) {
            is Decision.Refused -> return ToolResult.error(decision.message)
            is Decision.Proceed -> decision.entry
        }

        val action = resolveAction(entry, Build.VERSION.SDK_INT)
        return try {
            context.startActivity(
                Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
            ToolResult.ok("Opened ${entry.label}. ${entry.hint}")
        } catch (e: Exception) {
            // Not every OEM ships an activity for every documented action.
            ToolResult.error(
                "Could not open ${entry.label} directly (${e.message}). Fall back to navigate_app: " +
                    "open the Settings app and search for it there."
            )
        }
    }

    /** Whether a request may proceed, and why not when it may not. */
    sealed interface Decision {
        data class Proceed(val entry: SettingRoute) : Decision
        data class Refused(val message: String) : Decision
    }

    companion object {

        fun knownKeys(): List<String> = ROUTES.keys.sorted()

        /** Exposed for tests; the router itself goes through [ROUTES]. */
        internal fun routeFor(key: String): SettingRoute? = ROUTES[key]

        /**
         * The whole routing decision, with no [Context] involved — everything up
         * to the point where an intent is actually fired. Kept separate so it is
         * testable on the JVM: the table, the confirmation gate and the
         * unknown-key fallback are where the bugs would be, not `startActivity`.
         */
        internal fun decide(key: String, confirmed: Boolean): Decision {
            val normalized = key.lowercase().trim().replace(' ', '_').replace('-', '_')
            val entry = ROUTES[normalized] ?: return Decision.Refused(
                "No direct route for '$key'. Known settings: ${knownKeys().joinToString(", ")}. " +
                    "For anything else, use navigate_app to open the Settings app and search for it."
            )
            if (entry.route == Route.CONFIRM_FIRST && !confirmed) {
                return Decision.Refused(
                    "${entry.label} affects device security or how the phone is controlled, so it " +
                        "needs the user's explicit go-ahead. Ask them with the 'question' tool, " +
                        "then call open_setting again with confirmed=true if they agree."
                )
            }
            return Decision.Proceed(entry)
        }

        /** The action to fire at [sdkInt], honouring [SettingRoute.minSdk]. */
        internal fun resolveAction(entry: SettingRoute, sdkInt: Int): String =
            if (sdkInt < entry.minSdk && entry.fallbackAction != null) {
                entry.fallbackAction
            } else {
                entry.action
            }

        internal val ROUTES: Map<String, SettingRoute> = listOf(
            // ---- Tier 2: Settings Panels (slide-up sheet, no navigation) ----
            SettingRoute(
                key = "wifi",
                route = Route.PANEL,
                action = Settings.Panel.ACTION_WIFI,
                label = "the Wi-Fi panel",
                hint = "It lists nearby networks with a Wi-Fi toggle at the top."
            ),
            SettingRoute(
                key = "internet",
                route = Route.PANEL,
                action = Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
                label = "the internet connectivity panel",
                hint = "It has both the Wi-Fi and mobile-data toggles.",
                minSdk = Build.VERSION_CODES.Q,
                fallbackAction = Settings.Panel.ACTION_WIFI
            ),
            SettingRoute(
                key = "mobile_data",
                route = Route.PANEL,
                action = Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
                label = "the internet connectivity panel",
                hint = "Mobile data is the second toggle, under Wi-Fi.",
                minSdk = Build.VERSION_CODES.Q,
                fallbackAction = Settings.ACTION_DATA_ROAMING_SETTINGS
            ),
            SettingRoute(
                key = "nfc",
                route = Route.PANEL,
                action = Settings.Panel.ACTION_NFC,
                label = "the NFC panel",
                hint = "A single NFC toggle."
            ),

            // ---- Tier 3: deep links (exact screen, then 1-2 taps) ----
            SettingRoute(
                key = "bluetooth",
                route = Route.DEEPLINK,
                action = Settings.ACTION_BLUETOOTH_SETTINGS,
                label = "Bluetooth settings",
                hint = "The master toggle is at the top; paired devices are listed below it."
            ),
            SettingRoute(
                key = "location",
                route = Route.DEEPLINK,
                action = Settings.ACTION_LOCATION_SOURCE_SETTINGS,
                label = "Location settings",
                hint = "The master 'Use location' toggle is at the top."
            ),
            SettingRoute(
                key = "airplane_mode",
                route = Route.DEEPLINK,
                action = Settings.ACTION_AIRPLANE_MODE_SETTINGS,
                label = "Airplane mode settings",
                hint = "The airplane-mode toggle is in the network list on this screen."
            ),
            SettingRoute(
                key = "battery_saver",
                route = Route.DEEPLINK,
                action = Settings.ACTION_BATTERY_SAVER_SETTINGS,
                label = "Battery saver settings",
                hint = "'Use Battery Saver' is the main toggle."
            ),
            SettingRoute(
                key = "display",
                route = Route.DEEPLINK,
                action = Settings.ACTION_DISPLAY_SETTINGS,
                label = "Display settings",
                hint = "Brightness, dark theme, font size and screen timeout live here."
            ),
            SettingRoute(
                key = "sound",
                route = Route.DEEPLINK,
                action = Settings.ACTION_SOUND_SETTINGS,
                label = "Sound settings",
                hint = "Volume sliders, ringtone and Do Not Disturb live here."
            ),
            // No "notifications" entry on purpose: ACTION_NOTIFICATION_SETTINGS is
            // @hide in AOSP, and the public ACTION_APP_NOTIFICATION_SETTINGS needs
            // an EXTRA_APP_PACKAGE, so it cannot open the device-wide screen. That
            // makes notifications a genuine tier-4 case — navigate_app handles it.
            SettingRoute(
                key = "date_time",
                route = Route.DEEPLINK,
                action = Settings.ACTION_DATE_SETTINGS,
                label = "Date & time settings",
                hint = "Turn off 'Set time automatically' before the manual fields become editable."
            ),
            SettingRoute(
                key = "language",
                route = Route.DEEPLINK,
                action = Settings.ACTION_LOCALE_SETTINGS,
                label = "Language settings",
                hint = "'Add a language' is at the bottom; drag a language to the top to make it primary."
            ),
            SettingRoute(
                key = "input_method",
                route = Route.DEEPLINK,
                action = Settings.ACTION_INPUT_METHOD_SETTINGS,
                label = "Keyboard settings",
                hint = "Installed keyboards are listed with per-keyboard toggles."
            ),
            SettingRoute(
                key = "storage",
                route = Route.DEEPLINK,
                action = Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
                label = "Storage settings",
                hint = "Usage by category, with a 'Free up space' action."
            ),
            SettingRoute(
                key = "accessibility",
                route = Route.DEEPLINK,
                action = Settings.ACTION_ACCESSIBILITY_SETTINGS,
                label = "Accessibility settings",
                hint = "Installed services are under 'Downloaded apps' or 'Installed services'."
            ),
            SettingRoute(
                key = "default_apps",
                route = Route.DEEPLINK,
                action = Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
                label = "Default apps settings",
                hint = "One row per role (browser, phone, SMS, assistant).",
                minSdk = Build.VERSION_CODES.N,
                fallbackAction = Settings.ACTION_SETTINGS
            ),
            SettingRoute(
                key = "cast",
                route = Route.DEEPLINK,
                action = Settings.ACTION_CAST_SETTINGS,
                label = "Cast settings",
                hint = "Nearby cast targets are listed; tap one to connect."
            ),

            // ---- Security-relevant: always confirm with the user first ----
            SettingRoute(
                key = "developer_options",
                route = Route.CONFIRM_FIRST,
                action = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                label = "Developer options",
                hint = "The master toggle is at the top. Many switches here weaken device safety."
            ),
            SettingRoute(
                key = "lock_screen",
                route = Route.CONFIRM_FIRST,
                action = Settings.ACTION_SECURITY_SETTINGS,
                label = "Security & lock screen settings",
                hint = "'Screen lock' is near the top. Changing it may require the current PIN."
            ),
            SettingRoute(
                key = "vpn",
                route = Route.CONFIRM_FIRST,
                action = Settings.ACTION_VPN_SETTINGS,
                label = "VPN settings",
                hint = "Configured VPNs are listed; a VPN can see all network traffic.",
                minSdk = Build.VERSION_CODES.N,
                fallbackAction = Settings.ACTION_SETTINGS
            ),
            SettingRoute(
                key = "device_admin",
                route = Route.CONFIRM_FIRST,
                action = Settings.ACTION_SECURITY_SETTINGS,
                label = "Device admin settings",
                hint = "'Device admin apps' is under the advanced section. These apps can wipe the device."
            )
        ).associateBy { it.key }
    }
}
