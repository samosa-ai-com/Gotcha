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

    /**
     * How long a failed attempt suppresses the next fetch. The route allows a handful of
     * requests an hour, so a user retrying a failing command must not burn it in a minute.
     */
    private const val FAILURE_COOLDOWN_MS = 60_000L

    @Volatile
    private var lastFailureAt = 0L

    /**
     * Whose failure that was, as a hash of the session token. A different account signing in
     * within the window must not inherit the previous account's cooldown; only the token's hash
     * is kept, never the token itself.
     */
    @Volatile
    private var lastFailureKey: Int? = null

    /** Whose fetch is currently in flight or most recently attempted; the offer sites report back against it. */
    @Volatile
    private var lastAttemptKey: Int? = null

    /**
     * The source for this account, or null when signed out, cooling down after a failure, offline,
     * the route refused, or the answer was unusable. Every one of those is final for this call:
     * retrying at once would repeat the same answer, so failures are recorded here and the next
     * command asks again only after the cooldown.
     *
     * Retrofit's suspend functions dispatch on OkHttp's pool and resume on the caller, so this
     * does not block. It deliberately does not wrap itself in `withContext(Dispatchers.IO)`:
     * that moves the call to a dispatcher a `runTest` cannot control, which advanced another
     * test class's virtual clock.
     */
    suspend fun fetch(context: Context, baseUrl: String? = null): ShellRuntimeSource? {
        val token = SettingsRepository(context).load().samosaSessionToken
        if (token.isBlank()) return null
        val key = token.hashCode()
        if (lastFailureKey == key && System.currentTimeMillis() - lastFailureAt < FAILURE_COOLDOWN_MS) {
            return null
        }
        lastAttemptKey = key
        val api = if (baseUrl == null) SamosaAuthApi.create() else SamosaAuthApi.create(baseUrl)
        return try {
            val source = api.shellRuntime("Bearer $token")
            if (!source.isUsable()) {
                noteFailure(key)
                null
            } else {
                source
            }
        } catch (e: CancellationException) {
            // Stopping a turn must stay a stop, not become a missing-runtime error.
            throw e
        } catch (_: Exception) {
            noteFailure(key)
            null
        }
    }

    /**
     * Records that a source the companion was offered did not take — it was refused over Binder.
     * Without this, every command would fetch a fresh source only to have the same refusal, and
     * the hourly allowance would go on responses that can never be used. Scoped to the account
     * whose fetch produced the source, like every other failure here.
     */
    fun noteOfferRejected() {
        lastAttemptKey?.let { lastFailureKey = it }
        lastFailureAt = System.currentTimeMillis()
    }

    private fun noteFailure(key: Int) {
        lastFailureKey = key
        lastFailureAt = System.currentTimeMillis()
    }

    /** Forgets failures, attempts and cooldowns, so tests do not depend on wall-clock timing. */
    internal fun resetForTest() {
        lastFailureAt = 0L
        lastFailureKey = null
        lastAttemptKey = null
    }
}
