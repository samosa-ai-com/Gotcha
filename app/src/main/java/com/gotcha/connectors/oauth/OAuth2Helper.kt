package com.gotcha.connectors.oauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Static config for one OAuth2 provider (n8n client-oauth2 analog). */
data class OAuth2Config(
    val authUrl: String,
    val tokenUrl: String,
    val clientId: String,
    /**
     * Null/blank for public clients that authenticate with PKCE only (e.g. a
     * Microsoft Entra "Mobile and desktop applications" registration). The
     * client_secret form field is then omitted entirely — Entra rejects it.
     */
    val clientSecret: String?,
    val scopes: List<String>,
    val extraAuthParams: Map<String, String> = emptyMap()
)

/** Tokens returned by an exchange or refresh. */
data class TokenSet(
    val accessToken: String,
    /** Null when the provider did not rotate the refresh token. */
    val refreshToken: String?,
    /** Absolute epoch-millis expiry (computed from expires_in). */
    val expiresAtMillis: Long
)

/** The refresh token is invalid/expired/revoked — user must reconnect. */
class OAuthInvalidGrant(message: String) : IOException(message)

/** Any other non-2xx token endpoint response. */
class OAuthTokenError(val code: Int, message: String) : IOException(message)

/**
 * Provider-generic OAuth2 authorization-code + PKCE engine. Pure OkHttp;
 * JVM-testable against MockWebServer.
 */
class OAuth2Helper(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun buildAuthorizationUrl(
        cfg: OAuth2Config,
        redirectUri: String,
        state: String,
        codeChallenge: String
    ): String {
        val builder = cfg.authUrl.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", cfg.clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", cfg.scopes.joinToString(" "))
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", codeChallenge)
            .addQueryParameter("code_challenge_method", "S256")
        cfg.extraAuthParams.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build().toString()
    }

    suspend fun exchangeCode(
        cfg: OAuth2Config,
        code: String,
        redirectUri: String,
        codeVerifier: String
    ): TokenSet {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", cfg.clientId)
            .addClientSecret(cfg)
            .add("code_verifier", codeVerifier)
            .build()
        return requestToken(cfg.tokenUrl, form, currentRefreshToken = null)
    }

    suspend fun refresh(cfg: OAuth2Config, refreshToken: String): TokenSet {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", cfg.clientId)
            .addClientSecret(cfg)
            .build()
        return requestToken(cfg.tokenUrl, form, currentRefreshToken = refreshToken)
    }

    /** Adds client_secret only for confidential clients; public clients must omit it. */
    private fun FormBody.Builder.addClientSecret(cfg: OAuth2Config): FormBody.Builder =
        cfg.clientSecret?.takeIf { it.isNotBlank() }?.let { add("client_secret", it) } ?: this

    private suspend fun requestToken(
        tokenUrl: String,
        form: FormBody,
        currentRefreshToken: String?
    ): TokenSet = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(tokenUrl).post(form).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val error = runCatching {
                    json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                if (error == "invalid_grant") {
                    throw OAuthInvalidGrant("Token endpoint returned invalid_grant — reconnect required.")
                }
                throw OAuthTokenError(
                    response.code,
                    "Token endpoint HTTP ${response.code}: ${error ?: body.take(200)}"
                )
            }
            val obj = json.parseToJsonElement(body).jsonObject
            val accessToken = obj["access_token"]?.jsonPrimitive?.contentOrNull
                ?: throw OAuthTokenError(response.code, "Token response missing access_token")
            val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
            TokenSet(
                accessToken = accessToken,
                refreshToken = obj["refresh_token"]?.jsonPrimitive?.contentOrNull
                    ?: currentRefreshToken,
                expiresAtMillis = clock() + expiresIn * 1000
            )
        }
    }
}
