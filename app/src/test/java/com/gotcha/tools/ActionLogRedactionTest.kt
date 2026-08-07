package com.gotcha.tools

import androidx.test.core.app.ApplicationProvider
import com.gotcha.testsupport.FakeAndroidKeyStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The audit log records what every tool was asked to do, in plaintext in the app sandbox. That
 * is the point of it — but `run_termux_command`'s `stdin` exists so the model can answer a
 * prompt, and the prompts worth answering are passwords and tokens. Those must not be written
 * down, while the rest of the line stays intact so the audit trail still shows what ran.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionLogRedactionTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var executor: ToolExecutor

    private val logFile: File get() = File(context.filesDir, "action_log.txt")

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        if (logFile.exists()) logFile.delete()
        executor = ToolExecutor(context)
    }

    @Test
    fun `stdin is redacted from the audit log but the command survives`() = runTest {
        // Termux is absent here, so the tool fails early — the audit line is written either way,
        // which is exactly the path a secret would leak through.
        executor.execute(
            "run_termux_command",
            JsonObject(
                mapOf(
                    "command" to JsonPrimitive("ssh user@host"),
                    "stdin" to JsonPrimitive("hunter2-super-secret")
                )
            ),
            agent = AgentMode.OPERATOR,
            isSubAgent = true
        )

        val logged = logFile.readText()
        assertFalse("the secret must not reach disk: $logged", logged.contains("hunter2-super-secret"))
        assertTrue("the redaction should be visible, not silent", logged.contains("(redacted)"))
        assertTrue("the audit trail must still say what ran", logged.contains("ssh user@host"))
        assertTrue(logged.contains("run_termux_command"))
    }

    @Test
    fun `arguments without a redacted key are logged unchanged`() = runTest {
        executor.execute(
            "run_termux_command",
            JsonObject(mapOf("command" to JsonPrimitive("uname -a"))),
            agent = AgentMode.OPERATOR,
            isSubAgent = true
        )

        val logged = logFile.readText()
        assertTrue(logged.contains("uname -a"))
        assertFalse("nothing to redact here", logged.contains("(redacted)"))
    }
}
