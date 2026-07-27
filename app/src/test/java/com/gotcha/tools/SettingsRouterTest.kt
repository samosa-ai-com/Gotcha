package com.gotcha.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the routing table itself — the half of `open_setting` that decides
 * *where* to go. Firing the intent needs a device; picking the right one does
 * not, which is the point of keeping the table as data.
 */
class SettingsRouterTest {

    private val routes = SettingsRouter.ROUTES.values

    @Test
    fun everyEntryIsUsable() {
        routes.forEach { entry ->
            assertTrue("${entry.key}: blank action", entry.action.isNotBlank())
            assertTrue("${entry.key}: blank label", entry.label.isNotBlank())
            // The hint is the whole reason a deep link beats a blind search — it
            // tells the navigator where the control is on the screen that opens.
            assertTrue("${entry.key}: blank hint", entry.hint.isNotBlank())
        }
    }

    @Test
    fun keysAreNormalisedLowerSnakeCase() {
        routes.forEach { entry ->
            assertEquals(
                "${entry.key}: keys must be lower_snake_case so open() can normalise input to them",
                entry.key.lowercase().replace(' ', '_').replace('-', '_'),
                entry.key
            )
        }
    }

    @Test
    fun sdkGatedEntriesDeclareAFallback() {
        routes.filter { it.minSdk > 0 }.forEach { entry ->
            assertNotNull(
                "${entry.key}: declares minSdk ${entry.minSdk} but no fallbackAction, so it " +
                    "would fire an action the device does not have",
                entry.fallbackAction
            )
        }
        // …and nothing declares a fallback it can never use.
        routes.filter { it.fallbackAction != null }.forEach { entry ->
            assertTrue("${entry.key}: fallbackAction with no minSdk is dead code", entry.minSdk > 0)
        }
    }

    @Test
    fun securityRelevantScreensAreConfirmFirst() {
        setOf("developer_options", "lock_screen", "vpn", "device_admin").forEach { key ->
            val entry = SettingsRouter.routeFor(key)
            assertNotNull("$key missing from the routing table", entry)
            assertEquals(
                "$key must not be reachable without the user agreeing first",
                Route.CONFIRM_FIRST,
                entry!!.route
            )
        }
    }

    @Test
    fun knownKeysListsEveryEntry() {
        assertEquals(SettingsRouter.ROUTES.size, SettingsRouter.knownKeys().size)
        assertTrue("location" in SettingsRouter.knownKeys())
    }

    @Test
    fun unknownKeyPointsAtNavigateApp() {
        val decision = SettingsRouter.decide("font_size", confirmed = false)
        assertTrue(decision is SettingsRouter.Decision.Refused)
        val message = (decision as SettingsRouter.Decision.Refused).message
        assertTrue(
            "an unlisted setting must degrade to navigate_app, not dead-end: $message",
            message.contains("navigate_app")
        )
    }

    @Test
    fun keysAreMatchedLeniently() {
        // The model writes what it likes; "Airplane Mode" and "airplane-mode"
        // must reach the same row as "airplane_mode".
        listOf("airplane_mode", "Airplane Mode", "airplane-mode", "  AIRPLANE_MODE ").forEach { input ->
            val decision = SettingsRouter.decide(input, confirmed = false)
            assertTrue("'$input' did not resolve", decision is SettingsRouter.Decision.Proceed)
            assertEquals(
                "airplane_mode",
                (decision as SettingsRouter.Decision.Proceed).entry.key
            )
        }
    }

    @Test
    fun confirmFirstRefusesUntilConfirmed() {
        val refused = SettingsRouter.decide("developer_options", confirmed = false)
        assertTrue(refused is SettingsRouter.Decision.Refused)
        val message = (refused as SettingsRouter.Decision.Refused).message
        assertTrue(
            "the refusal must tell the agent how to proceed: $message",
            message.contains("question") && message.contains("confirmed=true")
        )

        val allowed = SettingsRouter.decide("developer_options", confirmed = true)
        assertTrue("confirming must unblock it", allowed is SettingsRouter.Decision.Proceed)
    }

    @Test
    fun ordinaryRoutesDoNotNeedConfirmation() {
        val decision = SettingsRouter.decide("location", confirmed = false)
        assertTrue(decision is SettingsRouter.Decision.Proceed)
    }

    @Test
    fun sdkFallbackIsUsedBelowMinSdk() {
        val gated = routes.first { it.minSdk > 0 && it.fallbackAction != null }
        assertEquals(
            "below minSdk the fallback action must be chosen",
            gated.fallbackAction,
            SettingsRouter.resolveAction(gated, gated.minSdk - 1)
        )
        assertEquals(
            "at or above minSdk the primary action must be chosen",
            gated.action,
            SettingsRouter.resolveAction(gated, gated.minSdk)
        )
    }
}
