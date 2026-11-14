package com.gotcha.auth

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Puts the bearer token on a request and, for the Samosa backend, keeps it
 * current.
 *
 * Clients are built with a copy of the token as it was at that moment. For a
 * Samosa request this sends the session's live token instead, renews it
 * shortly before it expires or when the server answers 401, and retries the
 * request once. For every other provider it only adds [staticToken] and
 * reports a 401, as the clients did before.
 *
 * [onUnauthorized] runs when the request ends unauthorized and nothing more
 * can be done: the caller clears what it holds and asks the user to sign in.
 */
class SamosaAuthInterceptor(
    private val staticToken: String = "",
    private val onUnauthorized: (() -> Unit)? = null,
    private val session: () -> SamosaSession? = { SamosaSession.current },
    private val isSamosaUrl: (String) -> Boolean = SamosaSession.Companion::isSamosaUrl
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val presented = original.header(AUTHORIZATION)?.removePrefix(BEARER) ?: staticToken
        // A request with no token at all (sign-in, the refresh itself) is not ours to manage.
        if (presented.isBlank()) return chain.proceed(original)

        val session = session()?.takeIf { isSamosaUrl(original.url.toString()) }
        val stored = session?.tokens()
        if (session == null || stored == null || stored.accessToken.isBlank()) {
            return chain.proceed(original.withToken(presented)).also {
                if (it.code == HTTP_UNAUTHORIZED) onUnauthorized?.invoke()
            }
        }
        return proceedManaged(chain, original, session, stored)
    }

    private fun proceedManaged(
        chain: Interceptor.Chain,
        original: Request,
        session: SamosaSession,
        stored: SamosaTokens
    ): Response {
        var token = stored.accessToken
        // Renew ahead of expiry so a long streaming request does not start with a refusal.
        if (stored.refreshToken.isNotBlank() && session.expiresWithin(token, EXPIRY_MARGIN_MILLIS)) {
            (session.refresh(token) as? RefreshOutcome.Refreshed)?.let { token = it.token }
        }

        val response = chain.proceed(original.withToken(token))
        if (response.code != HTTP_UNAUTHORIZED) return response

        return when (val outcome = session.refresh(token)) {
            is RefreshOutcome.Refreshed -> {
                response.close()
                chain.proceed(original.withToken(outcome.token)).also {
                    if (it.code == HTTP_UNAUTHORIZED) onUnauthorized?.invoke()
                }
            }
            is RefreshOutcome.SignedOut -> {
                onUnauthorized?.invoke()
                response
            }
            RefreshOutcome.Unavailable -> {
                // Reported as a network failure: the session may still be good,
                // and a 401 here would make callers sign the user out.
                response.close()
                throw IOException("Could not reach Samosa AI to renew the session")
            }
        }
    }

    private fun Request.withToken(token: String): Request =
        newBuilder().header(AUTHORIZATION, "$BEARER$token").build()

    private companion object {
        const val AUTHORIZATION = "Authorization"
        const val BEARER = "Bearer "
        const val HTTP_UNAUTHORIZED = 401
        const val EXPIRY_MARGIN_MILLIS = 60_000L
    }
}
