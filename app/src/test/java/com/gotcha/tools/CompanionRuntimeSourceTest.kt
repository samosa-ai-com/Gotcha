package com.gotcha.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.SettingsRepository
import com.gotcha.testsupport.FakeAndroidKeyStore
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The runtime source must never leave the phone without the account session, and a failed attempt
 * must not be repeated at once: the route allows a handful of requests an hour.
 *
 * The account binding matters as much as the cooldown: a refusal is recorded against the account
 * whose fetch produced the source, so one account's failure can never throttle another.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompanionRuntimeSourceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        FakeAndroidKeyStore.setUp()
        CompanionRuntimeSource.resetForTest()
        // Clear the tokens without clearSamosaSession(): that also signs the shell's entitlement
        // out, and the entitlement preferences are shared across test classes, so it would leave
        // ShellEntitlementTest starting from a signed-out state.
        SettingsRepository(context).prefs.edit()
            .remove(KEY_SAMOSA_TOKEN)
            .apply()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    @Test
    fun `no token means no request`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            assertNull(CompanionRuntimeSource.fetch(context, server.url("/").toString()))
            assertEquals("a signed-out phone must not call the route", 0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `an unusable answer suppresses the next fetch`() = runTest {
        val server = MockWebServer()
        server.enqueue(unusable())
        server.start()
        try {
            signIn("token-a")
            val base = server.url("/").toString()
            assertNull(CompanionRuntimeSource.fetch(context, base))
            assertNull(CompanionRuntimeSource.fetch(context, base))
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a refused offer suppresses the next fetch`() = runTest {
        val server = MockWebServer()
        server.enqueue(usable("b"))
        server.start()
        try {
            signIn("token-a")
            val base = server.url("/").toString()
            val ticket = CompanionRuntimeSource.fetchResolved(context, base)
            assertNotNull(ticket)
            ticket!!.noteRejected()
            assertNull(CompanionRuntimeSource.fetch(context, base))
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a different account is not throttled by the last one`() = runTest {
        val server = MockWebServer()
        server.enqueue(unusable())
        server.enqueue(usable("c"))
        server.start()
        try {
            val base = server.url("/").toString()
            signIn("token-a")
            assertNull(CompanionRuntimeSource.fetch(context, base))
            signIn("token-b")
            assertNotNull(CompanionRuntimeSource.fetch(context, base))
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a rejection is recorded against the account that fetched it, not the newest fetch`() =
        runTest {
            // Account A fetches, account B fetches, and only then does A's offer fail. A shared
            // "most recent attempt" slot would blame B and leave A unthrottled.
            val server = MockWebServer()
            server.enqueue(usable("d"))
            server.enqueue(usable("e"))
            server.enqueue(usable("f"))
            server.start()
            try {
                val base = server.url("/").toString()
                signIn("token-a")
                val a = CompanionRuntimeSource.fetchResolved(context, base)
                assertNotNull(a)
                signIn("token-b")
                assertNotNull(CompanionRuntimeSource.fetchResolved(context, base))

                a!!.noteRejected()

                // B, which never failed, may still fetch even though A's rejection came later.
                assertNotNull(CompanionRuntimeSource.fetch(context, base))
                // A, whose offer failed, is the one cooling down. Assert the request count too:
                // a 404 from an empty MockWebServer queue also yields null, so null alone would
                // not prove the fetch was suppressed rather than merely failing.
                signIn("token-a")
                assertNull(CompanionRuntimeSource.fetch(context, base))
                assertEquals("A's cooled-down fetch must not reach the route", 3, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    private fun usable(tag: String) = MockResponse()
        .setBody(
            """{"version":"5.1.107.91-1","url":"https://example.com/b.tar.gz","sha256":"${tag.repeat(64)}"}"""
        )
        .addHeader("Content-Type", "application/json")

    private fun unusable() = MockResponse()
        .setBody("""{"version":"1","url":"http://x","sha256":"short"}""")
        .addHeader("Content-Type", "application/json")

    /**
     * Signs in and waits for the write to land: `saveSamosaSession` uses `apply()`, so without
     * this a second sign-in could still read the first token.
     */
    private fun signIn(token: String) {
        // Straight to the preference: saveSamosaSession() also resets the shell entitlement,
        // which belongs to another test class and must not be disturbed from here.
        val repo = SettingsRepository(context)
        repo.prefs.edit().putString(KEY_SAMOSA_TOKEN, token).apply()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(token, repo.load().samosaSessionToken)
    }

    private companion object {
        /**
         * The key the session token lives under. It is private inside SettingsRepository, and
         * `signIn` reads it back through `load()`, so a rename fails loudly here rather than
         * silently signing in to nothing.
         */
        const val KEY_SAMOSA_TOKEN = "samosa_session_token"
    }
}
