package com.gotcha.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.SettingsRepository
import com.gotcha.testsupport.FakeAndroidKeyStore
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The interceptor against a real HTTP server that behaves like the backend:
 * it accepts exactly one access token at a time and rotates on `/auth/refresh`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SamosaAuthInterceptorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: SettingsRepository
    private lateinit var server: MockWebServer
    private lateinit var backend: FakeBackend
    private val unauthorized = AtomicInteger(0)

    /** Accepts [validAccess]; `/auth/refresh` answers [refreshAnswer] and rotates on success. */
    private class FakeBackend(var validAccess: String, var validRefresh: String) : Dispatcher() {
        val refreshCalls = AtomicInteger(0)
        val apiTokens = CopyOnWriteArrayList<String>()
        var refreshAnswer: (() -> MockResponse)? = null

        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path == "/auth/refresh") {
                val n = refreshCalls.incrementAndGet()
                refreshAnswer?.let { return it() }
                if (!request.body.readUtf8().contains(validRefresh)) return MockResponse().setResponseCode(401)
                validAccess = "access-rotated-$n"
                validRefresh = "refresh-rotated-$n"
                return MockResponse().setBody(
                    """{"token":"$validAccess","refreshToken":"$validRefresh","app":"gotcha-android","expires_in":1800}"""
                )
            }
            val token = request.getHeader("Authorization").orEmpty().removePrefix("Bearer ")
            apiTokens += token
            return if (token == validAccess) {
                MockResponse().setBody("ok")
            } else {
                MockResponse().setResponseCode(401).setBody("""{"detail":"Session expired"}""")
            }
        }
    }

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        store = SettingsRepository(context)
        store.clearSamosaSession()
        backend = FakeBackend(validAccess = "access-live", validRefresh = "refresh-1")
        server = MockWebServer().apply {
            dispatcher = backend
            start()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        store.clearSamosaSession()
    }

    private fun session(): SamosaSession {
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SamosaRefreshApi::class.java)
        return SamosaSession(store, HttpSamosaRefresher(api))
    }

    private fun client(
        staticToken: String,
        session: SamosaSession? = session(),
        samosa: Boolean = true
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            SamosaAuthInterceptor(
                staticToken = staticToken,
                onUnauthorized = { unauthorized.incrementAndGet() },
                session = { session },
                isSamosaUrl = { samosa }
            )
        )
        .build()

    private fun OkHttpClient.get(path: String = "/v1/models") =
        newCall(Request.Builder().url(server.url(path)).build()).execute()

    @Test
    fun `an expired access token is renewed and the request retried with the new one`() {
        store.saveSamosaSession("access-expired", "user@example.com", "refresh-1")

        val response = client(staticToken = "access-expired").get()

        assertEquals(200, response.code)
        assertEquals(listOf("access-expired", "access-rotated-1"), backend.apiTokens.toList())
        assertEquals(SamosaTokens("access-rotated-1", "refresh-rotated-1"), store.loadSamosaTokens())
        assertEquals(0, unauthorized.get())
    }

    @Test
    fun `a client built with an old copy of the token sends the live one`() {
        store.saveSamosaSession("access-live", "user@example.com", "refresh-1")

        val response = client(staticToken = "copy-from-an-hour-ago").get()

        assertEquals(200, response.code)
        assertEquals(listOf("access-live"), backend.apiTokens.toList())
        assertEquals(0, backend.refreshCalls.get())
    }

    @Test
    fun `ten parallel requests with an expired token renew once and all succeed`() {
        store.saveSamosaSession("access-expired", "user@example.com", "refresh-1")
        val client = client(staticToken = "access-expired")
        val pool = Executors.newFixedThreadPool(10)

        val codes = (1..10).map { pool.submit<Int> { client.get().use { it.code } } }
            .map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(List(10) { 200 }, codes)
        assertEquals(1, backend.refreshCalls.get())
        assertEquals(0, unauthorized.get())
    }

    @Test
    fun `a token about to expire is renewed before the request is sent`() {
        val expiring = jwtExpiringAt(System.currentTimeMillis() / 1000 + 20)
        backend.validAccess = expiring
        store.saveSamosaSession(expiring, "user@example.com", "refresh-1")

        val response = client(staticToken = expiring).get()

        assertEquals(200, response.code)
        assertEquals(listOf("access-rotated-1"), backend.apiTokens.toList())
    }

    @Test
    fun `a refused renewal signs out and reports unauthorized once`() {
        store.saveSamosaSession("access-expired", "user@example.com", "refresh-revoked")

        val response = client(staticToken = "access-expired").get()

        assertEquals(401, response.code)
        assertEquals(1, unauthorized.get())
        assertEquals(SamosaTokens(), store.loadSamosaTokens())
    }

    @Test
    fun `a banned account is signed out with that reason`() {
        store.saveSamosaSession("access-expired", "user@example.com", "refresh-1")
        backend.refreshAnswer = {
            MockResponse().setResponseCode(403)
                .setBody("""{"detail":{"code":"ACCOUNT_BANNED","message":"Account is banned"}}""")
        }
        val session = session()

        val response = client(staticToken = "access-expired", session = session).get()

        assertEquals(401, response.code)
        assertEquals(1, unauthorized.get())
        assertEquals(SignOutReason.BANNED, session.consumeSignOutReason())
    }

    @Test
    fun `a backend outage during renewal is a network error and keeps the session`() {
        store.saveSamosaSession("access-expired", "user@example.com", "refresh-1")
        backend.refreshAnswer = { MockResponse().setResponseCode(503) }

        try {
            client(staticToken = "access-expired").get()
            fail("expected an IOException")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("renew"))
        }

        assertEquals(0, unauthorized.get())
        assertEquals(SamosaTokens("access-expired", "refresh-1"), store.loadSamosaTokens())
    }

    @Test
    fun `a dropped connection during renewal keeps the session`() {
        store.saveSamosaSession("access-expired", "user@example.com", "refresh-1")
        backend.refreshAnswer = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }

        try {
            client(staticToken = "access-expired").get()
            fail("expected an IOException")
        } catch (_: IOException) {
            // expected
        }

        assertEquals(0, unauthorized.get())
        assertEquals(SamosaTokens("access-expired", "refresh-1"), store.loadSamosaTokens())
    }

    @Test
    fun `a session from a backend without refresh tokens signs out on 401 as before`() {
        store.saveSamosaSession("seven-day-token-expired", "user@example.com")

        val response = client(staticToken = "seven-day-token-expired").get()

        assertEquals(401, response.code)
        assertEquals(1, unauthorized.get())
        assertEquals(0, backend.refreshCalls.get())
        assertEquals("", store.loadSamosaTokens().accessToken)
    }

    @Test
    fun `other providers get the static key, no renewal, and the 401 callback`() {
        store.saveSamosaSession("access-live", "user@example.com", "refresh-1")

        val response = client(staticToken = "sk-someone-elses-key", samosa = false).get()

        assertEquals(401, response.code)
        assertEquals(listOf("sk-someone-elses-key"), backend.apiTokens.toList())
        assertEquals(0, backend.refreshCalls.get())
        assertEquals(1, unauthorized.get())
        assertEquals(SamosaTokens("access-live", "refresh-1"), store.loadSamosaTokens())
    }

    @Test
    fun `a request that carries its own Authorization header is renewed too`() {
        store.saveSamosaSession("access-expired", "user@example.com", "refresh-1")
        val request = Request.Builder().url(server.url("/me"))
            .header("Authorization", "Bearer access-expired").build()

        val response = client(staticToken = "").newCall(request).execute()

        assertEquals(200, response.code)
        assertEquals(listOf("access-expired", "access-rotated-1"), backend.apiTokens.toList())
    }

    @Test
    fun `a request with no token is passed through untouched`() {
        store.saveSamosaSession("access-live", "user@example.com", "refresh-1")

        client(staticToken = "").get("/register")

        assertEquals(listOf(""), backend.apiTokens.toList())
        assertEquals(0, backend.refreshCalls.get())
        assertEquals(0, unauthorized.get())
    }

    @Test
    fun `without an installed session the token is sent as given`() {
        backend.validAccess = "plain"

        val response = client(staticToken = "plain", session = null).get()

        assertEquals(200, response.code)
        assertEquals(listOf("plain"), backend.apiTokens.toList())
    }

    private fun jwtExpiringAt(epochSeconds: Long): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256"}""".toByteArray())
        val payload = encoder.encodeToString("""{"sub":"u","exp":$epochSeconds}""".toByteArray())
        return "$header.$payload.signature"
    }
}
