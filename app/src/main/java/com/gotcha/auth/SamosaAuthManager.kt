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
import com.gotcha.data.SettingsRepository
import com.gotcha.util.GotchaLog
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * Drives Samosa AI authentication:
 *  1. Request a Google ID token via Credential Manager (using the WEB client ID
 *     as serverClientId, per the backend's requirements).
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
    suspend fun signIn(activityContext: Context, referralCode: String? = null): SamosaSignInResult {
        val idToken = try {
            requestGoogleIdToken(activityContext)
        } catch (e: GetCredentialCancellationException) {
            GotchaLog.d(TAG, e) { "Sign-in cancelled by user" }
            return SamosaSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            GotchaLog.d(TAG, e) { "No Google credential available" }
            return SamosaSignInResult.Error(
                "No Google account available. Add a Google account to this device and try again."
            )
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Credential Manager error", e)
            return SamosaSignInResult.Error("Google Sign-In failed: ${e.message}")
        } catch (e: IllegalStateException) {
            return SamosaSignInResult.Error(e.message ?: "Unexpected sign-in error.")
        }

        return register(idToken, referralCode)
    }

    private suspend fun requestGoogleIdToken(activityContext: Context): String {
        val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(WEB_CLIENT_ID)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInWithGoogleOption)
            .build()

        val response = credentialManager.getCredential(activityContext, request)
        val credential = response.credential
        if (credential is androidx.credentials.CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }
        error("Unexpected credential type from Google Sign-In.")
    }

    private suspend fun register(idToken: String, referralCode: String? = null): SamosaSignInResult = try {
        val cleanCode = referralCode?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        val resp = api.register(RegisterRequest(idToken = idToken, referralCode = cleanCode))
        if (resp.token.isBlank()) {
            SamosaSignInResult.Error("Server did not return a session token.")
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
        SamosaSignInResult.Error("Network error. Check your connection and try again.")
    }

    private fun mapRegisterError(code: Int): String = when (code) {
        401 -> "Sign-in was rejected by the server (invalid token). Please try again."
        403 -> "This account is disabled. Contact support."
        429 -> "Too many attempts. Please wait a moment and try again."
        502 -> "Samosa AI is temporarily unavailable (gateway error). Try again shortly."
        else -> "Registration failed (HTTP $code)."
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
     * Fetches the full user profile (including tier, tags, and referral metadata) from GET /me.
     * Returns null when not signed in or when unreachable.
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
     * Claims a referral code via POST /api/v1/referrals/claim.
     * Returns Result.success with ClaimReferralResponse or Result.failure with a user-friendly message.
     */
    suspend fun claimReferralCode(code: String): Result<ClaimReferralResponse> {
        val token = settingsRepository.load().samosaSessionToken
        if (token.isBlank()) {
            return Result.failure(IllegalStateException("Not signed in to Samosa AI."))
        }
        val cleanCode = code.trim().uppercase()
        if (cleanCode.isBlank()) {
            return Result.failure(IllegalArgumentException("Please enter an invite code."))
        }
        return try {
            val resp = api.claimReferral("Bearer $token", ClaimReferralRequest(referralCode = cleanCode))
            Result.success(resp)
        } catch (e: HttpException) {
            val detail = extractErrorDetail(e)
            val msg = mapClaimError(e.code(), detail)
            Result.failure(Exception(msg))
        } catch (e: IOException) {
            GotchaLog.d(TAG, e) { "Network error during referral claim" }
            Result.failure(Exception("Network error. Please check your connection and try again."))
        } catch (e: Exception) {
            GotchaLog.d(TAG, e) { "Unexpected error during referral claim" }
            Result.failure(e)
        }
    }

    private fun extractErrorDetail(e: HttpException): String? {
        return try {
            val body = e.response()?.errorBody()?.string() ?: return null
            val json = Json { ignoreUnknownKeys = true }
            val obj = json.parseToJsonElement(body) as? JsonObject
            obj?.get("detail")?.let {
                if (it is JsonPrimitive) it.content else it.toString()
            } ?: obj?.get("message")?.let {
                if (it is JsonPrimitive) it.content else it.toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun mapClaimError(code: Int, detail: String?): String {
        val lowerDetail = detail?.lowercase() ?: ""
        return when (code) {
            400 -> {
                if (lowerDetail.contains("yourself")) {
                    "You cannot refer yourself."
                } else {
                    detail ?: "Invalid referral code."
                }
            }
            404 -> "Referral code not found."
            409 -> {
                if (lowerDetail.contains("limit")) {
                    "This referral code has reached its maximum invite limit."
                } else {
                    "You have already been referred."
                }
            }
            410 -> "Referral window expired. Codes must be claimed within 72 hours of signup."
            429 -> "Too many attempts. Please try again later."
            502 -> "Bonus grant failed. Please try again shortly."
            else -> detail ?: "Referral claim failed (HTTP $code)."
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
