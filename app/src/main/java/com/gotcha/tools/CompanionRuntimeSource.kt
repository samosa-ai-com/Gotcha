package com.gotcha.tools

import android.content.Context
import com.gotcha.auth.SamosaAuthApi
import com.gotcha.auth.ShellRuntimeSource
import com.gotcha.data.SettingsRepository
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

    /** How long a failed fetch is remembered, to stay inside the backend's hourly request limit. */
    private const val FAILURE_COOLDOWN_MS = 60_000L

    @Volatile
    private var lastFailureAt = 0L

    /**
     * The source for this account, or null when signed out, offline, the route refused, or a
     * fetch just failed. The cooldown is deliberately here, on the singleton, because a new
     * backend instance is built for every command: an instance field would reset on each call
     * and the limit would never bite.
     *
     * Retrofit's suspend functions dispatch on OkHttp's pool and resume on the caller, so this
     * does not block. It deliberately does not wrap itself in `withContext(Dispatchers.IO)`:
     * that moves the call to a dispatcher a `runTest` cannot control, which advanced another
     * test class's virtual clock.
     */
    suspend fun fetch(context: Context): ShellRuntimeSource? {
        if (System.currentTimeMillis() - lastFailureAt < FAILURE_COOLDOWN_MS) return null
        return try {
            val token = SettingsRepository(context).load().samosaSessionToken
            if (token.isBlank()) return null
            SamosaAuthApi.create().shellRuntime("Bearer $token")
        } catch (e: CancellationException) {
            // Stopping a turn must stay a stop, not become a missing-runtime error.
            throw e
        } catch (_: Exception) {
            lastFailureAt = System.currentTimeMillis()
            null
        }
    }

    /** Forgets a cooldown, so tests do not depend on wall-clock timing. */
    internal fun resetForTest() {
        lastFailureAt = 0L
    }
}
