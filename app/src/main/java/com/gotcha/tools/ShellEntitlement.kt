package com.gotcha.tools

import android.content.Context
import com.gotcha.BuildConfig
import com.gotcha.auth.SamosaAuthApi
import com.gotcha.data.SettingsRepository
import com.gotcha.util.GotchaLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException

/**
 * Whether this Gotcha may use Gotcha Shell. The shell is for Samosa AI accounts on a tier that
 * includes it ([SHELL_TIERS]), whichever chat provider is in use.
 *
 * The check lives here, in Gotcha: Gotcha Shell itself answers any correctly signed Gotcha. The
 * tier is asked of the backend, remembered, and asked again once a day. The shell keeps working
 * offline on the remembered answer for [OFFLINE_WINDOW_MILLIS]; a "not Pro" answer, or signing
 * out, turns it off at once.
 */
object ShellEntitlement {

    /** [reason] is the state in words the model can pass on to the user. */
    enum class Access(val reason: String) {
        ALLOWED("available"),

        /** No Samosa AI session on this phone. */
        SIGNED_OUT("the Linux shell is part of Samosa AI Pro and the user is not signed in to Samosa AI"),

        /** The backend said the account's tier does not include the shell. */
        NOT_PRO("the Linux shell is part of Samosa AI Pro and this account is on another plan"),

        /** Never asked, or the last "Pro" answer is too old: the backend has to be reached. */
        NEEDS_CHECK("Gotcha could not confirm the Samosa AI Pro plan recently; it needs an internet connection")
    }

    /** What the backend said about the signed-in account's tier. */
    sealed interface TierAnswer {
        data class Tier(val id: String) : TierAnswer
        data object SignedOut : TierAnswer

        /** No answer: offline, a timeout, a server error. */
        data object Unavailable : TierAnswer
    }

    fun interface TierSource {
        suspend fun fetch(): TierAnswer
    }

    /** The remembered answer. [state] is null when nothing is remembered. */
    internal data class Record(val state: String?, val confirmedAt: Long, val checkedAt: Long)

    /** Tiers that include the shell: Pro, and Influencer, which carries Pro's features. */
    val SHELL_TIERS = setOf("pro", "influencer")
    const val OFFLINE_WINDOW_MILLIS = 72L * 60 * 60 * 1000
    const val RECHECK_AFTER_MILLIS = 24L * 60 * 60 * 1000

    private const val TAG = "ShellEntitlement"
    private const val PREFS = "companion_shell"
    private const val KEY_STATE = "access_state"
    private const val KEY_CONFIRMED_AT = "access_confirmed_at"
    private const val KEY_CHECKED_AT = "access_checked_at"
    private const val STATE_PRO = "pro"
    private const val STATE_NOT_PRO = "not_pro"
    private const val STATE_SIGNED_OUT = "signed_out"

    /** A clock a little behind the last confirmation is drift, not a clock set back. */
    private const val CLOCK_SKEW_MILLIS = 5L * 60 * 1000
    private const val FETCH_TIMEOUT_MILLIS = 8_000L
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403

    private val refreshLock = Mutex()

    /**
     * The remembered answer, read from disk only: safe on any thread and on every tool call.
     *
     * Debug builds are always allowed. A debug Gotcha can only reach a debug Gotcha Shell, which
     * exists on developers' machines, and a public checkout has no backend to ask.
     */
    fun access(context: Context, now: Long = System.currentTimeMillis()): Access =
        if (BuildConfig.DEBUG) Access.ALLOWED else remembered(context, now)

    /** [access] without the debug-build exemption. */
    internal fun remembered(context: Context, now: Long = System.currentTimeMillis()): Access =
        evaluate(read(context), now)

    /** When the remembered "Pro" answer stops counting, or null when access is not allowed. */
    fun allowedUntil(context: Context, now: Long = System.currentTimeMillis()): Long? {
        val record = read(context)
        return if (evaluate(record, now) == Access.ALLOWED) record.confirmedAt + OFFLINE_WINDOW_MILLIS else null
    }

    internal fun evaluate(record: Record, now: Long): Access = when (record.state) {
        STATE_SIGNED_OUT -> Access.SIGNED_OUT
        STATE_NOT_PRO -> Access.NOT_PRO
        STATE_PRO -> {
            val age = now - record.confirmedAt
            // A clock set back would stretch the offline window for ever, so it asks again.
            if (age in -CLOCK_SKEW_MILLIS..OFFLINE_WINDOW_MILLIS) Access.ALLOWED else Access.NEEDS_CHECK
        }
        else -> Access.NEEDS_CHECK
    }

    /** True when the backend should be asked now. */
    internal fun isDue(record: Record, now: Long): Boolean =
        record.state == null || now - record.checkedAt !in 0 until RECHECK_AFTER_MILLIS

    /**
     * Asks the backend when a check is due, or always with [force], and returns the resulting
     * access. Without an answer the remembered one stands.
     */
    suspend fun refresh(
        context: Context,
        force: Boolean = false,
        source: TierSource = BackendTierSource(context),
        now: () -> Long = System::currentTimeMillis
    ): Access = refreshLock.withLock {
        val app = context.applicationContext ?: context
        if (force || isDue(read(app), now())) {
            when (val answer = source.fetch()) {
                is TierAnswer.Tier ->
                    write(app, if (answer.id in SHELL_TIERS) STATE_PRO else STATE_NOT_PRO, now())
                TierAnswer.SignedOut -> write(app, STATE_SIGNED_OUT, now())
                TierAnswer.Unavailable -> Unit
            }
        }
        access(app, now())
    }

    /** A new sign-in: whatever was remembered belonged to another session. */
    fun onSignedIn(context: Context) {
        prefs(context).edit().remove(KEY_STATE).remove(KEY_CONFIRMED_AT).remove(KEY_CHECKED_AT).apply()
    }

    /** Signing out turns the shell off without waiting for the next check. */
    fun onSignedOut(context: Context) {
        write(context, STATE_SIGNED_OUT, System.currentTimeMillis())
    }

    private fun write(context: Context, state: String, now: Long) {
        val edit = prefs(context).edit().putString(KEY_STATE, state).putLong(KEY_CHECKED_AT, now)
        if (state == STATE_PRO) edit.putLong(KEY_CONFIRMED_AT, now) else edit.remove(KEY_CONFIRMED_AT)
        edit.apply()
    }

    private fun read(context: Context): Record {
        val prefs = prefs(context)
        return Record(
            state = prefs.getString(KEY_STATE, null),
            confirmedAt = prefs.getLong(KEY_CONFIRMED_AT, 0L),
            checkedAt = prefs.getLong(KEY_CHECKED_AT, 0L)
        )
    }

    private fun prefs(context: Context) =
        (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Reads the tier from `GET /me` with the stored Samosa session. */
    private class BackendTierSource(private val context: Context) : TierSource {
        override suspend fun fetch(): TierAnswer = withContext(Dispatchers.IO) {
            val token = SettingsRepository(context).load().samosaSessionToken
            if (token.isBlank()) return@withContext TierAnswer.SignedOut
            try {
                withTimeoutOrNull(FETCH_TIMEOUT_MILLIS) {
                    TierAnswer.Tier(SamosaAuthApi.create().me("Bearer $token").user.tier.id)
                } ?: TierAnswer.Unavailable
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                // The session is dead, or the account is banned or deactivated.
                if (e.code() == HTTP_UNAUTHORIZED || e.code() == HTTP_FORBIDDEN) {
                    TierAnswer.SignedOut
                } else {
                    TierAnswer.Unavailable
                }
            } catch (e: Exception) {
                GotchaLog.d(TAG, e) { "Tier check failed (ignored)" }
                TierAnswer.Unavailable
            }
        }
    }
}
