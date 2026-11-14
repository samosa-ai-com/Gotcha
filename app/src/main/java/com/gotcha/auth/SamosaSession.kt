package com.gotcha.auth

import android.content.Context
import com.gotcha.BuildConfig
import com.gotcha.data.SettingsRepository
import com.gotcha.util.GotchaLog
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** The stored Samosa session: what is sent with requests, and what renews it. */
data class SamosaTokens(
    val accessToken: String = "",
    /** Blank for a session from a backend that issues no refresh tokens. */
    val refreshToken: String = ""
)

/** Why a session ended without the user signing out. */
enum class SignOutReason { EXPIRED, BANNED, DEACTIVATED }

/** What [SamosaSession.refresh] did. */
sealed interface RefreshOutcome {
    /** [token] is the access token to use now. */
    data class Refreshed(val token: String) : RefreshOutcome

    /** The session is over and has been cleared. */
    data class SignedOut(val reason: SignOutReason) : RefreshOutcome

    /** The backend could not be reached. The session is untouched. */
    data object Unavailable : RefreshOutcome
}

/** The refresh endpoint's answer, reduced to what [SamosaSession] acts on. */
sealed interface RefreshReply {
    data class NewPair(val token: String, val refreshToken: String) : RefreshReply
    data class Rejected(val reason: SignOutReason) : RefreshReply
    data object Unreachable : RefreshReply
}

/** Exchanges a refresh token for a new pair. Blocking. */
fun interface SamosaRefresher {
    fun refresh(refreshToken: String): RefreshReply
}

/**
 * The one owner of the Samosa session's tokens.
 *
 * The backend's access token lasts 30 minutes and is renewed with a refresh
 * token that works exactly once: using it retires it, and using a retired one
 * again (after a 60 second grace) is read as theft and ends every session of
 * that login. So renewals must never run in parallel, and a new pair must be
 * on disk before anything uses it. Both happen here and nowhere else.
 *
 * A session from a backend without refresh tokens has only an access token;
 * [refresh] then ends it on the first 401, which is what Gotcha always did.
 */
class SamosaSession(
    private val store: SettingsRepository,
    private val refresher: SamosaRefresher,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()

    @Volatile
    private var lastSignOutReason: SignOutReason? = null

    fun tokens(): SamosaTokens = store.loadSamosaTokens()

    /** The reason for the most recent forced sign-out, handed out once. */
    fun consumeSignOutReason(): SignOutReason? = lastSignOutReason.also { lastSignOutReason = null }

    /**
     * True when [accessToken] expires within [withinMillis]. False for a token
     * whose expiry cannot be read: the server's 401 then decides.
     */
    fun expiresWithin(accessToken: String, withinMillis: Long): Boolean {
        val expiresAt = expiryMillis(accessToken) ?: return false
        return expiresAt - nowMillis() <= withinMillis
    }

    /**
     * Renews the session because [staleToken] is expired or was refused.
     *
     * Callers queue here. Whoever arrives after a renewal finds the stored
     * token already differs from the one it holds, and gets the stored one
     * without spending the refresh token a second time.
     */
    fun refresh(staleToken: String): RefreshOutcome = synchronized(lock) {
        val stored = store.loadSamosaTokens()
        when {
            stored.accessToken.isBlank() ->
                RefreshOutcome.SignedOut(lastSignOutReason ?: SignOutReason.EXPIRED)
            stored.accessToken != staleToken -> RefreshOutcome.Refreshed(stored.accessToken)
            stored.refreshToken.isBlank() -> signOut(SignOutReason.EXPIRED)
            else -> when (val reply = refresher.refresh(stored.refreshToken)) {
                is RefreshReply.NewPair -> {
                    store.rotateSamosaTokens(reply.token, reply.refreshToken)
                    RefreshOutcome.Refreshed(reply.token)
                }
                is RefreshReply.Rejected -> signOut(reply.reason)
                RefreshReply.Unreachable -> RefreshOutcome.Unavailable
            }
        }
    }

    private fun signOut(reason: SignOutReason): RefreshOutcome {
        GotchaLog.d(TAG) { "Samosa session ended: $reason" }
        lastSignOutReason = reason
        store.clearSamosaSession()
        return RefreshOutcome.SignedOut(reason)
    }

    companion object {
        private const val TAG = "SamosaSession"

        @Volatile
        private var installed: SamosaSession? = null

        /** The process-wide session, or null before [install] (plain unit tests). */
        val current: SamosaSession? get() = installed

        /** Called once from the Application. Touches no storage until first used. */
        fun install(context: Context) {
            if (installed != null) return
            synchronized(this) {
                if (installed == null) {
                    installed = SamosaSession(
                        SettingsRepository(context.applicationContext),
                        HttpSamosaRefresher()
                    )
                }
            }
        }

        /** True for a URL on the Samosa backend, the only place its tokens may go. */
        fun isSamosaUrl(url: String): Boolean {
            val base = BuildConfig.SAMOSA_API_URL.trimEnd('/')
            return base.isNotBlank() && (url == base || url.startsWith("$base/"))
        }

        private val json = Json { ignoreUnknownKeys = true }

        /** The `exp` claim of a JWT in milliseconds, without verifying it; null if unreadable. */
        internal fun expiryMillis(accessToken: String): Long? = runCatching {
            val payload = accessToken.split('.').getOrNull(1) ?: return null
            val decoded = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
            json.parseToJsonElement(decoded).jsonObject["exp"]?.jsonPrimitive?.longOrNull?.times(MILLIS)
        }.getOrNull()

        private const val MILLIS = 1000L
    }
}

/** [SamosaRefresher] against `POST /auth/refresh`. */
class HttpSamosaRefresher(
    private val api: SamosaRefreshApi = createApi()
) : SamosaRefresher {

    override fun refresh(refreshToken: String): RefreshReply = try {
        val response = api.refresh(RefreshRequest(refreshToken)).execute()
        val body = response.body()
        when {
            response.isSuccessful && body != null && body.token.isNotBlank() && body.refreshToken.isNotBlank() ->
                RefreshReply.NewPair(body.token, body.refreshToken)
            // A 200 without a pair is a broken answer, not a verdict on the session.
            response.isSuccessful -> RefreshReply.Unreachable
            else -> rejection(response.code(), response.errorBody()?.string().orEmpty())
        }
    } catch (e: IOException) {
        GotchaLog.d(TAG, e) { "Refresh failed: network" }
        RefreshReply.Unreachable
    } catch (e: RuntimeException) {
        // An unparseable body. Retrofit reports it unchecked.
        GotchaLog.d(TAG, e) { "Refresh failed: unreadable answer" }
        RefreshReply.Unreachable
    }

    companion object {
        private const val TAG = "SamosaSession"
        private const val TIMEOUT_SECONDS = 15L
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_UNPROCESSABLE = 422

        /**
         * Only an answer that is plainly about this session ends it. Anything
         * else (a 5xx, a rate limit, a proxy's own 403) leaves it alone, so an
         * outage never signs users out.
         */
        internal fun rejection(code: Int, errorBody: String): RefreshReply = when {
            code == HTTP_FORBIDDEN && "ACCOUNT_BANNED" in errorBody ->
                RefreshReply.Rejected(SignOutReason.BANNED)
            code == HTTP_FORBIDDEN && "ACCOUNT_DEACTIVATED" in errorBody ->
                RefreshReply.Rejected(SignOutReason.DEACTIVATED)
            // 404: a backend without the refresh route cannot renew anything.
            code in setOf(HTTP_BAD_REQUEST, HTTP_UNAUTHORIZED, HTTP_NOT_FOUND, HTTP_UNPROCESSABLE) ->
                RefreshReply.Rejected(SignOutReason.EXPIRED)
            else -> RefreshReply.Unreachable
        }

        private fun createApi(): SamosaRefreshApi {
            val json = Json { ignoreUnknownKeys = true }
            val client = OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            return Retrofit.Builder()
                .baseUrl(SamosaAuthApi.AUTH_BASE_URL)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(SamosaRefreshApi::class.java)
        }
    }
}
