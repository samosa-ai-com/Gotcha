package com.gotcha.tools

import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `run_termux_command`. Real Termux cannot exist on the JVM, so this covers everything up to and
 * after the intent: the {installed, granted} decision table via ShadowPackageManager, the
 * validation guards, and result-bundle parsing — which is why [TermuxTool.formatResult] is a
 * separate function rather than inlined into the coroutine.
 *
 * The live round-trip (a real `pkg install`) is manual by necessity; see
 * docs/FEATURE_TEST_COVERAGE.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class TermuxToolTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tool = TermuxTool(context)

    /**
     * The F-Droid/GitHub build: the package plus a resolvable RunCommandService.
     *
     * [playStoreTermux] is the other real-world shape — verified on a Nothing Phone 3a running
     * the Play build, which registers no services at all.
     */
    private fun installTermux() {
        playStoreTermux()
        shadowOf(context.packageManager).addServiceIfNotPresent(
            ComponentName(TermuxTool.TERMUX_PACKAGE, "com.termux.app.RunCommandService")
        )
        shadowOf(context.packageManager).addIntentFilterForService(
            ComponentName(TermuxTool.TERMUX_PACKAGE, "com.termux.app.RunCommandService"),
            IntentFilter("com.termux.RUN_COMMAND")
        )
    }

    /** Termux installed, but the plugin API stripped — the Google Play build. */
    private fun playStoreTermux() {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = TermuxTool.TERMUX_PACKAGE
                versionName = "0.118.0"
            }
        )
    }

    private fun grantRunCommand() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(TermuxTool.PERMISSION_RUN_COMMAND)
    }

    // ---- status / decision table ----

    @Test
    fun `status reports termux absent on a clean device`() {
        val status = tool.status()
        assertFalse(status.installed)
        assertFalse(status.usable)
        assertNull(status.versionName)
    }

    @Test
    fun `status reports the installed version and grant`() {
        installTermux()
        grantRunCommand()

        val status = tool.status()
        assertTrue(status.installed)
        assertTrue(status.pluginApiAvailable)
        assertTrue(status.usable)
        assertTrue(status.permissionGranted)
        assertEquals("0.118.0", status.versionName)
    }

    @Test
    fun `the play store build is installed but not usable`() {
        playStoreTermux()

        val status = tool.status()
        assertTrue(status.installed)
        assertFalse("Play build registers no RunCommandService", status.pluginApiAvailable)
        assertFalse("must not be gated in as usable", status.usable)
    }

    @Test
    fun `the play store build is called out rather than asking for a permission that cannot exist`() = runTest {
        // Verified against a real device: the Play build declares no RUN_COMMAND permission at
        // all, so `pm grant` fails with "Unknown permission". Prompting would be a dead end.
        playStoreTermux()
        grantRunCommand()

        val result = tool.runCommand("echo hello")

        assertFalse(result.success)
        assertNull("a permission prompt could never be satisfied here", result.needsPermission)
        assertTrue(result.message.contains("Google Play build"))
        assertTrue(result.message.contains("F-Droid"))
        assertTrue(result.message.contains("Do not retry"))
    }

    @Test
    fun `without termux the tool points at run_command instead`() = runTest {
        val result = tool.runCommand("echo hello")

        assertFalse(result.success)
        assertNull("no permission prompt is useful without Termux", result.needsPermission)
        assertTrue(result.message.contains("not installed"))
        assertTrue(result.message.contains("run_command"))
    }

    @Test
    fun `with termux but no permission the tool asks for the grant`() = runTest {
        installTermux()

        val result = tool.runCommand("echo hello")

        assertFalse(result.success)
        assertEquals(ToolResult.TERMUX_ACCESS, result.needsPermission)
        assertTrue(
            "must name the property the user has to set: ${result.message}",
            result.message.contains("allow-external-apps")
        )
    }

    @Test
    fun `a granted call reaches termux and then times out waiting for a result`() = runTest {
        installTermux()
        grantRunCommand()

        val result = tool.runCommand("echo hello", timeoutSeconds = 1)

        // No Termux exists to answer the PendingIntent, so this is the timeout path — and it has
        // to name both indistinguishable causes.
        assertFalse(result.success)
        assertNull(result.needsPermission)
        assertTrue(result.message.contains("still be running"))
        assertTrue(result.message.contains("allow-external-apps"))
    }

    // ---- validation guards (checked before availability, so they report the real problem) ----

    @Test
    fun `empty command is rejected`() = runTest {
        val result = tool.runCommand("   ")
        assertFalse(result.success)
        assertTrue(result.message.contains("Empty command"))
    }

    @Test
    fun `device-destroying commands are blocked by the deny-list`() = runTest {
        installTermux()
        grantRunCommand()
        listOf("mkfs.ext4 /dev/block/sda", "dd if=/dev/zero of=/dev/block/sda", "rm -rf / ", "fastboot oem unlock")
            .forEach { command ->
                val result = tool.runCommand(command)
                assertFalse("expected '$command' to be blocked", result.success)
                assertTrue(result.message.contains("safety policy"))
            }
    }

    @Test
    fun `rm -r inside termux home is allowed unlike run_command`() = runTest {
        // TerminalTool blocks every `rm -r`; here it is ordinary housekeeping, so the deny-list
        // must let it through to the (absent) Termux rather than refusing it outright.
        val result = tool.runCommand("rm -rf ~/build")
        assertFalse(result.message.contains("safety policy"))
    }

    @Test
    fun `an oversized command is rejected before the intent is sent`() = runTest {
        installTermux()
        grantRunCommand()

        val result = tool.runCommand("echo " + "x".repeat(200 * 1024))

        assertFalse(result.success)
        assertTrue(result.message.contains("over Termux's"))
        assertTrue(result.message.contains("write_file"))
    }

    // ---- result-bundle parsing ----

    @Test
    fun `a successful result reports exit code and stdout`() {
        val result = tool.formatResult(resultBundle(exitCode = 0, stdout = "hello\n"))

        assertTrue(result.success)
        assertTrue(result.message.contains("exit code: 0"))
        assertTrue(result.message.contains("stdout:\nhello"))
    }

    @Test
    fun `a failing command reports stderr and a non-zero exit code`() {
        val result = tool.formatResult(resultBundle(exitCode = 127, stderr = "sh: nope: not found"))

        assertFalse(result.success)
        assertTrue(result.message.contains("exit code: 127"))
        assertTrue(result.message.contains("stderr:\nsh: nope: not found"))
    }

    @Test
    fun `a silent command says so rather than reporting nothing`() {
        val result = tool.formatResult(resultBundle(exitCode = 0))
        assertTrue(result.message.contains("(no output)"))
    }

    @Test
    fun `errno -1 is success, not an error`() {
        // Regression test for a real inversion. Termux's Errno is built on Activity's result
        // constants: RESULT_OK is -1, so ERRNO_SUCCESS is -1 and 0 means *cancelled* — the
        // opposite of the shell convention. Reading `err != 0` as failure discarded every
        // successful run. Caught on a Nothing Phone 3a: `uname -a` returned err = -1 and its
        // output was thrown away as "Termux refused or could not run the command".
        val bundle = resultBundle(exitCode = 0, stdout = "Linux localhost 5.15.0 aarch64 Android\n")
            .apply { putInt(TermuxTool.RESULT_ERR, TermuxTool.ERRNO_SUCCESS) }

        val result = tool.formatResult(bundle)

        assertTrue("errno -1 means success: ${result.message}", result.success)
        assertTrue(result.message.contains("aarch64"))
    }

    @Test
    fun `errno 0 is cancelled, not success`() {
        val bundle = resultBundle(exitCode = 0).apply { putInt(TermuxTool.RESULT_ERR, 0) }

        val result = tool.formatResult(bundle)

        assertFalse(result.success)
        assertTrue(result.message.contains("cancelled"))
    }

    @Test
    fun `a termux-side failure surfaces its message and fails`() {
        val bundle = resultBundle(exitCode = 0).apply {
            putInt(TermuxTool.RESULT_ERR, 2) // ERRNO_FAILED
            putString(TermuxTool.RESULT_ERRMSG, "allow-external-apps is false")
        }

        val result = tool.formatResult(bundle)

        assertFalse(result.success)
        assertTrue(result.message.contains("failed"))
        assertTrue(result.message.contains("allow-external-apps is false"))
    }

    @Test
    fun `output past our cap is truncated with a marker`() {
        val result = tool.formatResult(resultBundle(exitCode = 0, stdout = "y".repeat(40 * 1024)))

        assertTrue(result.message.contains("…(output capped)"))
        assertTrue("must stay near the 32KB cap: ${result.message.length}", result.message.length < 34 * 1024)
    }

    @Test
    fun `termux truncating the bundle itself is reported too`() {
        // Termux sends the original lengths as strings, and only ever sends what fits in ~100KB.
        val bundle = resultBundle(exitCode = 0, stdout = "z".repeat(100)).apply {
            putString(TermuxTool.RESULT_STDOUT_ORIGINAL_LENGTH, "500000")
        }

        assertTrue(tool.formatResult(bundle).message.contains("~100KB result limit"))
    }

    @Test
    fun `a missing exit code does not read as success`() {
        val result = tool.formatResult(Bundle().apply { putString(TermuxTool.RESULT_STDOUT, "partial") })

        assertFalse(result.success)
        assertTrue(result.message.contains("exit code: -1"))
    }

    // ---- receiver hand-off ----

    @Test
    fun `the receiver hands a result to the waiting caller and drops unknown ones`() {
        // completeResult clears the entry it completes; an unknown code must be a no-op rather
        // than a crash, since a timed-out caller has already removed itself.
        assertFalse(TermuxTool.isPending(Int.MAX_VALUE))
        TermuxTool.completeResult(Int.MAX_VALUE, resultBundle(exitCode = 0))
        assertFalse(TermuxTool.isPending(Int.MAX_VALUE))
    }

    private fun resultBundle(exitCode: Int, stdout: String? = null, stderr: String? = null) =
        Bundle().apply {
            putInt(TermuxTool.RESULT_EXIT_CODE, exitCode)
            stdout?.let { putString(TermuxTool.RESULT_STDOUT, it) }
            stderr?.let { putString(TermuxTool.RESULT_STDERR, it) }
        }
}
