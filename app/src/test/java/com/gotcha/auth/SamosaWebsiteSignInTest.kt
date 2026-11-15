package com.gotcha.auth

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.gotcha.R
import com.gotcha.connectors.oauth.Pkce
import com.gotcha.data.SettingsRepository
import com.gotcha.testsupport.FakeAndroidKeyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response

/** Sign-in through the account website: URL, redirect checks, code exchange, restarts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SamosaWebsiteSignInTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: SettingsRepository
    private var now = 1_000_000L

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        store = SettingsRepository(context)
        store.clearSamosaSession()
        store.clearPendingSamosaSignIn()
    }

    @After
    fun tearDown() {
        store.clearSamosaSession()
        store.clearPendingSamosaSignIn()
    }

    private class FakeApi : SamosaAuthApi {
        val exchanges = mutableListOf<OAuthTokenRequest>()
        var exchangeError: HttpException? = null
        var response = RegisterResponse(
            token = "access-1",
            refreshToken = "refresh-1",
            user = SamosaUser(email = "user@example.com")
        )

        override suspend fun exchangeCode(body: OAuthTokenRequest): RegisterResponse {
            exchanges += body
            exchangeError?.let { throw it }
            return response
        }

        override suspend fun register(body: RegisterRequest) = error("Google sign-in not expected")
        override suspend fun me(bearer: String) = MeResponse(SamosaUser(email = "user@example.com"))
        override suspend fun claimReferral(bearer: String, body: ClaimReferralRequest) = ClaimReferralResponse()
        override suspend fun logout(bearer: String) = Unit
    }

    /** Records each page it is asked to open; [works] false means no browser. */
    private class FakeBrowser(private val works: Boolean = true) : SamosaBrowser {
        val opened = mutableListOf<Uri>()
        val firstOpen = CompletableDeferred<Uri>()
        override fun open(context: Context, url: Uri): Boolean {
            opened += url
            firstOpen.complete(url)
            return works
        }
    }

    private fun manager(api: SamosaAuthApi, browser: SamosaBrowser = FakeBrowser()) =
        SamosaAuthManager(context, store, api, websiteSignIn = true, browser = browser, nowMillis = { now })

    private fun redirect(state: String?, code: String? = "code-1", error: String? = null): Uri {
        val builder = Uri.parse(SamosaAuthManager.REDIRECT_URI).buildUpon()
        code?.let { builder.appendQueryParameter("code", it) }
        state?.let { builder.appendQueryParameter("state", it) }
        error?.let { builder.appendQueryParameter("error", it) }
        return builder.build()
    }

    private fun httpError(code: Int) =
        HttpException(Response.error<Any>(code, "{}".toResponseBody(null)))

    @Test
    fun `sign-in page names the app, the return link and an S256 challenge`() {
        val verifier = Pkce.generateVerifier()
        val url = SamosaAuthManager.signInUrl(Pkce.challengeS256(verifier), "state-1")

        assertEquals("gotcha-android", url.getQueryParameter("oauth_client"))
        assertEquals("com.gotcha:/oauth2redirect", url.getQueryParameter("oauth_redirect"))
        assertEquals(Pkce.challengeS256(verifier), url.getQueryParameter("oauth_challenge"))
        assertEquals("state-1", url.getQueryParameter("state"))
        // The website accepts S256 only and takes no method from the URL.
        assertNull(url.getQueryParameter("oauth_method"))
        assertTrue(url.toString().startsWith(SamosaDashboard.baseUrl + "/?"))
    }

    @Test
    fun `only the exact return link counts as a sign-in link`() {
        assertTrue(SamosaAuthManager.isSignInRedirect(Uri.parse("com.gotcha:/oauth2redirect?code=a&state=b")))
        assertFalse(SamosaAuthManager.isSignInRedirect(Uri.parse("com.gotcha:/something-else?code=a")))
        assertFalse(SamosaAuthManager.isSignInRedirect(Uri.parse("https://example.com/oauth2redirect")))
    }

    @Test
    fun `a returning sign-in link finishes the waiting sign-in and stores both tokens`() = runBlocking {
        val api = FakeApi()
        val browser = FakeBrowser()
        val auth = manager(api, browser)

        val signIn = async { auth.signInWithWebsite(context) }
        val page = browser.firstOpen.await()
        val outcome = auth.handleRedirect(redirect(page.getQueryParameter("state")))

        assertEquals(SamosaRedirectOutcome.Delivered, outcome)
        val result = signIn.await()
        assertTrue(result is SamosaSignInResult.Success)
        assertEquals("user@example.com", (result as SamosaSignInResult.Success).email)
        assertEquals(SamosaTokens("access-1", "refresh-1"), store.loadSamosaTokens())
        val exchange = api.exchanges.single()
        assertEquals("code-1", exchange.code)
        assertEquals(page.getQueryParameter("oauth_challenge"), Pkce.challengeS256(exchange.codeVerifier))
        assertEquals("com.gotcha:/oauth2redirect", exchange.redirectUri)
        assertTrue(exchange.deviceLabel.orEmpty().length in 1..120)
        assertNull(store.loadPendingSamosaSignIn())
    }

    @Test
    fun `a sign-in link after a restart still signs the user in`() = runBlocking {
        val browser = FakeBrowser()
        val before = manager(FakeApi(), browser)
        val signIn = async { before.signInWithWebsite(context) }
        val page = browser.firstOpen.await()
        // Gotcha is stopped while the browser is open: the waiting screen is gone.
        signIn.cancel()

        val api = FakeApi()
        val after = manager(api)
        val outcome = after.handleRedirect(redirect(page.getQueryParameter("state")))

        assertTrue(outcome is SamosaRedirectOutcome.Finished)
        assertTrue((outcome as SamosaRedirectOutcome.Finished).result is SamosaSignInResult.Success)
        assertEquals("access-1", store.loadSamosaTokens().accessToken)
        assertEquals(1, api.exchanges.size)
    }

    @Test
    fun `a link from another sign-in attempt is refused without spending the code`() = runBlocking {
        val api = FakeApi()
        val browser = FakeBrowser()
        val auth = manager(api, browser)

        val signIn = async { auth.signInWithWebsite(context) }
        browser.firstOpen.await()
        auth.handleRedirect(redirect("someone-elses-state"))

        assertEquals(
            SamosaSignInResult.Error(context.getString(R.string.samosa_auth_sign_in_link_mismatch)),
            signIn.await()
        )
        assertTrue(api.exchanges.isEmpty())
        assertEquals("", store.loadSamosaTokens().accessToken)
    }

    @Test
    fun `a sign-in that waited too long is refused`() = runBlocking {
        val api = FakeApi()
        val browser = FakeBrowser()
        val auth = manager(api, browser)

        val signIn = async { auth.signInWithWebsite(context) }
        val page = browser.firstOpen.await()
        now += SamosaAuthManager.PENDING_TTL_MS + 1
        auth.handleRedirect(redirect(page.getQueryParameter("state")))

        assertEquals(
            SamosaSignInResult.Error(context.getString(R.string.samosa_auth_sign_in_expired)),
            signIn.await()
        )
        assertTrue(api.exchanges.isEmpty())
    }

    @Test
    fun `declining on the website is a cancel, not an error`() = runBlocking {
        val browser = FakeBrowser()
        val auth = manager(FakeApi(), browser)

        val signIn = async { auth.signInWithWebsite(context) }
        val page = browser.firstOpen.await()
        auth.handleRedirect(redirect(page.getQueryParameter("state"), code = null, error = "access_denied"))

        assertEquals(SamosaSignInResult.Cancelled, signIn.await())
    }

    @Test
    fun `a replayed sign-in link does nothing`() = runBlocking {
        val api = FakeApi()
        val browser = FakeBrowser()
        val auth = manager(api, browser)

        val signIn = async { auth.signInWithWebsite(context) }
        val link = redirect(browser.firstOpen.await().getQueryParameter("state"))
        auth.handleRedirect(link)
        signIn.await()

        assertEquals(SamosaRedirectOutcome.Ignored, auth.handleRedirect(link))
        assertEquals(1, api.exchanges.size)
    }

    @Test
    fun `coming back from the browser without signing in cancels the sign-in`() = runBlocking {
        val browser = FakeBrowser()
        val auth = manager(FakeApi(), browser)

        val signIn = async { auth.signInWithWebsite(context) }
        browser.firstOpen.await()
        auth.onHostPaused()
        auth.onHostSettled()

        assertEquals(SamosaSignInResult.Cancelled, signIn.await())
        assertNull(store.loadPendingSamosaSignIn())
    }

    @Test
    fun `a resume before the browser opened does not cancel the sign-in`() = runBlocking {
        val browser = FakeBrowser()
        val auth = manager(FakeApi(), browser)

        val signIn = async { auth.signInWithWebsite(context) }
        val page = browser.firstOpen.await()
        // Gotcha never went to the background, so there is nothing to give up on yet.
        auth.onHostSettled()
        yield()
        assertFalse(signIn.isCompleted)

        auth.handleRedirect(redirect(page.getQueryParameter("state")))
        assertTrue(signIn.await() is SamosaSignInResult.Success)
    }

    @Test
    fun `a second tap joins the sign-in already waiting`() = runBlocking {
        val browser = FakeBrowser()
        val auth = manager(FakeApi(), browser)

        val first = async { auth.signInWithWebsite(context) }
        val page = browser.firstOpen.await()
        val second = async { auth.signInWithWebsite(context) }
        yield()
        auth.handleRedirect(redirect(page.getQueryParameter("state")))

        assertTrue(first.await() is SamosaSignInResult.Success)
        assertTrue(second.await() is SamosaSignInResult.Success)
        assertEquals(1, browser.opened.size)
    }

    @Test
    fun `no browser on the phone is reported and leaves nothing pending`() = runBlocking {
        val auth = manager(FakeApi(), FakeBrowser(works = false))

        val result = auth.signInWithWebsite(context)

        assertEquals(SamosaSignInResult.Error(context.getString(R.string.samosa_auth_no_browser)), result)
        assertNull(store.loadPendingSamosaSignIn())
    }

    @Test
    fun `a code the backend refuses asks the user to start again`() = runBlocking {
        val api = FakeApi().apply { exchangeError = httpError(400) }
        val browser = FakeBrowser()
        val auth = manager(api, browser)

        val signIn = async { auth.signInWithWebsite(context) }
        auth.handleRedirect(redirect(browser.firstOpen.await().getQueryParameter("state")))

        assertEquals(
            SamosaSignInResult.Error(context.getString(R.string.samosa_auth_sign_in_expired)),
            signIn.await()
        )
        assertEquals("", store.loadSamosaTokens().accessToken)
    }

    @Test
    fun `a disabled account is told so`() = runBlocking {
        val api = FakeApi().apply { exchangeError = httpError(403) }
        val browser = FakeBrowser()
        val auth = manager(api, browser)

        val signIn = async { auth.signInWithWebsite(context) }
        auth.handleRedirect(redirect(browser.firstOpen.await().getQueryParameter("state")))

        assertEquals(
            SamosaSignInResult.Error(context.getString(R.string.samosa_auth_this_account_is_disabled_contact)),
            signIn.await()
        )
    }

    @Test
    fun `a link that is not the sign-in link is left to the caller`() = runBlocking {
        val auth = manager(FakeApi())
        assertEquals(SamosaRedirectOutcome.NotSignIn, auth.handleRedirect(Uri.parse("com.gotcha:/other")))
    }

    @Test
    fun `signing out drops a sign-in still waiting for the browser`() = runBlocking {
        store.savePendingSamosaSignIn(PendingSamosaSignIn("v".repeat(64), "state-1", now))
        assertNotNull(store.loadPendingSamosaSignIn())

        manager(FakeApi()).signOut()

        assertNull(store.loadPendingSamosaSignIn())
    }

    @Test
    fun `account pages open on the website with their section`() {
        assertEquals("billing", SamosaDashboard.url(SamosaDashboard.Section.BILLING).getQueryParameter("tab"))
        assertNull(SamosaDashboard.url().getQueryParameter("tab"))
        val opened = FakeBrowser()
        assertTrue(SamosaDashboard.open(context, SamosaDashboard.Section.USAGE, opened))
        assertTrue(opened.opened.single().toString().startsWith(SamosaDashboard.baseUrl))
    }
}
