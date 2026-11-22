package com.gotcha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gotcha.R
import com.gotcha.auth.SamosaUser
import com.gotcha.data.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * The name the drawer and the Account screen show for a signed-in user: their
 * profile name, else their email, else null so the caller can use a generic label.
 */
internal fun accountDisplayName(displayName: String, email: String): String? =
    displayName.trim().ifEmpty { email.trim() }.ifEmpty { null }

/** Up to two initials for the avatar: "Priya Sharma" → "PS", "priya@x.com" → "P". */
internal fun accountInitials(name: String): String {
    val words = name.substringBefore('@').split(' ', '.', '_', '-').filter { it.isNotBlank() }
    return words.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
}

/** A round badge with the user's initials; the profile has no picture loader yet. */
@Composable
fun AccountAvatar(name: String, size: Int = 40) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            accountInitials(name),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
            fontSize = (size * 0.4f).sp
        )
    }
}

/**
 * Samosa AI account, opened from the drawer: sign in when signed out; when
 * signed in, the user's dashboard — plan, credits, account website, referrals —
 * and the only place to log out. AI Configuration and Speech show just the
 * sign-in and a link to manage models.
 */
@Composable
fun AccountScreen(
    load: () -> Settings,
    onBack: () -> Unit,
    onSignIn: suspend () -> Result<Pair<String, String>>,
    /** Google sign-in when the account website is the main sign-in; see AiConfigScreen. */
    onSignInWithGoogle: (suspend () -> Result<Pair<String, String>>)?,
    sessionChanges: Flow<Unit> = emptyFlow(),
    onSignOut: suspend () -> Unit,
    onFetchProfile: suspend () -> SamosaUser?,
    onClaimReferral: suspend (String) -> Result<Unit>
) {
    val initial = remember { load() }
    var token by remember { mutableStateOf(initial.samosaSessionToken) }
    var email by remember { mutableStateOf(initial.samosaEmail) }
    var cachedName by remember { mutableStateOf(initial.samosaDisplayName) }
    var user by remember { mutableStateOf<SamosaUser?>(null) }
    var busy by remember { mutableStateOf(false) }
    var referralBusy by remember { mutableStateOf(false) }
    var referralError by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val overlay = rememberSettingsOverlayState()

    LaunchedEffect(token) {
        user = if (token.isBlank()) null else onFetchProfile()
    }
    SamosaSessionSync(
        sessionChanges = sessionChanges,
        onSessionChanged = {
            val current = load()
            token = current.samosaSessionToken
            email = current.samosaEmail
            cachedName = current.samosaDisplayName
        },
        onResumed = {
            // Back from the account website: credits bought there show up.
            if (token.isNotBlank()) scope.launch { onFetchProfile()?.let { user = it } }
        }
    )

    fun runSignIn(startStatus: Int, signIn: suspend () -> Result<Pair<String, String>>) {
        busy = true
        status = context.getString(startStatus)
        scope.launch {
            signIn().onSuccess { (signedInEmail, newToken) ->
                email = signedInEmail
                token = newToken
                status = context.getString(R.string.samosa_signed_in_as, signedInEmail)
            }.onFailure { e ->
                status = e.message ?: context.getString(R.string.samosa_sign_in_failed)
            }
            busy = false
        }
    }

    val signedIn = token.isNotBlank()
    SettingsScaffold(title = stringResource(R.string.account_title), onBack = onBack, overlay = overlay) {
        if (signedIn) {
            val name = accountDisplayName(user?.displayName ?: cachedName, email)
                ?: stringResource(R.string.account_fallback_name)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AccountAvatar(name, size = 48)
                Column {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (email.isNotBlank() && email != name) {
                        Text(
                            email,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        SamosaAuthSection(
            email = email,
            showEmail = false, // the header above already shows it
            signedIn = signedIn,
            busy = busy,
            creditsRemaining = user?.creditsRemaining,
            user = user,
            referralBusy = referralBusy,
            referralError = referralError,
            onClaimReferral = { code ->
                referralBusy = true
                referralError = null
                scope.launch {
                    onClaimReferral(code).onSuccess {
                        val profile = onFetchProfile()
                        user = (profile ?: user)?.let { u -> u.copy(referral = u.referral.copy(canClaim = false)) }
                        status = context.getString(R.string.samosa_invite_applied)
                    }.onFailure { e ->
                        referralError = e.message ?: context.getString(R.string.samosa_invite_failed)
                    }
                    referralBusy = false
                }
            },
            onSignIn = {
                runSignIn(
                    if (onSignInWithGoogle != null) R.string.samosa_opening_sign_in else R.string.samosa_signing_in_google,
                    onSignIn
                )
            },
            onSignInWithGoogle = onSignInWithGoogle?.let { google ->
                { runSignIn(R.string.samosa_signing_in_google, google) }
            },
            onSignOut = {
                busy = true
                status = context.getString(R.string.samosa_signing_out)
                scope.launch {
                    onSignOut()
                    token = ""
                    email = ""
                    cachedName = ""
                    user = null
                    status = context.getString(R.string.samosa_signed_out)
                    busy = false
                }
            }
        )
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
