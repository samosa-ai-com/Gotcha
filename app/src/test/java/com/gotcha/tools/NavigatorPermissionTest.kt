package com.gotcha.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.testsupport.FakeAndroidKeyStore
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A grant the navigator runs into partway through its steps (issue #133).
 *
 * It used to log the tool's "not granted" line and step on, so the marker
 * ended up as prose in the handover summary and nothing was ever asked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NavigatorPermissionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a step that needs a grant ends the run and carries the marker up`() = runTest {
        // Up front the service reads as running; by the first tap it is gone
        // (as it is under Robolectric), so `tap` is withheld with its marker.
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","tool_calls":[
                  {"id":"nav_1","type":"function","function":{"name":"tap","arguments":"{\"x\":10,\"y\":10}"}}
                ]}}]}"""
            )
        )

        val output = AppNavigatorSession(
            appContext = context,
            toolExecutor = ToolExecutor(context),
            settings = Settings(
                provider = LlmProvider.OPENAI_COMPATIBLE,
                apiKey = "test-key",
                baseUrl = server.url("/").toString(),
                model = "gpt-4o"
            ),
            task = "open the settings screen",
            accessibilityState = { AccessibilityState.AVAILABLE }
        ).run()

        assertEquals("the run should stop at the step that hit the gap", 1, server.requestCount)
        assertFalse(output.success)
        assertEquals(ToolResult.ACCESSIBILITY_ACCESS, output.needsPermission)
        assertTrue(
            "the answer must say what stopped it: ${output.finalAnswer}",
            output.finalAnswer.contains("Navigation stopped at step 1")
        )
    }
}
