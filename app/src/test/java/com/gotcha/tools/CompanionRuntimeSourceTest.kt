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
 * The runtime source must never leave the phone without the account session, and a failure must
 * not be retried at once: the route allows a handful of requests an hour. A signed-out phone has
 * no token, so the fetch returns null without touching the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompanionRuntimeSourceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        FakeAndroidKeyStore.setUp()
        CompanionRuntimeSource.resetForTest()
        SettingsRepository(context).clearSamosaSession()
    }

    @Test
    fun `no token means no request`() = runTest {
        assertNull(CompanionRuntimeSource.fetch(context))
    }

    @Test
    fun `an unusable answer suppresses the next fetch`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setBody("""{"version":"1","url":"http://x","sha256":"short"}""")
                .addHeader("Content-Type", "application/json")
        )
        server.start()
        try {
            signIn("token-a")
            assertNull(CompanionRuntimeSource.fetch(context, server.url("/").toString()))
            assertNull(CompanionRuntimeSource.fetch(context, server.url("/").toString()))
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a refused offer suppresses the next fetch`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"version":"5.1.107.91-1","url":"https://example.com/b.tar.gz","sha256":"${"b".repeat(64)}"}"""
                )
                .addHeader("Content-Type", "application/json")
        )
        server.start()
        try {
            signIn("token-a")
            val base = server.url("/").toString()
            assertNotNull(CompanionRuntimeSource.fetch(context, base))
            CompanionRuntimeSource.noteOfferRejected()
            assertNull(CompanionRuntimeSource.fetch(context, base))
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a different account is not throttled by the last one`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setBody("""{"version":"1","url":"http://x","sha256":"short"}""")
                .addHeader("Content-Type", "application/json")
        )
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"version":"5.1.107.91-1","url":"https://example.com/b.tar.gz","sha256":"${"c".repeat(64)}"}"""
                )
                .addHeader("Content-Type", "application/json")
        )
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

    private fun signIn(token: String) {
        SettingsRepository(context).saveSamosaSession(token, "test@example.com")
    }
}
