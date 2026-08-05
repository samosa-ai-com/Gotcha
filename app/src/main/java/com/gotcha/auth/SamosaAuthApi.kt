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

/** Request body for POST /register — the Google ID token (not the JWT). */
@Serializable
data class RegisterRequest(val idToken: String)

/** User profile returned by /register and /me. */
@Serializable
data class SamosaUser(
    val id: String = "",
    val email: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("profile_picture") val profilePicture: String = "",
    val role: String = "",
    /**
     * Remaining credit as a float, fetched live from the AIR gateway via the
     * user's own `sk-air-*` key. Null when the user has no gateway key yet or
     * the gateway is unreachable. Never rendered raw — only as the ×1000
     * whole number (see `formatScaledCredits` in SettingsCommon.kt).
     */
    @SerialName("credits_remaining") val creditsRemaining: Double? = null
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
                level = HttpLoggingInterceptor.Level.BASIC
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
