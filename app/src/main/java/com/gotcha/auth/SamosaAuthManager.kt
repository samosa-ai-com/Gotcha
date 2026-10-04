package com.gotcha.auth

import android.content.Context
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
import com.gotcha.data.SettingsRepository
import com.gotcha.util.GotchaLog
import com.gotcha.util.HumanReadableError
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

/** Outcome of a Samosa sign-in attempt, surfaced to the UI. */
sealed interface SamosaSignInResult {
    data class Success(
        val email: String,
        val user: SamosaUser? = null,
        val isNewUser: Boolean = false
    ) : SamosaSignInResult

    data class Error(val message: String) : SamosaSignInResult

    /** User dismissed the Google account chooser — not a real failure. */
    data object Cancelled : SamosaSignInResult
}

/**
 * Handles the Google Sign-In dance for Samosa AI accounts:
 *
 *  1. Request a Google ID token from Credential Manager using the Web client ID.
 *  2. Exchange it at POST /register for a session JWT.
 *  3. Persist the JWT + account email in EncryptedSharedPreferences.
 *
 * The Google ID token is never stored. Logout deletes the JWT and clears the
 * Credential Manager state. Does not touch OpenAI-compatible settings.
 */
class SamosaAuthManager(
    private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val api: SamosaAuthApi = SamosaAuthApi.create()
) {
    private val credentialManager = CredentialManager.create(appContext)

    /**
     * Runs the full Google Sign-In → /register → store flow. Must be called with
     * an Activity [context] so Credential Manager can show the account chooser.
     */
    suspend fun signIn(activityContext: Context): SamosaSignInResult {
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

    private suspend fun register(idToken: String): SamosaSignInResult = try {
        val resp = api.register(RegisterRequest(idToken = idToken))
        if (resp.token.isBlank()) {
            SamosaSignInResult.Error(appContext.getString(R.string.samosa_auth_server_did_not_return_a))
        } else {
            settingsRepository.saveSamosaSession(resp.token, resp.user.email)
            // Fetch fresh /me to obtain full tier, tags, and referral metadata
            val profile = runCatching { api.me("Bearer ${resp.token}").user }.getOrNull() ?: resp.user
            val isNew = profile.referral.canClaim
            SamosaSignInResult.Success(resp.user.email, user = profile, isNewUser = isNew)
        }
    } catch (e: HttpException) {
        SamosaSignInResult.Error(mapRegisterError(e.code()))
    } catch (e: IOException) {
        GotchaLog.d(TAG, e) { "Network error during register" }
        SamosaSignInResult.Error(appContext.getString(R.string.samosa_auth_network_error_check_your_connection))
    }

    private fun mapRegisterError(code: Int): String = when (code) {
        401 -> appContext.getString(R.string.samosa_auth_sign_in_was_rejected_by)
        403 -> appContext.getString(R.string.samosa_auth_this_account_is_disabled_contact)
        429 -> appContext.getString(R.string.samosa_auth_too_many_attempts_please_wait)
        502 -> appContext.getString(R.string.samosa_auth_samosa_ai_is_temporarily_unavailable)
        else -> appContext.getString(R.string.samosa_auth_registration_failed_http, code)
    }

    /**
     * Logs out of Samosa: best-effort server-side blacklist, then always clears
     * the local JWT and Google credential state. Never touches OpenAI settings.
     */
    suspend fun signOut() {
        val token = settingsRepository.load().samosaSessionToken
        if (token.isNotBlank()) {
            runCatching { api.logout("Bearer $token") }
                .onFailure { GotchaLog.d(TAG) { "Server logout failed (ignored): ${it.message}" } }
        }
        settingsRepository.clearSamosaSession()
        runCatching {
            credentialManager.clearCredentialState(
                androidx.credentials.ClearCredentialStateRequest()
            )
        }
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
            api.me("Bearer $token").user
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
        val lowerDetail = detail?.lowercase() ?: appContext.getString(R.string.samosa_auth)
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
