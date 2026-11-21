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
     * requests an hour, so a retrying user must not burn it in a minute.
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

    /**
     * A fetched source bound to the account it was fetched for. The key travels with the source
     * so a later Binder refusal is recorded against the right account even if another fetch has
     * happened since: a shared "most recent attempt" slot could not promise that.
     */
    internal class ResolvedSource(val source: ShellRuntimeSource, private val key: Int) {
        /** Records that the companion refused this source. See [noteFailure]. */
        fun noteRejected() = noteFailure(key)
    }

    /** The source for this account, or null when signed out, cooling down, offline, refused or unusable. */
    suspend fun fetch(context: Context, baseUrl: String? = null): ShellRuntimeSource? =
        fetchResolved(context, baseUrl)?.source

    /**
     * Like [fetch], but keeps the account binding so the caller can report a refused offer.
     * A failure anywhere in the attempt — network, unusable answer, refused offer — is recorded
     * under the fetching account's key, and the next fetch for that account waits out the
     * cooldown instead of spending another hourly request on the same answer.
     *
     * Retrofit's suspend functions dispatch on OkHttp's pool and resume on the caller, so this
     * does not block. It deliberately does not wrap itself in `withContext(Dispatchers.IO)`:
     * that moves the call to a dispatcher a `runTest` cannot control, which advanced another
     * test class's virtual clock.
     */
    suspend fun fetchResolved(context: Context, baseUrl: String? = null): ResolvedSource? {
        val token = SettingsRepository(context).load().samosaSessionToken
        if (token.isBlank()) return null
        val key = token.hashCode()
        if (lastFailureKey == key && System.currentTimeMillis() - lastFailureAt < FAILURE_COOLDOWN_MS) {
            return null
        }
        val api = if (baseUrl == null) SamosaAuthApi.create() else SamosaAuthApi.create(baseUrl)
        return try {
            val source = api.shellRuntime("Bearer $token")
            if (!source.isUsable()) {
                noteFailure(key)
                null
            } else {
                ResolvedSource(source, key)
            }
        } catch (e: CancellationException) {
            // Stopping a turn must stay a stop, not become a missing-runtime error.
            throw e
        } catch (_: Exception) {
            noteFailure(key)
            null
        }
    }

    internal fun noteFailure(key: Int) {
        lastFailureKey = key
        lastFailureAt = System.currentTimeMillis()
    }

    /** Forgets failures and cooldowns, so tests do not depend on wall-clock timing. */
    internal fun resetForTest() {
        lastFailureAt = 0L
        lastFailureKey = null
    }
}
