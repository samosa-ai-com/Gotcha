package com.gotcha.connectors.google

import com.gotcha.connectors.Connector
import com.gotcha.connectors.CredentialStore
import com.gotcha.connectors.mail.EmailFull
import com.gotcha.connectors.mail.EmailSummary
import com.gotcha.connectors.mail.MailBackend
import com.gotcha.connectors.mail.MailBodyExtractor
import com.gotcha.connectors.mail.OutgoingEmail
import com.gotcha.connectors.oauth.OAuth2Config
import com.gotcha.connectors.oauth.OAuth2Helper
import com.gotcha.connectors.oauth.OAuthInvalidGrant
import com.gotcha.connectors.oauth.TokenSet
import com.gotcha.tools.MimeMessageBuilder
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class GoogleCredentials(
    val clientId: String,
    val clientSecret: String,
    val refreshToken: String,
    val accountEmail: String,
    val scopes: List<String>,
    /** Set when refresh hit invalid_grant (e.g. 7-day testing-status expiry). */
    val needsReconnect: Boolean = false
)

/**
 * n8n-style Bring-Your-Own-OAuth Google connector: the user pastes credentials
 * of a Desktop-type OAuth client from their own Google Cloud project, so
 * restricted scopes need no CASA verification. One credential is shared by all
 * Google services: the scopes actually granted are stored alongside it, so
 * [hasScope]/[hasCalendar] can steer per-service tools when the user connected
 * for Gmail only.
 */
@Suppress("TooManyFunctions") // one credential fronts two Google services (Gmail, Calendar)
class GoogleConnector(
    private val store: CredentialStore,
    private val api: GmailApi = GmailApi(),
    private val calendarApi: GoogleCalendarApi = GoogleCalendarApi(),
    private val oauth: OAuth2Helper = OAuth2Helper(),
    private val tokenUrl: String = TOKEN_URL,
    private val clock: () -> Long = System::currentTimeMillis
) : Connector, MailBackend {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val SCOPE_GMAIL_MODIFY = "https://www.googleapis.com/auth/gmail.modify"
        const val SCOPE_CALENDAR = "https://www.googleapis.com/auth/calendar"
        private const val EXPIRY_SKEW_MILLIS = 60_000L
    }

    override val id = "google"
    override val displayName = "Google (BYO OAuth)"
    override val description =
        "Gmail and Google Calendar via your own Google Cloud OAuth client (no app passwords)."
    override val toolNames = setOf(
        "list_emails",
        "read_email",
        "send_email",
        "mark_email_read",
        "list_calendar_events",
        "create_calendar_event",
        "check_availability"
    )
    override val idPrefix = "gmail"
    override val spec = com.gotcha.connectors.ConnectorCatalog.GOOGLE

    @Volatile
    private var credentials: GoogleCredentials? = store.loadRaw(id)?.let { blob ->
        runCatching { json.decodeFromString<GoogleCredentials>(blob) }.getOrNull()
    }

    // In-memory access token cache; never persisted.
    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var accessTokenExpiresAt: Long = 0

    private fun persist(creds: GoogleCredentials) {
        store.saveRaw(id, json.encodeToString(creds))
        credentials = creds
    }

    fun credentials(): GoogleCredentials? = credentials

    fun needsReconnect(): Boolean = credentials?.needsReconnect == true

    fun hasScope(scope: String): Boolean = credentials?.scopes?.contains(scope) == true

    override fun isConnected(): Boolean = credentials?.needsReconnect == false

    override fun statusLine(): String = when {
        credentials == null -> "Not connected"
        credentials?.needsReconnect == true ->
            "Reconnect needed — the saved sign-in expired (consent screens in " +
                "\"Testing\" status expire every 7 days; publish to production to stop this)."
        else -> "Connected as ${credentials?.accountEmail}"
    }

    override suspend fun refreshTools(): String {
        val creds = credentials ?: return "Not connected"
        if (creds.needsReconnect) return statusLine()
        return try {
            val tok = token(forceRefresh = true)
            val email = api.profileEmail(tok)
            if (email != creds.accountEmail) {
                persist(creds.copy(accountEmail = email))
            }
            statusLine()
        } catch (e: Exception) {
            statusLine()
        }
    }

    override fun disconnect() {
        // Best-effort revoke so the grant disappears from the user's Google account.
        credentials?.refreshToken?.let { revokeQuietly(it) }
        store.clear(id)
        credentials = null
        accessToken = null
        accessTokenExpiresAt = 0
    }

    private fun revokeQuietly(token: String) {
        Thread {
            runCatching {
                val client = okhttp3.OkHttpClient()
                val body = okhttp3.FormBody.Builder().add("token", token).build()
                client.newCall(
                    okhttp3.Request.Builder()
                        .url("https://oauth2.googleapis.com/revoke")
                        .post(body)
                        .build()
                ).execute().close()
            }
        }.start()
    }

    fun oauthConfig(
        clientId: String,
        clientSecret: String,
        scopes: List<String> = listOf(SCOPE_GMAIL_MODIFY)
    ) = OAuth2Config(
        authUrl = AUTH_URL,
        tokenUrl = tokenUrl,
        clientId = clientId,
        clientSecret = clientSecret,
        scopes = scopes,
        extraAuthParams = mapOf("access_type" to "offline", "prompt" to "consent")
    )

    /**
     * Finish connecting after the OAuth code exchange: identify the account via
     * users/me/profile and persist the credential blob. [scopes] must be the
     * scopes actually requested, so [hasScope] can steer per-service tools.
     */
    suspend fun completeConnect(
        clientId: String,
        clientSecret: String,
        tokens: TokenSet,
        scopes: List<String> = listOf(SCOPE_GMAIL_MODIFY)
    ) {
        val refreshToken = checkNotNull(tokens.refreshToken) {
            "Google did not return a refresh token — remove the app's access at " +
                "myaccount.google.com/permissions and try connecting again."
        }
        val email = api.profileEmail(tokens.accessToken)
        val creds = GoogleCredentials(
            clientId = clientId,
            clientSecret = clientSecret,
            refreshToken = refreshToken,
            accountEmail = email,
            scopes = scopes
        )
        persist(creds)
        accessToken = tokens.accessToken
        accessTokenExpiresAt = tokens.expiresAtMillis
    }

    /** Valid access token, refreshing proactively when missing/expiring. */
    private suspend fun token(forceRefresh: Boolean = false): String {
        val creds = checkNotNull(credentials) { "Google is not connected." }
        check(!creds.needsReconnect) {
            "The Google sign-in expired — ask the user to reconnect in Settings → Connectors."
        }
        val cached = accessToken
        if (!forceRefresh && cached != null && clock() < accessTokenExpiresAt - EXPIRY_SKEW_MILLIS) {
            return cached
        }
        try {
            val tokens = oauth.refresh(
                oauthConfig(creds.clientId, creds.clientSecret, creds.scopes),
                creds.refreshToken
            )
            accessToken = tokens.accessToken
            accessTokenExpiresAt = tokens.expiresAtMillis
            tokens.refreshToken?.takeIf { it != creds.refreshToken }?.let { rotated ->
                persist(creds.copy(refreshToken = rotated))
            }
            return tokens.accessToken
        } catch (e: OAuthInvalidGrant) {
            persist(creds.copy(needsReconnect = true))
            accessToken = null
            throw IllegalStateException(
                "The Google sign-in expired (invalid_grant) — ask the user to reconnect in " +
                    "Settings → Connectors. Tip: publishing the consent screen to \"In production\" " +
                    "stops the weekly expiry.",
                e
            )
        }
    }

    /** Runs [block] with a token, force-refreshing and retrying once on a 401. */
    private suspend fun <T> withAuth(block: suspend (String) -> T): T {
        return try {
            block(token())
        } catch (e: GmailApiException) {
            if (e.code != 401) throw e
            block(token(forceRefresh = true))
        }
    }

    // ---- MailBackend (Gmail REST) ----

    override suspend fun list(query: String?, unreadOnly: Boolean, max: Int): List<EmailSummary> =
        withAuth { tok ->
            api.listMessageIds(tok, query, unreadOnly, max).map { msgId ->
                val meta = api.getMessageMetadata(tok, msgId)
                val headers = GmailMessageParser.headerMap(meta)
                EmailSummary(
                    id = "gmail:$msgId",
                    from = headers["from"].orEmpty(),
                    subject = headers["subject"] ?: "(no subject)",
                    date = headers["date"].orEmpty(),
                    unread = GmailMessageParser.labelIds(meta).contains("UNREAD"),
                    snippet = MailBodyExtractor.snippet(
                        meta["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    )
                )
            }
        }

    override suspend fun read(id: String): EmailFull = withAuth { tok ->
        val msgId = GmailMessageParser.stripPrefix(id)
        val full = api.getMessageFull(tok, msgId)
        val headers = GmailMessageParser.headerMap(full)
        EmailFull(
            id = id,
            from = headers["from"].orEmpty(),
            to = headers["to"].orEmpty(),
            cc = headers["cc"].orEmpty(),
            subject = headers["subject"] ?: "(no subject)",
            date = headers["date"].orEmpty(),
            body = GmailMessageParser.extractBody(full["payload"]?.jsonObject)
        )
    }

    override suspend fun send(message: OutgoingEmail): String = withAuth { tok ->
        val creds = checkNotNull(credentials) { "Google is not connected." }
        val raw = MimeMessageBuilder.build(
            from = creds.accountEmail,
            to = message.to,
            cc = message.cc,
            bcc = message.bcc,
            subject = message.subject,
            body = message.body
        )
        val newId = api.sendRaw(tok, MimeMessageBuilder.toBase64Url(raw))
        "Email sent to ${message.to.joinToString()} via Gmail (id gmail:$newId)."
    }

    override suspend fun markRead(id: String, read: Boolean) {
        withAuth { tok -> api.setUnread(tok, GmailMessageParser.stripPrefix(id), unread = !read) }
    }

    // ---- Google Calendar (used by the shared calendar router) ----

    /** True when the stored grant includes the Calendar scope. */
    fun hasCalendar(): Boolean = hasScope(SCOPE_CALENDAR)

    suspend fun listCalendarEvents(
        calendarId: String,
        timeMin: String,
        timeMax: String,
        query: String?,
        max: Int
    ): JsonArray = withAuth { tok ->
        calendarApi.listEvents(tok, calendarId, timeMin, timeMax, query, max)
    }

    suspend fun insertCalendarEvent(calendarId: String, event: JsonObject): String =
        withAuth { tok -> calendarApi.insertEvent(tok, calendarId, event) }

    suspend fun freeBusy(calendarIds: List<String>, timeMin: String, timeMax: String): JsonObject =
        withAuth { tok -> calendarApi.freeBusy(tok, calendarIds, timeMin, timeMax) }

    suspend fun calendarList(): JsonArray = withAuth { tok -> calendarApi.calendarList(tok) }
}
