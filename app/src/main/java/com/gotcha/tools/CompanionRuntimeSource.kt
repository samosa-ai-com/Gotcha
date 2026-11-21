package com.gotcha.tools

import com.gotcha.auth.SamosaAuthApi
import com.gotcha.auth.ShellRuntimeSource
import kotlinx.coroutines.CancellationException

/**
 * Where the Gotcha Shell runtime bundle can be fetched from.
 *
 * The runtime is not shipped in the companion's APK: it is GPL-2.0, and distributing its bytes
 * would oblige the companion to hand over the source of what it sells. It is downloaded on first
 * use instead, and the route that names it is tier-gated, so the companion cannot ask for it —
 * it holds no session. Gotcha asks, with the account's session, and pushes the answer over Binder.
 *
 * One place, because two callers need it: a command (`CompanionShellBackend`) and the Linux
 * system's own install and repair (`CompanionShellManager`), which both end up in the
 * companion's `ensureRuntime()`.
 */
internal object CompanionRuntimeSource {

    /**
     * The source for this account, or null when signed out, offline, or the route refused.
     *
     * Retrofit's suspend functions dispatch on OkHttp's pool and resume on the caller, so this
     * does not block. It deliberately does not wrap itself in `withContext(Dispatchers.IO)`:
     * that moves the call to a dispatcher a `runTest` cannot control, which advanced another
     * test class's virtual clock.
     */
    suspend fun fetch(): ShellRuntimeSource? = try {
        SamosaAuthApi.create().shellRuntime()
    } catch (e: CancellationException) {
        // Stopping a turn must stay a stop, not become a missing-runtime error.
        throw e
    } catch (_: Exception) {
        null
    }
}
