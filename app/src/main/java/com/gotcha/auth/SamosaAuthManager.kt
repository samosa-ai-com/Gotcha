package com.gotcha.auth

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.gotcha.BuildConfig
import com.gotcha.R
import com.gotcha.connectors.oauth.Pkce
import com.gotcha.data.SettingsRepository
import com.gotcha.util.GotchaLog
import com.gotcha.util.HumanReadableError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID

/** Outcome of a Samosa sign-in attempt, surfaced to the UI. */
sealed interface SamosaSignInResult {
    data class Success(
        val email: String,
        val user: SamosaUser? = null,
        val isNewUser: Boolean = false
    ) : SamosaSignInResult

    data class Error(val message: String) : SamosaSignInResult

    /** User closed the account chooser or the sign-in page: not a real failure. */
    data object Cancelled : SamosaSignInResult
}

/**
 * A website sign-in waiting for the browser to come back. Kept on disk so it
 * survives Android stopping Gotcha while the browser is open.
 */
data class PendingSamosaSignIn(
    val verifier: String,
    val state: String,
    val startedAtMillis: Long
)

/** What [SamosaAuthManager.handleRedirect] did with a link that opened Gotcha. */
sealed interface SamosaRedirectOutcome {
    /** Not the sign-in link. The caller handles the intent as usual. */
    data object NotSignIn : SamosaRedirectOutcome

    /** A sign-in link with no sign-in waiting for it (old or replayed). Nothing happened. */
    data object Ignored : SamosaRedirectOutcome

    /** Handed to the [SamosaAuthManager.signIn] call waiting for it, which reports it. */
    data object Delivered : SamosaRedirectOutcome

    /**
     * Finished with nobody waiting: Gotcha was restarted while the browser was
     * open. The caller shows [result] itself.
     */
    data class Finished(val result: SamosaSignInResult) : SamosaRedirectOutcome
}

/**
 * Signs in to Samosa AI accounts, two ways:
 *
 *  - **Account website** (when [websiteSignIn] is on, the default): opens the
 *    Samosa account website in a Custom Tab. The user signs in there and the
 *    website returns to `com.gotcha:/oauth2redirect` with a one-time code,
 *    which is exchanged at POST /oauth/token together with a PKCE verifier
 *    that never leaves the phone.
 *  - **Google** (the fallback): a Google ID token from Credential Manager,
 *    exchanged at POST /register. The ID token is never stored.
 *
 * Either way the session goes to [SettingsRepository.saveSamosaSession], and
 * [SamosaSession] keeps it renewed from then on. Never touches
 * OpenAI-compatible settings.
 */
@Suppress("TooManyFunctions") // sign-in, sign-out, profile and referrals share one owner
class SamosaAuthManager(
    private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val api: SamosaAuthApi = SamosaAuthApi.create(),
    /** True: the account website is the main sign-in, with Google as the fallback. */
    val websiteSignIn: Boolean = BuildConfig.SAMOSA_DASHBOARD_SIGN_IN,
    private val browser: SamosaBrowser = CustomTabBrowser,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val credentialManager by lazy { CredentialManager.create(appContext) }

    private val lock = Any()

    /** The website sign-in this instance is waiting on, if any. */
    private var waiter: Waiter? = null

    private class Waiter(val result: CompletableDeferred<SamosaSignInResult>) {
        /** Gotcha went to the background after the browser opened. */
        @Volatile var leftForBrowser = false

        /** The browser came back with the sign-in link. */
        @Volatile var redirectArrived = false
    }

    private val sessionChangeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Fires when the session changed outside a screen's own sign-in or
     * sign-out: a sign-in finished after a restart, or the session ended
     * (signed out elsewhere, expired, account disabled). Screens re-read it.
     */
    val sessionChanges: SharedFlow<Unit> = sessionChangeEvents.asSharedFlow()

    fun notifySessionChanged() {
        sessionChangeEvents.tryEmit(Unit)
    }

    /** The main sign-in: the account website, or Google when that is turned off. */
    suspend fun signIn(activityContext: Context): SamosaSignInResult =
        if (websiteSignIn) signInWithWebsite(activityContext) else signInWithGoogle(activityContext)

    /**
     * Runs the full Google Sign-In → /register → store flow. Must be called with
     * an Activity [activityContext] so Credential Manager can show the account chooser.
     */
    suspend fun signInWithGoogle(activityContext: Context): SamosaSignInResult {
        val idToken = try {
            requestGoogleIdToken(activityContext)
        } catch (e: GetCredentialCancellationException) {
            GotchaLog.d(TAG, e) { "Sign-in cancelled by user" }
            return SamosaSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            GotchaLog.d(TAG, e) { "No Google credential available" }
            return SamosaSignInResult.Error(
                appContext.getString(R.string.samosa_auth_no_google_account_available_add)
            )
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Credential Manager error", e)
            return SamosaSignInResult.Error(
                appContext.getString(R.string.samosa_auth_google_sign_in_failed, e.message.orEmpty())
            )
        } catch (e: IllegalStateException) {
            return SamosaSignInResult.Error(e.message ?: appContext.getString(R.string.samosa_auth_unexpected_error))
        }

        return register(idToken)
    }

    /**
     * Opens the account website and suspends until the browser returns with
     * the sign-in link ([handleRedirect]), the user comes back without it
     * ([onHostSettled]), or [SIGN_IN_WAIT_MS] passes. A second call while one
     * is waiting joins it instead of opening another page.
     *
     * Cancelling the caller (leaving the screen) stops the wait but keeps the
     * pending sign-in, so a link that arrives afterwards still signs the user in.
     */
    suspend fun signInWithWebsite(activityContext: Context): SamosaSignInResult {
        synchronized(lock) { waiter }?.let { existing ->
            if (!existing.result.isCompleted) return existing.result.await()
        }
        val verifier = Pkce.generateVerifier()
        val state = UUID.randomUUID().toString()
        withContext(Dispatchers.IO) {
            settingsRepository.savePendingSamosaSignIn(PendingSamosaSignIn(verifier, state, nowMillis()))
        }
        val current = Waiter(CompletableDeferred())
        synchronized(lock) { waiter = current }

        if (!browser.open(activityContext, signInUrl(Pkce.challengeS256(verifier), state))) {
            release(current)
            settingsRepository.clearPendingSamosaSignIn()
            return SamosaSignInResult.Error(appContext.getString(R.string.samosa_auth_no_browser))
        }
        return try {
            withTimeout(SIGN_IN_WAIT_MS) { current.result.await() }
        } catch (e: TimeoutCancellationException) {
            GotchaLog.d(TAG, e) { "Website sign-in timed out" }
            release(current)
            settingsRepository.clearPendingSamosaSignIn()
            SamosaSignInResult.Error(appContext.getString(R.string.samosa_auth_sign_in_timed_out))
        } catch (e: CancellationException) {
            release(current)
            throw e
        }
    }

    /** Call when the screen hosting sign-in goes to the background. */
    fun onHostPaused() {
        synchronized(lock) { waiter?.leftForBrowser = true }
    }

    /**
     * Call a moment ([RETURN_GRACE_MS]) after the host is back on screen. The
     * browser delivers the sign-in link before Gotcha resumes, so a waiting
     * sign-in with no link by now was closed by the user: it ends as Cancelled.
     */
    fun onHostSettled() {
        val abandoned = synchronized(lock) {
            waiter?.takeIf { it.leftForBrowser && !it.redirectArrived }?.also { waiter = null }
        } ?: return
        GotchaLog.d(TAG) { "Back from the browser without signing in" }
        settingsRepository.clearPendingSamosaSignIn()
        abandoned.result.complete(SamosaSignInResult.Cancelled)
    }

    /**
     * Handles a link that opened Gotcha. Finishes the website sign-in when it is
     * the sign-in link: checks it against the pending sign-in, then exchanges
     * the code for a session. The pending sign-in is used up either way.
     */
    suspend fun handleRedirect(uri: Uri): SamosaRedirectOutcome {
        if (!isSignInRedirect(uri)) return SamosaRedirectOutcome.NotSignIn
        val current = synchronized(lock) { waiter?.also { it.redirectArrived = true } }
        val pending = withContext(Dispatchers.IO) { settingsRepository.loadPendingSamosaSignIn() }
        if (pending == null) {
            GotchaLog.d(TAG) { "Sign-in link with no sign-in waiting for it (ignored)" }
            // A waiting screen would otherwise sit until the timeout.
            if (current != null) {
                release(current)
                current.result.complete(
                    SamosaSignInResult.Error(appContext.getString(R.string.samosa_auth_sign_in_link_mismatch))
                )
            }
            return SamosaRedirectOutcome.Ignored
        }
        settingsRepository.clearPendingSamosaSignIn()
        // The exchange spends the one-time code, so it finishes even if the screen goes away.
        val result = withContext(NonCancellable) { resolveRedirect(uri, pending) }
        if (result is SamosaSignInResult.Success) notifySessionChanged()

        val delivered = current != null && synchronized(lock) {
            (waiter === current).also { if (it) waiter = null }
        } && current.result.complete(result)
        return if (delivered) SamosaRedirectOutcome.Delivered else SamosaRedirectOutcome.Finished(result)
    }

    private suspend fun resolveRedirect(uri: Uri, pending: PendingSamosaSignIn): SamosaSignInResult {
        val error = uri.getQueryParameter("error")
        val code = uri.getQueryParameter("code")
        return when {
            uri.getQueryParameter("state") != pending.state -> {
                Log.w(TAG, "Sign-in link does not match the pending sign-in")
                SamosaSignInResult.Error(appContext.getString(R.string.samosa_auth_sign_in_link_mismatch))
            }
            nowMillis() - pending.startedAtMillis > PENDING_TTL_MS ->
                SamosaSignInResult.Error(appContext.getString(R.string.samosa_auth_sign_in_expired))
            error == "access_denied" -> SamosaSignInResult.Cancelled
            error != null -> {
                GotchaLog.d(TAG) { "Account website refused the sign-in: $error" }
                SamosaSignInResult.Error(appContext.getString(R.string.samosa_auth_sign_in_refused))
            }
            code.isNullOrBlank() ->
                SamosaSignInResult.Error(appContext.getString(R.string.samosa_auth_sign_in_link_mismatch))
            else -> exchangeCode(code, pending.verifier)
        }
    }

    private suspend fun exchangeCode(code: String, verifier: String): SamosaSignInResult = try {
        val resp = api.exchangeCode(
            OAuthTokenRequest(
                code = code,
                codeVerifier = verifier,
                redirectUri = REDIRECT_URI,
                deviceLabel = deviceLabel().take(DEVICE_LABEL_MAX)
            )
        )
        finishSignIn(resp)
    } catch (e: HttpException) {
        GotchaLog.d(TAG, e) { "Code exchange failed with HTTP ${e.code()}" }
        SamosaSignInResult.Error(mapExchangeError(e.code()))
    } catch (e: IOException) {
        GotchaLog.d(TAG, e) { "Network error during code exchange" }
        SamosaSignInResult.Error(appContext.getString(R.string.samosa_auth_network_error_check_your_connection))
    } catch (e: SerializationException) {
        // A reply the app cannot read must end the sign-in, not crash the app.
        Log.w(TAG, "Unreadable sign-in reply", e)
        SamosaSignInResult.Error(appContext.getString(R.string.samosa_auth_unexpected_error))
    }

    private fun mapExchangeError(code: Int): String = when (code) {
        // An expired, already used or mismatched code: starting again fixes it.
        400 -> appContext.getString(R.string.samosa_auth_sign_in_expired)
        else -> mapRegisterError(code)
    }

    private fun release(current: Waiter) {
        synchronized(lock) { if (waiter === current) waiter = null }
    }

    private suspend fun requestGoogleIdToken(activityContext: Context): String {
        val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(WEB_CLIENT_ID)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInWithGoogleOption)
            .build()

        val result = credentialManager.getCredential(
            request = request,
            context = activityContext
        )

        val credential = result.credential
        if (credential is androidx.credentials.CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }
        error("Unexpected credential type from Google Sign-In.")
    }

    internal suspend fun register(idToken: String): SamosaSignInResult = try {
        val resp = api.register(
            RegisterRequest(idToken = idToken, app = APP_ID, deviceLabel = deviceLabel())
        )
        finishSignIn(resp)
    } catch (e: HttpException) {
        SamosaSignInResult.Error(mapRegisterError(e.code()))
    } catch (e: IOException) {
        GotchaLog.d(TAG, e) { "Network error during register" }
        SamosaSignInResult.Error(appContext.getString(R.string.samosa_auth_network_error_check_your_connection))
    } catch (e: SerializationException) {
        Log.w(TAG, "Unreadable sign-in reply", e)
        SamosaSignInResult.Error(appContext.getString(R.string.samosa_auth_unexpected_error))
    }

    /** Stores the session from /register or /oauth/token and reads the full profile. */
    private suspend fun finishSignIn(resp: RegisterResponse): SamosaSignInResult {
        if (resp.token.isBlank()) {
            return SamosaSignInResult.Error(appContext.getString(R.string.samosa_auth_server_did_not_return_a))
        }
        settingsRepository.saveSamosaSession(resp.token, resp.user.email, resp.refreshToken.orEmpty())
        // Fetch fresh /me to obtain full tier, tags, and referral metadata
        val profile = runCatching { api.me("Bearer ${resp.token}").user }.getOrNull() ?: resp.user
        rememberDisplayName(profile)
        val isNew = profile.referral.canClaim
        return SamosaSignInResult.Success(resp.user.email.ifBlank { profile.email }, user = profile, isNewUser = isNew)
    }

    private fun mapRegisterError(code: Int): String = when (code) {
        401 -> appContext.getString(R.string.samosa_auth_sign_in_was_rejected_by)
        403 -> appContext.getString(R.string.samosa_auth_this_account_is_disabled_contact)
        429 -> appContext.getString(R.string.samosa_auth_too_many_attempts_please_wait)
        502, 503 -> appContext.getString(R.string.samosa_auth_samosa_ai_is_temporarily_unavailable)
        else -> appContext.getString(R.string.samosa_auth_registration_failed_http, code)
    }

    /**
     * Logs out of Samosa: best-effort server-side sign-out, then always clears
     * the local session and Google credential state. Never touches OpenAI settings.
     */
    suspend fun signOut() {
        val token = settingsRepository.load().samosaSessionToken
        if (token.isNotBlank()) {
            runCatching { api.logout("Bearer $token") }
                .onFailure { GotchaLog.d(TAG) { "Server logout failed (ignored): ${it.message}" } }
        }
        settingsRepository.clearSamosaSession()
        settingsRepository.clearPendingSamosaSignIn()
        // Best-effort: the session is already gone, so a stuck Play services
        // must not hold sign-out on screen.
        runCatching {
            withTimeoutOrNull(CREDENTIAL_CLEAR_TIMEOUT_MS) {
                credentialManager.clearCredentialState(
                    androidx.credentials.ClearCredentialStateRequest()
                )
            }
        }
    }

    /**
     * Caches the name the drawer shows. Announces a session change when it
     * differs, so an open drawer swaps the email for the name without a restart.
     */
    private fun rememberDisplayName(user: SamosaUser) {
        val name = user.displayName.trim()
        if (name.isEmpty() || name == settingsRepository.load().samosaDisplayName) return
        settingsRepository.saveSamosaDisplayName(name)
        sessionChangeEvents.tryEmit(Unit)
    }

    /** Called on a 401 from a Samosa API call: drop the stale token. */
    fun invalidateSession() {
        settingsRepository.clearSamosaSession()
    }

    /**
     * Fetches the user's full profile including tier, tags, and referral info from GET /me.
     * Returns null when not signed in or when the server is unreachable.
     */
    suspend fun fetchUserProfile(): SamosaUser? {
        val token = settingsRepository.load().samosaSessionToken
        if (token.isBlank()) return null
        return try {
            api.me("Bearer $token").user.also { rememberDisplayName(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GotchaLog.d(TAG, e) { "Could not fetch user profile (ignored)" }
            null
        }
    }

    /**
     * Fetches the user's remaining credit from GET /me. Returns null when not
     * signed in, when the user has no gateway key yet, or when the gateway is
     * unreachable — never throws, so a hiccup cannot break the page.
     */
    suspend fun fetchCreditsRemaining(): Double? {
        return fetchUserProfile()?.creditsRemaining
    }

    /**
     * Claims a referral code via POST /v1/referrals/claim.
     * Returns Result.success with ClaimReferralResponse or Result.failure with a user-friendly message.
     */
    suspend fun claimReferralCode(code: String): Result<ClaimReferralResponse> {
        val token = settingsRepository.load().samosaSessionToken
        if (token.isBlank()) {
            return Result.failure(
                IllegalStateException(appContext.getString(R.string.samosa_auth_not_signed_in_to_samosa))
            )
        }
        val cleanCode = code.trim().uppercase()
        if (cleanCode.isBlank()) {
            return Result.failure(
                IllegalArgumentException(appContext.getString(R.string.samosa_auth_please_enter_an_invite_code))
            )
        }
        return try {
            GotchaLog.d(TAG) { "Claiming referral code: $cleanCode" }
            val resp = api.claimReferral("Bearer $token", ClaimReferralRequest(referralCode = cleanCode))
            GotchaLog.d(TAG) { "Claim referral successful: ${resp.message}" }
            Result.success(resp)
        } catch (e: HttpException) {
            val detail = HumanReadableError.extractHttpErrorDetail(e)
            GotchaLog.d(TAG, e) { "Claim referral failed with HTTP ${e.code()}: $detail" }
            val msg = mapClaimError(e.code(), detail)
            Result.failure(Exception(msg))
        } catch (e: IOException) {
            GotchaLog.d(TAG, e) { "Network error during referral claim" }
            Result.failure(Exception(appContext.getString(R.string.samosa_auth_network_error_please_check_your)))
        } catch (e: Exception) {
            GotchaLog.d(TAG, e) { "Unexpected error during referral claim" }
            Result.failure(e)
        }
    }

    private fun mapClaimError(code: Int, detail: String?): String {
        val lowerDetail = detail?.lowercase() ?: ""
        return when (code) {
            400 -> {
                if (lowerDetail.contains("yourself")) {
                    appContext.getString(R.string.samosa_auth_you_cannot_refer_yourself)
                } else {
                    detail ?: appContext.getString(R.string.samosa_auth_invalid_referral_code)
                }
            }
            404 -> detail ?: appContext.getString(R.string.samosa_auth_referral_code_not_found)
            409 -> {
                if (lowerDetail.contains("limit")) {
                    appContext.getString(R.string.samosa_auth_this_referral_code_has_reached)
                } else {
                    detail ?: appContext.getString(R.string.samosa_auth_you_have_already_been_referred)
                }
            }
            410 -> detail ?: appContext.getString(R.string.samosa_auth_referral_window_expired_codes_must, REFERRAL_CLAIM_WINDOW_HOURS)
            429 -> detail ?: appContext.getString(R.string.samosa_auth_too_many_attempts_please_try)
            502 -> detail ?: appContext.getString(R.string.samosa_auth_bonus_grant_failed_please_try)
            else -> detail ?: appContext.getString(R.string.samosa_auth_referral_claim_failed_http, code)
        }
    }

    companion object {
        private const val TAG = "SamosaAuth"

        /** This app's id in the backend's app registry. */
        const val APP_ID = "gotcha-android"

        /** Where the account website sends the browser back with the sign-in code. */
        const val REDIRECT_SCHEME = "com.gotcha"
        const val REDIRECT_PATH = "/oauth2redirect"
        const val REDIRECT_URI = "$REDIRECT_SCHEME:$REDIRECT_PATH"

        /** The backend's sign-in code lives 5 minutes; stop waiting a little before. */
        const val SIGN_IN_WAIT_MS = 4 * 60 * 1000L

        /** A pending sign-in older than this can no longer finish. */
        const val PENDING_TTL_MS = 5 * 60 * 1000L

        /** How long after Gotcha resumes a missing sign-in link means "closed". */
        const val RETURN_GRACE_MS = 2_000L

        /** How long sign-out waits for Credential Manager to forget the Google account. */
        private const val CREDENTIAL_CLEAR_TIMEOUT_MS = 3_000L

        /** The backend's limit for a device label on /oauth/token. */
        private const val DEVICE_LABEL_MAX = 120

        /** True for `com.gotcha:/oauth2redirect…`, the only link that finishes a sign-in. */
        fun isSignInRedirect(uri: Uri): Boolean =
            uri.scheme == REDIRECT_SCHEME && uri.path == REDIRECT_PATH

        /** The account website's sign-in page for this app. */
        fun signInUrl(challenge: String, state: String): Uri =
            SamosaDashboard.rootBuilder()
                .appendQueryParameter("oauth_client", APP_ID)
                .appendQueryParameter("oauth_redirect", REDIRECT_URI)
                .appendQueryParameter("oauth_challenge", challenge)
                .appendQueryParameter("state", state)
                .build()

        /** For example "Google Pixel 8": how the user tells their sessions apart. */
        internal fun deviceLabel(): String =
            listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
                .filter { !it.isNullOrBlank() }
                .joinToString(" ")
                .replaceFirstChar { it.uppercase() }

        /**
         * WEB OAuth client ID. Google mints the ID token with aud = this value,
         * which is what the backend verifies. Do NOT use the Android client ID.
         *
         * Supplied at build time via the `SAMOSA_WEB_CLIENT_ID` environment
         * variable or `local.properties`; a public checkout falls back to an
         * inert placeholder, so Samosa sign-in is disabled until you set it.
         */
        val WEB_CLIENT_ID: String = BuildConfig.SAMOSA_WEB_CLIENT_ID
    }
}
