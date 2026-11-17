package com.gotcha.tools

import android.content.Context
import android.os.RemoteException
import com.gotcha.shell.IInstallCallback
import com.gotcha.shell.ShellInfo
import kotlinx.coroutines.CancellationException

/**
 * Looks after the Gotcha Shell companion itself, as opposed to running things in it: what state
 * it is in, and installing, repairing or wiping its Linux system. This is what
 * Settings ▸ Linux shell drives.
 *
 * Each action returns null on success, otherwise a sentence saying why it failed.
 */
class CompanionShellManager(
    private val context: Context,
    private val link: CompanionLink = CompanionConnection.get(context)
) {

    fun identity(): CompanionIdentity = link.identity()

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
        val callback = object : IInstallCallback.Stub() {
            override fun onProgress(phase: String, done: Long, total: Long) = onProgress(phase, done, total)
        }
        link.call { it.install(callback) }.also { error ->
            if (error == null) CompanionShellState.setRootfsReady(context, true)
        }
    }

    /** Finishes package installs that were interrupted, for example when Android killed the companion. */
    suspend fun repair(): String? = managing {
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
