package com.gotcha.tools

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.gotcha.service.GotchaVpnService

/**
 * Tier 3 — controls the [GotchaVpnService] local kill-switch firewall.
 *
 * Enabling needs the one-time system VPN consent: [VpnService.prepare] returns a consent
 * Intent when the user has not authorized a VPN for this app. In that case the tool returns
 * the [ToolResult.VPN_CONSENT] marker so `MainActivity` can launch the consent dialog, matching
 * the "grant, then ask again" flow used by the other special-access tools. Once consent is on
 * record, `prepare` returns null and the tool starts/stops the service.
 */
class VpnTool(private val context: Context) {

    /** Turn the traffic-blocking VPN on ([enabled] = true) or off (false). */
    fun setFirewall(enabled: Boolean): ToolResult {
        if (!enabled) {
            if (!GotchaVpnService.isRunning) return ToolResult.ok("The network firewall is already off.")
            context.startService(stopIntent())
            return ToolResult.ok("Network firewall disabled — traffic can flow normally again.")
        }

        // Enabling: check we hold VPN consent first.
        val consent = VpnService.prepare(context)
        if (consent != null) {
            return ToolResult.permissionNeeded(
                ToolResult.VPN_CONSENT,
                "Blocking network traffic needs your one-time VPN permission. I have opened the " +
                    "system consent dialog — please allow it for Gotcha and ask again."
            )
        }
        context.startService(startIntent())
        return ToolResult.ok(
            "Network firewall enabled — a local VPN is now blocking ALL device network traffic " +
                "(nothing is inspected or sent anywhere). Ask me to disable it to restore connectivity."
        )
    }

    /** Report whether the blocking VPN is currently up. */
    fun getFirewallStatus(): ToolResult = ToolResult.ok(
        if (GotchaVpnService.isRunning) {
            "The network firewall is ON — all traffic is blocked."
        } else {
            "The network firewall is OFF — traffic flows normally."
        }
    )

    private fun startIntent() =
        Intent(context, GotchaVpnService::class.java).setAction(GotchaVpnService.ACTION_START)

    private fun stopIntent() =
        Intent(context, GotchaVpnService::class.java).setAction(GotchaVpnService.ACTION_STOP)
}
