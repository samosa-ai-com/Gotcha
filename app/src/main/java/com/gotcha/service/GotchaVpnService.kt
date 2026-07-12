package com.gotcha.service

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream

/**
 * Tier 3 — VpnService.
 *
 * A **local kill-switch firewall**: it establishes a tun interface that routes *all*
 * device traffic into this app and then discards every packet, so nothing reaches the
 * network. There is no remote server and no packet inspection — traffic is simply
 * black-holed while the tunnel is up, which is the classic no-server VpnService use
 * (an on-device "block all internet" switch).
 *
 * Unlike the other Tier 3 components, the enable step is gated by the system VPN consent
 * dialog rather than a Settings toggle: [android.net.VpnService.prepare] returns a consent
 * Intent the UI must launch once. After the user consents, [VpnTool] starts this service.
 *
 * The service exposes a static [isRunning] flag so the stateless [com.gotcha.tools.VpnTool]
 * can report status without holding a binding.
 */
class GotchaVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                teardown()
                stopSelf()
                START_NOT_STICKY
            }
            else -> {
                establish()
                START_STICKY
            }
        }
    }

    /** Bring up the tun interface routing everything into this app (then drop it). */
    private fun establish() {
        if (vpnInterface != null) return
        val builder = Builder()
            .setSession("Gotcha Firewall")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
        // Also swallow IPv6 where the device supports it, so v6 traffic can't leak past
        // the block. Guarded because some devices reject an IPv6 config outright.
        try {
            builder.addAddress("fd00::2", 128).addRoute("::", 0)
        } catch (_: Exception) {
            // IPv6 not available; IPv4 block still applies.
        }

        vpnInterface = try {
            builder.establish()
        } catch (_: Exception) {
            null
        }
        isRunning = vpnInterface != null

        // Drain and discard packets so the tun buffer doesn't back up. Reading blocks
        // until a packet arrives; we never write anything back, so nothing is forwarded.
        vpnInterface?.let { pfd ->
            worker = Thread {
                val input = FileInputStream(pfd.fileDescriptor)
                val buffer = ByteArray(MAX_PACKET)
                try {
                    while (!Thread.interrupted()) {
                        if (input.read(buffer) < 0) break
                    }
                } catch (_: Exception) {
                    // Interface closed on teardown; exit the loop.
                }
            }.apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun teardown() {
        worker?.interrupt()
        worker = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
            // Already closed.
        }
        vpnInterface = null
        isRunning = false
    }

    /** The user revoked the VPN from system settings / another VPN took over. */
    override fun onRevoke() {
        teardown()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.gotcha.vpn.START"
        const val ACTION_STOP = "com.gotcha.vpn.STOP"

        private const val MAX_PACKET = 32_767

        /** True while the blocking tunnel is established; false otherwise. */
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
