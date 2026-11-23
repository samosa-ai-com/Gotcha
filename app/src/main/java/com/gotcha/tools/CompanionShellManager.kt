package com.gotcha.tools

import android.content.Context
import android.os.RemoteException
import com.gotcha.shell.IInstallCallback
import com.gotcha.shell.ShellInfo
import kotlinx.coroutines.CancellationException

/**
 * Looks after the Gotcha Shell companion itself, as opposed to running things in it: what state
 * it is in, and installing, repairing or wiping its Linux system. This is what
 * Settings ▸ Shell ▸ Gotcha Shell drives.
 *
 * Each action returns null on success, otherwise a sentence saying why it failed.
 */
class CompanionShellManager(
    private val context: Context,
    private val link: CompanionLink = CompanionConnection.get(context)
) {

    fun identity(): CompanionIdentity = link.identity()

    /**
     * Fetches and installs the companion APK from the backend.
     *
     * Gotcha installs the companion so a user only ever installs one app. The route is
     * tier-gated and session-authenticated, so this is only reachable from a screen that has
     * already been told the plan includes the shell.
     *
     * Returns a user-facing message on failure, or null when the install has started — the
     * outcome itself arrives through [InstallReceiver].
     */
    suspend fun installFromBackend(
        onProgress: (phase: String, done: Long, total: Long) -> Unit = { _, _, _ -> }
    ): String? {
        val resolved = CompanionApkSource.fetchResolved(context) ?: return unavailableMessage()
        val installer = CompanionApkInstaller(context)
        val token = com.gotcha.data.SettingsRepository(context).load().samosaSessionToken
        val bearer = "Bearer $token"
        return when (val result = installer.install(resolved.release, apiBase(), bearer, onProgress)) {
            CompanionApkInstaller.Result.Started -> null
            CompanionApkInstaller.Result.NeedInstallPermission -> {
                installer.openInstallPermissionSettings(context)
                context.getString(com.gotcha.R.string.linux_shell_get_app_permission)
            }
            CompanionApkInstaller.Result.NotInPlan ->
                context.getString(com.gotcha.R.string.linux_shell_plan_not_pro)
            CompanionApkInstaller.Result.Expired ->
                context.getString(com.gotcha.R.string.linux_shell_get_app_expired)
            CompanionApkInstaller.Result.Corrupt ->
                context.getString(com.gotcha.R.string.linux_shell_get_app_corrupt)
            CompanionApkInstaller.Result.UntrustedSigner ->
                context.getString(com.gotcha.R.string.linux_shell_app_untrusted)
            CompanionApkInstaller.Result.WrongPackage ->
                context.getString(com.gotcha.R.string.linux_shell_get_app_wrong_build)
            CompanionApkInstaller.Result.Downgrade ->
                context.getString(com.gotcha.R.string.linux_shell_get_app_downgrade)
            is CompanionApkInstaller.Result.Failed -> result.message
        }
    }

    private fun apiBase(): String = com.gotcha.auth.SamosaAuthApi.AUTH_BASE_URL

    private fun unavailableMessage(): String =
        context.getString(com.gotcha.R.string.linux_shell_get_app_unavailable)

    /**
     * Hands the companion a runtime source, best effort.
     *
     * `install` and `repair` both end up in the companion's `ensureRuntime()`, and its own route
     * is tier-gated, so without this that step fails with 401. A failure here is not fatal: the
     * call it preceded still runs and reports its own error.
     */
    private suspend fun offerRuntime() {
        val ticket = try {
            CompanionRuntimeSource.fetchResolved(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val source = ticket?.source
        if (source == null || !source.isUsable()) return
        try {
            link.call { it.offerRuntime(source.version, source.url, source.sha256) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort; the install reports its own failure. Record the refusal against the
            // account that fetched it, so the next install does not spend another request on it.
            ticket.noteRejected()
        }
    }

    /** The companion's own report, or null when it cannot be reached. */
    suspend fun info(): ShellInfo? = try {
        link.call { it.info }.also { CompanionShellState.setRootfsReady(context, it.rootfsReady) }
    } catch (_: CompanionUnavailable) {
        null
    } catch (_: RemoteException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** Downloads and unpacks the Linux system, replacing any existing one. */
    suspend fun install(onProgress: (phase: String, done: Long, total: Long) -> Unit): String? = managing {
        // The companion installs the runtime it needs as part of this call, and its own route is
        // tier-gated, so push a source first or that part fails with 401.
        offerRuntime()
        val callback = object : IInstallCallback.Stub() {
            override fun onProgress(phase: String, done: Long, total: Long) = onProgress(phase, done, total)
        }
        link.call { it.install(callback) }.also { error ->
            if (error == null) CompanionShellState.setRootfsReady(context, true)
        }
    }

    /** Finishes package installs that were interrupted, for example when Android killed the companion. */
    suspend fun repair(): String? = managing {
        offerRuntime()
        val result = link.call { it.repair() }
        if (result.exitCode == 0) {
            null
        } else {
            result.stderr.orEmpty().trim().ifEmpty { "exit code ${result.exitCode}" }
        }
    }

    /** Deletes the Linux system and everything installed in it. */
    suspend fun reset(): String? = managing {
        link.call { it.reset() }
        CompanionShellState.setRootfsReady(context, false)
        null
    }

    private suspend fun managing(block: suspend () -> String?): String? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: CompanionUnavailable) {
        CompanionMessages.unavailable(e).message
    } catch (e: SecurityException) {
        CompanionMessages.refused(e.message).message
    } catch (e: IllegalStateException) {
        e.message ?: "Gotcha Shell rejected the request."
    } catch (_: RemoteException) {
        "Gotcha Shell stopped before it finished."
    }
}
