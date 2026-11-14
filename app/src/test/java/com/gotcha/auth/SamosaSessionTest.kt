package com.gotcha.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.SettingsRepository
import com.gotcha.testsupport.FakeAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SamosaSessionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: SettingsRepository

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        store = SettingsRepository(context)
        store.clearSamosaSession()
    }

    @After
    fun tearDown() {
        store.clearSamosaSession()
    }

    private class CountingRefresher(var reply: RefreshReply) : SamosaRefresher {
        val calls = AtomicInteger(0)
        var lastRefreshToken: String? = null
        override fun refresh(refreshToken: String): RefreshReply {
            calls.incrementAndGet()
            lastRefreshToken = refreshToken
            return reply
        }
    }

    @Test
    fun `a renewal stores the new pair and spends the stored refresh token`() {
        store.saveSamosaSession("access-1", "user@example.com", "refresh-1")
        val refresher = CountingRefresher(RefreshReply.NewPair("access-2", "refresh-2"))

        val outcome = SamosaSession(store, refresher).refresh("access-1")

        assertEquals(RefreshOutcome.Refreshed("access-2"), outcome)
        assertEquals("refresh-1", refresher.lastRefreshToken)
        assertEquals(SamosaTokens("access-2", "refresh-2"), store.loadSamosaTokens())
        assertEquals("user@example.com", store.load().samosaEmail)
    }

    @Test
    fun `a caller holding an already replaced token gets the stored one without a network call`() {
        store.saveSamosaSession("access-2", "user@example.com", "refresh-2")
        val refresher = CountingRefresher(RefreshReply.NewPair("access-3", "refresh-3"))

        val outcome = SamosaSession(store, refresher).refresh("access-1")

        assertEquals(RefreshOutcome.Refreshed("access-2"), outcome)
        assertEquals(0, refresher.calls.get())
    }

    @Test
    fun `parallel callers with the same stale token renew exactly once`() {
        store.saveSamosaSession("access-1", "user@example.com", "refresh-1")
        val refresher = object : SamosaRefresher {
            val calls = AtomicInteger(0)
            override fun refresh(refreshToken: String): RefreshReply {
                val n = calls.incrementAndGet()
                Thread.sleep(50) // long enough for every other caller to be waiting on the lock
                return RefreshReply.NewPair("access-${n + 1}", "refresh-${n + 1}")
            }
        }
        val session = SamosaSession(store, refresher)
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)

        val results = (1..10).map {
            pool.submit<RefreshOutcome> {
                start.await()
                session.refresh("access-1")
            }
        }
        start.countDown()
        val outcomes = results.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, refresher.calls.get())
        assertTrue(outcomes.all { it == RefreshOutcome.Refreshed("access-2") })
    }

    @Test
    fun `a refused renewal clears the session and reports why once`() {
        store.saveSamosaSession("access-1", "user@example.com", "refresh-1")
        val session = SamosaSession(store, CountingRefresher(RefreshReply.Rejected(SignOutReason.BANNED)))

        val outcome = session.refresh("access-1")

        assertEquals(RefreshOutcome.SignedOut(SignOutReason.BANNED), outcome)
        assertEquals(SamosaTokens(), store.loadSamosaTokens())
        assertEquals("", store.load().samosaEmail)
        assertEquals(SignOutReason.BANNED, session.consumeSignOutReason())
        assertNull(session.consumeSignOutReason())
    }

    @Test
    fun `an unreachable backend leaves the session untouched`() {
        store.saveSamosaSession("access-1", "user@example.com", "refresh-1")
        val session = SamosaSession(store, CountingRefresher(RefreshReply.Unreachable))

        assertEquals(RefreshOutcome.Unavailable, session.refresh("access-1"))
        assertEquals(SamosaTokens("access-1", "refresh-1"), store.loadSamosaTokens())
    }

    @Test
    fun `a session without a refresh token ends on renewal, as it did before refresh tokens`() {
        store.saveSamosaSession("old-style-token", "user@example.com")
        val refresher = CountingRefresher(RefreshReply.NewPair("x", "y"))

        val outcome = SamosaSession(store, refresher).refresh("old-style-token")

        assertEquals(RefreshOutcome.SignedOut(SignOutReason.EXPIRED), outcome)
        assertEquals(0, refresher.calls.get())
        assertEquals("", store.loadSamosaTokens().accessToken)
    }

    @Test
    fun `renewing when already signed out makes no network call`() {
        val refresher = CountingRefresher(RefreshReply.NewPair("x", "y"))

        val outcome = SamosaSession(store, refresher).refresh("access-1")

        assertEquals(RefreshOutcome.SignedOut(SignOutReason.EXPIRED), outcome)
        assertEquals(0, refresher.calls.get())
    }

    @Test
    fun `saving settings does not put back a token that has since been renewed`() {
        store.saveSamosaSession("access-1", "user@example.com", "refresh-1")
        val heldByAScreen = store.load()
        store.rotateSamosaTokens("access-2", "refresh-2")

        store.save(heldByAScreen.copy(model = "another-model"))

        assertEquals(SamosaTokens("access-2", "refresh-2"), store.loadSamosaTokens())
        assertEquals("access-2", store.load().samosaSessionToken)
        assertEquals("user@example.com", store.load().samosaEmail)
    }

    @Test
    fun `expiry is read from the token and unreadable tokens never count as expiring`() {
        val session = SamosaSession(store, CountingRefresher(RefreshReply.Unreachable), nowMillis = { 1_000_000L })

        assertTrue(session.expiresWithin(jwtExpiringAt(1_030), 60_000L))
        assertFalse(session.expiresWithin(jwtExpiringAt(1_100), 60_000L))
        assertTrue(session.expiresWithin(jwtExpiringAt(900), 60_000L))
        assertFalse(session.expiresWithin("not-a-jwt", 60_000L))
        assertFalse(session.expiresWithin("a.b.c", 60_000L))
    }

    @Test
    fun `refresh answers map to ending the session only when they are about the session`() {
        val expired = RefreshReply.Rejected(SignOutReason.EXPIRED)
        assertEquals(expired, HttpSamosaRefresher.rejection(401, """{"detail":"Invalid refresh token"}"""))
        assertEquals(expired, HttpSamosaRefresher.rejection(404, ""))
        assertEquals(
            RefreshReply.Rejected(SignOutReason.BANNED),
            HttpSamosaRefresher.rejection(403, """{"detail":{"code":"ACCOUNT_BANNED","message":"Account is banned"}}""")
        )
        assertEquals(
            RefreshReply.Rejected(SignOutReason.DEACTIVATED),
            HttpSamosaRefresher.rejection(403, """{"detail":{"code":"ACCOUNT_DEACTIVATED","message":"x"}}""")
        )
        assertEquals(RefreshReply.Unreachable, HttpSamosaRefresher.rejection(403, "<html>blocked</html>"))
        assertEquals(RefreshReply.Unreachable, HttpSamosaRefresher.rejection(429, ""))
        assertEquals(RefreshReply.Unreachable, HttpSamosaRefresher.rejection(502, ""))
    }

    @Test
    fun `sign-in stores the refresh token and names the app and device`() = runBlocking {
        val api = object : SamosaAuthApi {
            var request: RegisterRequest? = null
            override suspend fun register(body: RegisterRequest): RegisterResponse {
                request = body
                return RegisterResponse(
                    token = "access-1",
                    refreshToken = "refresh-1",
                    user = SamosaUser(email = "user@example.com")
                )
            }
            override suspend fun me(bearer: String) = MeResponse(SamosaUser(email = "user@example.com"))
            override suspend fun claimReferral(bearer: String, body: ClaimReferralRequest) = ClaimReferralResponse()
            override suspend fun logout(bearer: String) = Unit
        }

        val result = SamosaAuthManager(context, store, api).register("google-id-token")

        assertTrue(result is SamosaSignInResult.Success)
        assertEquals(SamosaTokens("access-1", "refresh-1"), store.loadSamosaTokens())
        assertEquals("gotcha-android", api.request?.app)
        assertFalse(api.request?.deviceLabel.isNullOrBlank())
    }

    @Test
    fun `sign-in against a backend without refresh tokens stores only the access token`() = runBlocking {
        val api = object : SamosaAuthApi {
            override suspend fun register(body: RegisterRequest) =
                RegisterResponse(token = "seven-day-token", user = SamosaUser(email = "user@example.com"))
            override suspend fun me(bearer: String) = MeResponse(SamosaUser(email = "user@example.com"))
            override suspend fun claimReferral(bearer: String, body: ClaimReferralRequest) = ClaimReferralResponse()
            override suspend fun logout(bearer: String) = Unit
        }

        SamosaAuthManager(context, store, api).register("google-id-token")

        assertEquals(SamosaTokens("seven-day-token", ""), store.loadSamosaTokens())
    }

    private fun jwtExpiringAt(epochSeconds: Long): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256"}""".toByteArray())
        val payload = encoder.encodeToString("""{"sub":"u","exp":$epochSeconds}""".toByteArray())
        return "$header.$payload.signature"
    }
}
