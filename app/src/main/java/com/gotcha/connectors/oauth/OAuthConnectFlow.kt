package com.gotcha.connectors.oauth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import java.util.UUID

/**
 * Provider-generic loopback OAuth flow driven from the Settings UI: build the
 * authorization URL with PKCE, open it in a Custom Tab, wait on a transient
 * loopback listener for the redirect, then exchange the code and hand the tokens
 * to the connector.
 *
 * Works for any provider whose client type permits an unregistered
 * `http://127.0.0.1:{ephemeral}` redirect — Google Desktop-app clients and
 * Microsoft Entra public clients both do.
 *
 * @param configFor builds the provider config from the credentials typed into the card.
 * @param onTokens persists the token set and identifies the account; may throw with a
 *   user-facing message.
 * @param accountLabel account identity to report after [onTokens] succeeded.
 */
class OAuthConnectFlow(
    private val context: Context,
    private val configFor: (clientId: String, clientSecret: String?) -> OAuth2Config,
    private val onTokens: suspend (clientId: String, clientSecret: String?, tokens: TokenSet) -> Unit,
    private val accountLabel: () -> String,
    private val oauth: OAuth2Helper = OAuth2Helper()
) {

    sealed class Outcome {
        data class Connected(val account: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    // Kept from the most recent connect() attempt so the paste-URL fallback can
    // exchange a code the user copied out of a Custom Tab that died mid-flow.
    private var lastRedirectUri: String? = null
    private var lastVerifier: String? = null
    private var lastState: String? = null

    /**
     * Launches the Custom Tab and suspends until the loopback server captures the
     * redirect (or times out after 5 minutes). Call from a coroutine scope tied to
     * the Settings screen.
     */
    suspend fun connect(clientId: String, clientSecret: String? = null): Outcome {
        val id = clientId.trim()
        val secret = clientSecret?.trim()
        val cfg = configFor(id, secret)
        val server = LoopbackRedirectServer()
        val verifier = Pkce.generateVerifier()
        val challenge = Pkce.challengeS256(verifier)
        val state = UUID.randomUUID().toString()
        lastRedirectUri = server.redirectUri
        lastVerifier = verifier
        lastState = state

        return try {
            openCustomTab(oauth.buildAuthorizationUrl(cfg, server.redirectUri, state, challenge))

            when (val result = server.awaitCode(state)) {
                is LoopbackRedirectServer.Result.Error -> Outcome.Failed(result.message)
                is LoopbackRedirectServer.Result.Code -> {
                    val tokens = oauth.exchangeCode(cfg, result.code, server.redirectUri, verifier)
                    onTokens(id, secret, tokens)
                    relaunchApp()
                    Outcome.Connected(accountLabel())
                }
            }
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: "Connection failed.")
        } finally {
            server.close()
        }
    }

    /**
     * Fallback for a died Custom Tab listener: the user pastes the final
     * `http://127.0.0.1:.../?code=...&state=...` URL manually. Reuses the redirect
     * URI, PKCE verifier and state from the most recent [connect] attempt, so this
     * only works right after calling it.
     */
    suspend fun connectWithPastedUrl(
        clientId: String,
        clientSecret: String? = null,
        pastedUrl: String
    ): Outcome {
        val redirectUri = lastRedirectUri
        val verifier = lastVerifier
        val expectedState = lastState
        if (redirectUri == null || verifier == null || expectedState == null) {
            return Outcome.Failed("Start Connect first, then paste the redirect URL if the browser didn't return.")
        }
        val uri = Uri.parse(pastedUrl.trim())
        val code = uri.getQueryParameter("code")
            ?: return Outcome.Failed("That URL doesn't contain a 'code' parameter.")
        if (uri.getQueryParameter("state") != expectedState) {
            return Outcome.Failed("State mismatch — start Connect again and paste the new redirect URL.")
        }
        val id = clientId.trim()
        val secret = clientSecret?.trim()
        return try {
            val tokens = oauth.exchangeCode(configFor(id, secret), code, redirectUri, verifier)
            onTokens(id, secret, tokens)
            Outcome.Connected(accountLabel())
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: "Connection failed.")
        }
    }

    private fun openCustomTab(url: String) {
        val intent = CustomTabsIntent.Builder().build()
        try {
            intent.launchUrl(context, Uri.parse(url))
        } catch (ignored: ActivityNotFoundException) {
            // No Custom Tabs-capable browser — fall back to a plain view intent.
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun relaunchApp() {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        context.startActivity(launchIntent)
    }
}
