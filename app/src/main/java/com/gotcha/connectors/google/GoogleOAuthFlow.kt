package com.gotcha.connectors.google

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.gotcha.connectors.oauth.LoopbackRedirectServer
import com.gotcha.connectors.oauth.OAuth2Helper
import com.gotcha.connectors.oauth.Pkce
import java.util.UUID

/**
 * Drives the desktop-app loopback OAuth flow for [GoogleConnector] from the
 * Settings UI: build the authorization URL with PKCE, open it in a Custom Tab,
 * wait on a transient loopback listener for the redirect, then exchange the
 * code. A Desktop-type OAuth client needs no redirect-URI registration, so any
 * ephemeral 127.0.0.1 port works.
 */
class GoogleOAuthFlow(
    private val context: Context,
    private val connector: GoogleConnector,
    private val oauth: OAuth2Helper = OAuth2Helper()
) {

    sealed class Outcome {
        data class Connected(val email: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    // Kept from the most recent connect() attempt so the paste-URL fallback can
    // exchange a code the user copied out of a Custom Tab that died mid-flow.
    private var lastRedirectUri: String? = null
    private var lastVerifier: String? = null
    private var lastState: String? = null

    /**
     * Launches the Custom Tab and suspends until the loopback server captures
     * the redirect (or times out after 5 minutes). Call from a coroutine scope
     * tied to the Settings screen.
     */
    suspend fun connect(clientId: String, clientSecret: String): Outcome {
        val cfg = connector.oauthConfig(clientId.trim(), clientSecret.trim())
        val server = LoopbackRedirectServer()
        val verifier = Pkce.generateVerifier()
        val challenge = Pkce.challengeS256(verifier)
        val state = UUID.randomUUID().toString()
        lastRedirectUri = server.redirectUri
        lastVerifier = verifier
        lastState = state

        return try {
            val authUrl = oauth.buildAuthorizationUrl(cfg, server.redirectUri, state, challenge)
            openCustomTab(authUrl)

            when (val result = server.awaitCode(state)) {
                is LoopbackRedirectServer.Result.Error -> Outcome.Failed(result.message)
                is LoopbackRedirectServer.Result.Code -> {
                    val tokens = oauth.exchangeCode(cfg, result.code, server.redirectUri, verifier)
                    connector.completeConnect(clientId.trim(), clientSecret.trim(), tokens)
                    relaunchApp()
                    Outcome.Connected(connector.credentials()?.accountEmail.orEmpty())
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
     * `http://127.0.0.1:.../?code=...&state=...` URL manually. Reuses the
     * redirect URI, PKCE verifier and state from the most recent [connect]
     * attempt, so this only works right after calling it.
     */
    suspend fun connectWithPastedUrl(clientId: String, clientSecret: String, pastedUrl: String): Outcome {
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
        val cfg = connector.oauthConfig(clientId.trim(), clientSecret.trim())
        return try {
            val tokens = oauth.exchangeCode(cfg, code, redirectUri, verifier)
            connector.completeConnect(clientId.trim(), clientSecret.trim(), tokens)
            Outcome.Connected(connector.credentials()?.accountEmail.orEmpty())
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
