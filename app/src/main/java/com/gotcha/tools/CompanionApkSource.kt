package com.gotcha.tools

import android.content.Context
import com.gotcha.auth.SamosaAuthApi
import com.gotcha.auth.ShellApkRelease
import com.gotcha.data.SettingsRepository
import kotlinx.coroutines.CancellationException

/**
 * Where the Gotcha Shell companion APK comes from.
 *
 * Gotcha installs the companion itself, so a user only ever installs one app. The route that
 * names the APK is tier-gated and requires a session — Gotcha holds one, so unlike the runtime
 * source it asks directly rather than being pushed an answer over Binder.
 *
 * A refusal here is an answer, not an outage: a free account gets 403 and must not be retried,
 * while a network failure is worth one retry later. The distinction is the same one
 * [CompanionRuntimeSource] draws, and the cooldown is keyed the same way — by a hash of the
 * session token, so one account's cooldown cannot leak into another's.
 */
internal object CompanionApkSource {

    /** The route allows six requests an hour, so a retrying user must not burn it in a minute. */
    private const val FAILURE_COOLDOWN_MS = 60_000L

    @Volatile
    private var lastFailureAt = 0L

    @Volatile
    private var lastFailureKey: Int? = null

    /** A fetched release bound to the account it was fetched for. */
    internal class ResolvedRelease(val release: ShellApkRelease, private val key: Int) {
        fun noteRejected() = noteFailure(key)
    }

    suspend fun fetch(context: Context, baseUrl: String? = null): ShellApkRelease? =
        fetchResolved(context, baseUrl)?.release

    /**
     * Like [fetch], keeping the account binding so a later refusal is recorded against the right
     * account even if another fetch happened since.
     */
    suspend fun fetchResolved(context: Context, baseUrl: String? = null): ResolvedRelease? {
        val token = SettingsRepository(context).load().samosaSessionToken
        if (token.isBlank()) return null
        val key = token.hashCode()
        if (lastFailureKey == key && System.currentTimeMillis() - lastFailureAt < FAILURE_COOLDOWN_MS) {
            return null
        }
        val api = if (baseUrl == null) SamosaAuthApi.create() else SamosaAuthApi.create(baseUrl)
        return try {
            val release = api.shellApkRelease("Bearer $token")
            // A well-formed but unusable answer is a refusal, not a transient fault: retrying
            // spends another hourly request to be told the same thing.
            if (!release.isUsable()) {
                noteFailure(key)
                null
            } else {
                ResolvedRelease(release, key)
            }
        } catch (e: CancellationException) {
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

    /** Whether *key* is inside its post-failure window. Exposed so the binding is testable. */
    internal fun isCoolingDownFor(key: Int): Boolean =
        lastFailureKey == key && System.currentTimeMillis() - lastFailureAt < FAILURE_COOLDOWN_MS

    /** Forgets failures and cooldowns, so tests do not depend on wall-clock timing. */
    internal fun resetForTest() {
        lastFailureAt = 0L
        lastFailureKey = null
    }
}
