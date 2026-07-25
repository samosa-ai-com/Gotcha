package com.gotcha.connectors.microsoft

import com.gotcha.connectors.Connector
import com.gotcha.connectors.CredentialStore
import com.gotcha.connectors.mail.EmailFull
import com.gotcha.connectors.mail.EmailSummary
import com.gotcha.connectors.mail.MailBackend
import com.gotcha.connectors.mail.OutgoingEmail
import com.gotcha.connectors.oauth.OAuth2Config
import com.gotcha.connectors.oauth.OAuth2Helper
import com.gotcha.connectors.oauth.OAuthInvalidGrant
import com.gotcha.connectors.oauth.TokenSet
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
data class MicrosoftCredentials(
    val clientId: String,
    /** "common" for personal + work accounts; a tenant id/domain locks it to one org. */
    val tenant: String,
    val refreshToken: String,
    val accountEmail: String,
    val scopes: List<String>,
    /** Set when refresh hit invalid_grant (password change, admin revoke, MFA reset). */
    val needsReconnect: Boolean = false
)

/**
 * Bring-Your-Own-OAuth Microsoft Graph connector. The user registers a public
 * client ("Mobile and desktop applications") in their own Entra tenant, so no
 * client secret exists and no publisher verification is involved. Entra accepts
 * any loopback port for public clients, so the shared
 * [com.gotcha.connectors.oauth.LoopbackRedirectServer] flow works unchanged.
 *
 * One credential backs three services: Outlook mail (via [MailBackend], so it
 * reuses the existing email tools), calendar, and To Do.
 */
@Suppress("TooManyFunctions") // one credential fronts three Graph services (mail, calendar, To Do)
class MicrosoftConnector(
    private val store: CredentialStore,
    private val api: GraphApi = GraphApi(),
    private val oauth: OAuth2Helper = OAuth2Helper(),
    private val authorityOverride: String? = null,
    private val clock: () -> Long = System::currentTimeMillis
) : Connector, MailBackend {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val DEFAULT_TENANT = "common"
        const val SCOPE_MAIL = "Mail.ReadWrite"
        const val SCOPE_MAIL_SEND = "Mail.Send"
        const val SCOPE_CALENDAR = "Calendars.ReadWrite"
        const val SCOPE_TASKS = "Tasks.ReadWrite"

        /** offline_access is what makes Entra return a refresh token at all. */
        val DEFAULT_SCOPES = listOf(
            "offline_access",
            "User.Read",
            SCOPE_MAIL,
            SCOPE_MAIL_SEND,
            SCOPE_CALENDAR,
            SCOPE_TASKS
        )

        private const val EXPIRY_SKEW_MILLIS = 60_000L
    }

    override val id = "microsoft"
    override val displayName = "Microsoft (Outlook, Calendar, To Do)"
    override val description =
        "Outlook mail, calendar and To Do via your own Entra app registration."
    override val toolNames = setOf(
        "list_emails",
        "read_email",
        "send_email",
        "mark_email_read",
        "list_tasks",
        "create_task",
        "complete_task"
    )
    override val idPrefix = "ms"

    @Volatile
    private var credentials: MicrosoftCredentials? = store.loadRaw(id)?.let { blob ->
        runCatching { json.decodeFromString<MicrosoftCredentials>(blob) }.getOrNull()
    }

    // In-memory access token cache; never persisted.
    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var accessTokenExpiresAt: Long = 0

    private fun persist(creds: MicrosoftCredentials) {
        store.saveRaw(id, json.encodeToString(creds))
        credentials = creds
    }

    fun credentials(): MicrosoftCredentials? = credentials

    fun needsReconnect(): Boolean = credentials?.needsReconnect == true

    fun hasScope(scope: String): Boolean = credentials?.scopes?.contains(scope) == true

    override fun isConnected(): Boolean = credentials?.needsReconnect == false

    override fun statusLine(): String = when {
        credentials == null -> "Not connected"
        credentials?.needsReconnect == true ->
            "Reconnect needed — the saved sign-in expired or was revoked."
        else -> "Connected as ${credentials?.accountEmail}"
    }

    override fun disconnect() {
        store.clear(id)
        credentials = null
        accessToken = null
        accessTokenExpiresAt = 0
    }

    private fun authority(tenant: String): String =
        authorityOverride ?: "https://login.microsoftonline.com/$tenant/oauth2/v2.0"

    fun oauthConfig(
        clientId: String,
        tenant: String = DEFAULT_TENANT,
        scopes: List<String> = DEFAULT_SCOPES
    ) = OAuth2Config(
        authUrl = "${authority(tenant)}/authorize",
        tokenUrl = "${authority(tenant)}/token",
        clientId = clientId,
        // Public client: PKCE only. Sending a secret makes Entra reject the request.
        clientSecret = null,
        scopes = scopes,
        extraAuthParams = mapOf("response_mode" to "query", "prompt" to "select_account")
    )

    /**
     * Finish connecting after the OAuth code exchange: identify the account via
     * `GET /me` and persist the credential blob.
     */
    suspend fun completeConnect(
        clientId: String,
        tenant: String,
        tokens: TokenSet,
        scopes: List<String> = DEFAULT_SCOPES
    ) {
        val refreshToken = checkNotNull(tokens.refreshToken) {
            "Microsoft did not return a refresh token — make sure the 'offline_access' " +
                "permission is included and try connecting again."
        }
        val email = api.me(tokens.accessToken)
        persist(
            MicrosoftCredentials(
                clientId = clientId,
                tenant = tenant.ifBlank { DEFAULT_TENANT },
                refreshToken = refreshToken,
                accountEmail = email,
                scopes = scopes
            )
        )
        accessToken = tokens.accessToken
        accessTokenExpiresAt = tokens.expiresAtMillis
    }

    /** Valid access token, refreshing proactively when missing/expiring. */
    private suspend fun token(forceRefresh: Boolean = false): String {
        val creds = checkNotNull(credentials) { "Microsoft is not connected." }
        check(!creds.needsReconnect) {
            "The Microsoft sign-in expired — ask the user to reconnect in Settings → Connectors."
        }
        val cached = accessToken
        if (!forceRefresh && cached != null && clock() < accessTokenExpiresAt - EXPIRY_SKEW_MILLIS) {
            return cached
        }
        try {
            val tokens = oauth.refresh(
                oauthConfig(creds.clientId, creds.tenant, creds.scopes),
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
                "The Microsoft sign-in expired (invalid_grant) — ask the user to reconnect in " +
                    "Settings → Connectors.",
                e
            )
        }
    }

    /** Runs [block] with a token, force-refreshing and retrying once on a 401. */
    private suspend fun <T> withAuth(block: suspend (String) -> T): T {
        return try {
            block(token())
        } catch (e: GraphApiException) {
            if (e.code != 401) throw e
            block(token(forceRefresh = true))
        }
    }

    // ---- MailBackend (Outlook via Graph) ----

    override suspend fun list(query: String?, unreadOnly: Boolean, max: Int): List<EmailSummary> =
        withAuth { tok ->
            // Graph cannot combine $search with $filter, so unread filtering for a text
            // query happens here instead of server-side.
            val rows = api.listMessages(tok, query, unreadOnly, max)
                .map { GraphMailParser.summary(it.jsonObject) }
            if (unreadOnly && !query.isNullOrBlank()) rows.filter { it.unread } else rows
        }

    override suspend fun read(id: String): EmailFull = withAuth { tok ->
        GraphMailParser.full(id, api.getMessage(tok, GraphMailParser.stripPrefix(id)))
    }

    override suspend fun send(message: OutgoingEmail): String = withAuth { tok ->
        api.sendMail(tok, GraphMailParser.outgoing(message))
        "Email sent to ${message.to.joinToString()} via Outlook."
    }

    override suspend fun markRead(id: String, read: Boolean) {
        withAuth { tok -> api.setRead(tok, GraphMailParser.stripPrefix(id), read) }
    }

    // ---- Calendar (used by the shared calendar router) ----

    suspend fun calendarView(startIso: String, endIso: String, max: Int): JsonArray =
        withAuth { tok -> api.calendarView(tok, startIso, endIso, max) }

    suspend fun createEvent(event: JsonObject): String =
        withAuth { tok -> api.createEvent(tok, event) }

    suspend fun busyBlocks(startIso: String, endIso: String, intervalMinutes: Int): JsonArray =
        withAuth { tok ->
            val me = credentials?.accountEmail ?: return@withAuth JsonArray(emptyList())
            api.getSchedule(tok, listOf(me), startIso, endIso, intervalMinutes)
        }

    // ---- To Do ----

    suspend fun todoLists(): JsonArray = withAuth { tok -> api.todoLists(tok) }

    suspend fun todoTasks(listId: String, includeCompleted: Boolean, max: Int): JsonArray =
        withAuth { tok -> api.todoTasks(tok, listId, includeCompleted, max) }

    suspend fun createTodoTask(listId: String, task: JsonObject): String =
        withAuth { tok -> api.createTodoTask(tok, listId, task) }

    suspend fun completeTodoTask(listId: String, taskId: String, completed: Boolean) {
        withAuth { tok -> api.setTodoTaskCompleted(tok, listId, taskId, completed) }
    }

    /** Default list id (the built-in "Tasks" list) used when the caller names no list. */
    suspend fun defaultTodoListId(): String? {
        val lists = todoLists().map { it.jsonObject }
        val wellKnown = lists.firstOrNull {
            it["wellknownListName"]?.jsonPrimitive?.contentOrNull == "defaultList"
        }
        return (wellKnown ?: lists.firstOrNull())?.get("id")?.jsonPrimitive?.contentOrNull
    }
}
