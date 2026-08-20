package com.gotcha.auth

import com.gotcha.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/** Maximum claim window in hours from signup for referral bonus. */
const val REFERRAL_CLAIM_WINDOW_HOURS = 72L

/** Request body for POST /register — the Google ID token (not the JWT) plus optional referral code. */
@Serializable
data class RegisterRequest(
    val idToken: String,
    @SerialName("referral_code") val referralCode: String? = null
)

/** User tier information returned in /me. */
@Serializable
data class SamosaTier(
    val id: String = "free",
    @SerialName("display_name") val displayName: String = "Free",
    @SerialName("badge_color") val badgeColor: String = "#6c757d"
)

/** Referrer details returned in referral metadata (code-only in Rev 3). */
@Serializable
data class SamosaReferrer(
    val id: String = "",
    @SerialName("display_name") val displayName: String = "",
    val email: String = "",
    @SerialName("referral_code") val referralCode: String = ""
) {
    /** Best-effort display code for this referrer. */
    val displayCode: String
        get() = referralCode.ifBlank { id }
}

/** Referral metadata returned in /me. */
@Serializable
data class SamosaReferral(
    val code: String? = null,
    @SerialName("share_url") val shareUrl: String = "",
    @SerialName("total_referred") val totalReferred: Int = 0,
    @SerialName("credits_earned") val creditsEarned: Double = 0.0,
    @SerialName("can_claim") val canClaim: Boolean = false,
    @SerialName("referred_by") val referredBy: SamosaReferrer? = null
)

/** User profile returned by /register and /me. */
@Serializable
data class SamosaUser(
    val id: String = "",
    val email: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("profile_picture") val profilePicture: String = "",
    val role: String = "",
    val status: String = "active",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_login") val lastLogin: String? = null,
    /**
     * Remaining credit as a float, fetched live from the AIR gateway via the
     * user's own `sk-air-*` key. Null when the user has no gateway key yet or
     * the gateway is unreachable. Never rendered raw — only as the ×1000
     * whole number (see `formatScaledCredits` in SettingsCommon.kt).
     */
    @SerialName("credits_remaining") val creditsRemaining: Double? = null,
    val tier: SamosaTier = SamosaTier(),
    val tags: List<String> = emptyList(),
    val referral: SamosaReferral = SamosaReferral()
)

/** Response from POST /register — the session JWT plus the user profile. */
@Serializable
data class RegisterResponse(
    val token: String = "",
    val user: SamosaUser = SamosaUser()
)

/** Response from GET /me — the user profile enveloped under `user`. */
@Serializable
data class MeResponse(
    val user: SamosaUser = SamosaUser()
)

/** Request body for POST /v1/referrals/claim. */
@Serializable
data class ClaimReferralRequest(
    @SerialName("referral_code") val referralCode: String
)

/** Referral reward details returned in claim response. */
@Serializable
data class ClaimedReferralInfo(
    val code: String = "",
    val referrer: SamosaReferrer? = null,
    @SerialName("referrer_reward") val referrerReward: Double = 0.0,
    @SerialName("referee_reward") val refereeReward: Double = 0.0
)

/** Response from POST /v1/referrals/claim. */
@Serializable
data class ClaimReferralResponse(
    val message: String = "",
    val referral: ClaimedReferralInfo? = null,
    @SerialName("credits_remaining") val creditsRemaining: Double? = null
)

/**
 * Samosa AI auth endpoints (base URL [AUTH_BASE_URL], from BuildConfig).
 * The OpenAI-compatible chat/model endpoints are handled by the existing
 * LLMClient against the /v1/ proxy root — these are auth-only.
 */
interface SamosaAuthApi {

    @POST("register")
    suspend fun register(@Body body: RegisterRequest): RegisterResponse

    @GET("me")
    suspend fun me(@Header("Authorization") bearer: String): MeResponse

    @POST("v1/referrals/claim")
    suspend fun claimReferral(
        @Header("Authorization") bearer: String,
        @Body body: ClaimReferralRequest
    ): ClaimReferralResponse

    @POST("logout")
    suspend fun logout(@Header("Authorization") bearer: String)

    companion object {
        /** Auth-manager root (no /v1). Trailing slash required by Retrofit. */
        val AUTH_BASE_URL: String = "${BuildConfig.SAMOSA_API_URL}/"

        fun create(): SamosaAuthApi {
            @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
            val json = Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = false
            }
            val logging = HttpLoggingInterceptor().apply {
                // The request URL is the configured endpoint — not for release logcat.
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BASIC
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
                redactHeader("Authorization")
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build()
            return Retrofit.Builder()
                .baseUrl(AUTH_BASE_URL)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(SamosaAuthApi::class.java)
        }
    }
}
