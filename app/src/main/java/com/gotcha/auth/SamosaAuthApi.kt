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
    @SerialName("referral_code") val referralCode: String? = null,
    /** Which registered app is signing in. Backends that predate app scoping ignore it. */
    val app: String? = null,
    /** Shown to the user in their list of signed-in devices. */
    @SerialName("device_label") val deviceLabel: String? = null
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

/** Response from GET /v1/user/limits. Only the model allow-list is read. */
@Serializable
data class LimitsResponse(
    val tier: String = "",
    /** Null or empty: the plan has no model restriction. May contain "*" for every model. */
    @SerialName("allowed_models") val allowedModels: List<String>? = null
)

/** Response from POST /register — the session JWT plus the user profile. */
@Serializable
data class RegisterResponse(
    val token: String = "",
    /** Renews [token]. Null from a backend that issues one long-lived token. */
    val refreshToken: String? = null,
    val user: SamosaUser = SamosaUser()
)

/**
 * Request body for POST /oauth/token: trades the one-time code from the
 * account website, plus the PKCE verifier that only this app holds, for a session.
 */
@Serializable
data class OAuthTokenRequest(
    val code: String,
    @SerialName("code_verifier") val codeVerifier: String,
    @SerialName("redirect_uri") val redirectUri: String,
    @SerialName("device_label") val deviceLabel: String? = null
)

/** Request body for POST /auth/refresh. Sent without an Authorization header. */
@Serializable
data class RefreshRequest(val refreshToken: String)

/** Response from POST /auth/refresh: the next access token and the refresh token that renews it. */
@Serializable
data class RefreshResponse(
    val token: String = "",
    val refreshToken: String = ""
)

/**
 * The refresh call on its own, blocking, because it runs inside an OkHttp
 * interceptor. Kept apart from [SamosaAuthApi] so its client carries no
 * [SamosaAuthInterceptor]: a renewal must never wait on another renewal.
 */
interface SamosaRefreshApi {
    @POST("auth/refresh")
    fun refresh(@Body body: RefreshRequest): retrofit2.Call<RefreshResponse>
}

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

/** Response from GET /gotcha/shell/runtime/latest. */
@Serializable
data class ShellRuntimeSource(
    /** For example "5.1.107.91-1". The companion compares it with its own pinned version. */
    val version: String = "",
    /** Absolute https URL to the bundle. Expires; ask again rather than reusing it. */
    val url: String = "",
    /** SHA-256 of the bundle, 64 lowercase hex characters. Must equal the companion's pin. */
    val sha256: String = ""
) {
    /**
     * Whether this answer is worth passing on. A partial one would only fail in the companion,
     * whose own check also requires 64 lowercase hex characters.
     */
    fun isUsable(): Boolean =
        version.isNotBlank() && url.startsWith("https://") &&
            sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }
}

/** Response from GET /gotcha/shell/release — the companion APK itself. */
@Serializable
data class ShellApkRelease(
    /** For example "0.2.0". */
    val version: String = "",
    /** SHA-256 of the APK, 64 lowercase hex characters. */
    val sha256: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    /**
     * RELATIVE path such as `/gotcha/shell/download/<token>`. The server returns it relative on
     * purpose: it is resolved against the configured Samosa origin here, so a staging hub on a
     * different host needs no server-side change.
     */
    @SerialName("download_path") val downloadPath: String = "",
    @SerialName("expires_in_seconds") val expiresInSeconds: Long = 0L
) {
    companion object {
        /**
         * The one shape the download path may take: the canonical route and a 43-character
         * token, and nothing else.
         *
         * Checked this strictly because the path is concatenated onto the configured base URL
         * and fetched with the caller's session. A prefix test alone would admit
         * `.../download/../../admin/reset` or a query string, and OkHttp would resolve the
         * traversal and send the bearer token to another endpoint on the same host.
         */
        private val DOWNLOAD_PATH = Regex("^/gotcha/shell/download/[A-Za-z0-9_-]{43}$")
    }

    /**
     * Whether this answer is safe to act on. A partial one would only fail later, after the
     * user has waited for permission and a download.
     */
    fun isUsable(): Boolean =
        version.isNotBlank() && sizeBytes > 0 &&
            sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' } &&
            DOWNLOAD_PATH.matches(downloadPath)
}

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

    /** The plan's caps, including which models it may call: the list the proxy enforces. */
    @GET("v1/user/limits")
    suspend fun limits(
        @Header("Authorization") bearer: String,
        @retrofit2.http.Query("app") app: String = "gotcha-android"
    ): LimitsResponse

    @POST("v1/referrals/claim")
    suspend fun claimReferral(
        @Header("Authorization") bearer: String,
        @Body body: ClaimReferralRequest
    ): ClaimReferralResponse

    @POST("logout")
    suspend fun logout(@Header("Authorization") bearer: String)

    /** Same answer as [register]: the session pair and the user. */
    @POST("oauth/token")
    suspend fun exchangeCode(@Body body: OAuthTokenRequest): RegisterResponse

    /**
     * Where the companion should fetch its Linux runtime from. The route is tier-gated, so the
     * call needs the account session: pass it explicitly like [me] does, because the interceptor
     * leaves a request with no bearer token alone instead of attaching one.
     *
     * The companion pins the SHA-256 of the runtime in its own APK, so this answer can choose
     * where the bytes come from but never which bytes they are. A checksum that disagrees is a
     * build-skew bug, not something to retry.
     */
    @GET("gotcha/shell/runtime/latest")
    suspend fun shellRuntime(@Header("Authorization") bearer: String): ShellRuntimeSource

    /**
     * The companion APK release, for a Pro/influencer account.
     *
     * Unlike the runtime source this is fetched by Gotcha, which holds a session — the companion
     * cannot, so it is never asked to. The route is tier-gated for the same reason: an APK is a
     * licence-gated binary, not a public download.
     */
    @GET("gotcha/shell/release")
    suspend fun shellApkRelease(@Header("Authorization") bearer: String): ShellApkRelease

    companion object {
        /** Auth-manager root (no /v1). Trailing slash required by Retrofit. */
        val AUTH_BASE_URL: String = "${BuildConfig.SAMOSA_API_URL}/"

        /**
         * How the backend's JSON is read. `coerceInputValues` turns a null the
         * backend sends for a text field, such as `profile_picture` for an account
         * with no avatar, into that field's default instead of failing the reply.
         */
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        internal val samosaJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
            coerceInputValues = true
        }

        fun create(baseUrl: String = AUTH_BASE_URL): SamosaAuthApi {
            val json = samosaJson
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
                // Calls that carry a session token get the live one and are
                // retried once after a renewal; sign-in carries none and passes through.
                .addInterceptor(SamosaAuthInterceptor())
                .addInterceptor(logging)
                .build()
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(SamosaAuthApi::class.java)
        }
    }
}
