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
import retrofit2.HttpException
import java.io.IOException

/** Outcome of a Samosa sign-in attempt, surfaced to the UI. */
sealed interface SamosaSignInResult {
    data class Success(val email: String) : SamosaSignInResult
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
    suspend fun signIn(activityContext: Context): SamosaSignInResult {
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

        return register(idToken)
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

    private suspend fun register(idToken: String): SamosaSignInResult = try {
        val resp = api.register(RegisterRequest(idToken))
        if (resp.token.isBlank()) {
            SamosaSignInResult.Error("Server did not return a session token.")
        } else {
            settingsRepository.saveSamosaSession(resp.token, resp.user.email)
            SamosaSignInResult.Success(resp.user.email)
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
     * Fetches the user's remaining credit from GET /me. Returns null when not
     * signed in, when the user has no gateway key yet, or when the gateway is
     * unreachable — never throws, so a hiccup cannot break the page.
     */
    suspend fun fetchCreditsRemaining(): Double? {
        val token = settingsRepository.load().samosaSessionToken
        if (token.isBlank()) return null
        return try {
            api.me("Bearer $token").user.creditsRemaining
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GotchaLog.d(TAG, e) { "Could not fetch credit balance (ignored)" }
            null
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
