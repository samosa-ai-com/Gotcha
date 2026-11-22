package com.gotcha.tools

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.gotcha.shell.SessionHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opens interactive sessions (`bash -l` on a pty) in Gotcha Shell, for the terminal screen.
 *
 * This is the other half of [CompanionShellBackend]: that one runs a command and reports what it
 * printed, this one hands over a live byte stream. It needs interface version
 * [CompanionShellBackend.INTERFACE_VERSION], which added sessions with an id, resize and a cap.
 *
 * Everything the user types or a program prints goes through the stream untouched. Unlike
 * `run_shell`, nothing here is shown to the model; if that ever changes, [ShellOutputGuard] has to
 * come with it.
 */
class CompanionTerminal internal constructor(
    private val context: Context,
    private val link: CompanionLink = CompanionConnection.get(context),
    private val access: () -> ShellEntitlement.Access = { ShellEntitlement.access(context) },
    /** Where the runtime comes from; see [offerRuntimeSource]. Injected so tests need no network. */
    private val runtimeSource: suspend () -> CompanionRuntimeSource.ResolvedSource? =
        { CompanionRuntimeSource.fetchResolved(context) }
) {

    /** Why a session could not be opened; the screen turns each into a message of its own. */
    sealed interface Refusal {
        /** The account's plan does not include the shell, or the user is signed out. */
        data class NotAllowed(val access: ShellEntitlement.Access) : Refusal

        /** Not installed, not signed with Gotcha's key, or it would not bind. */
        data class Unavailable(val reason: CompanionUnavailable.Reason) : Refusal

        /** The installed companion predates the terminal; it needs an update. */
        data class TooOld(val found: Int, val needed: Int) : Refusal

        /** The Linux system is not installed, or is being installed right now. */
        data class NoLinuxSystem(val installing: Boolean) : Refusal

        /**
         * The runtime (downloaded on first use, not shipped) is being downloaded, or is missing and
         * Gotcha could not get a source for it: offline, or the account does not include the shell.
         */
        data class NoRuntime(val installing: Boolean) : Refusal

        /** The companion's session cap is reached. [limit] is 0 when it did not say. */
        data class TooManySessions(val limit: Int) : Refusal

        /** Gotcha Shell refused this Gotcha as a caller, or died while answering. */
        data class Failed(val detail: String?) : Refusal
    }

    sealed interface Opened {
        data class Session(val session: ShellSession) : Opened
        data class Refused(val refusal: Refusal) : Opened
    }

    /** Opens a session of [rows] by [cols] characters. Call from a coroutine; Binder calls block. */
    suspend fun open(rows: Int, cols: Int): Opened {
        val allowed = access()
        if (allowed != ShellEntitlement.Access.ALLOWED) return refused(Refusal.NotAllowed(allowed))
        return try {
            val info = link.call { it.info }
            CompanionShellState.setRootfsReady(context, info.rootfsReady)
            when {
                info.interfaceVersion < CompanionShellBackend.INTERFACE_VERSION ->
                    refused(Refusal.TooOld(info.interfaceVersion, CompanionShellBackend.INTERFACE_VERSION))
                info.installing || !info.rootfsReady -> refused(Refusal.NoLinuxSystem(info.installing))
                info.runtimeInstalling -> refused(Refusal.NoRuntime(installing = true))
                // As for commands: push a source, then open; the companion installs the runtime
                // before it starts the session. Only a failed push is a refusal.
                !info.runtimeReady && !offerRuntimeSource(link, runtimeSource) ->
                    refused(Refusal.NoRuntime(installing = false))
                else -> Opened.Session(openSession(rows, cols))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CompanionUnavailable) {
            refused(Refusal.Unavailable(e.reason))
        } catch (e: SecurityException) {
            refused(Refusal.Failed(e.message))
        } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            when {
                message.contains(TOO_MANY, ignoreCase = true) ->
                    refused(Refusal.TooManySessions(limitIn(message)))
                message.contains("No rootfs", ignoreCase = true) -> {
                    CompanionShellState.setRootfsReady(context, false)
                    refused(Refusal.NoLinuxSystem(installing = false))
                }
                else -> refused(Refusal.Failed(e.message))
            }
        } catch (e: IllegalArgumentException) {
            refused(Refusal.Failed(e.message))
        } catch (e: RemoteException) {
            refused(Refusal.Failed(e.message))
        } catch (e: IOException) {
            refused(Refusal.Failed(e.message))
        }
    }

    /**
     * Once the companion answers, the session counts toward its cap, so it has to reach the caller
     * or be hung up. The call is not cancellable: a cancellation while it is under way would throw
     * the answer away and leave the session holding a place until the companion process ends.
     */
    private suspend fun openSession(rows: Int, cols: Int): ShellSession {
        var opened: ShellSession? = null
        try {
            withContext(NonCancellable) {
                opened = sessionFrom(link.call { it.openSessionHandle(rows, cols) })
            }
            currentCoroutineContext().ensureActive()
            return checkNotNull(opened)
        } catch (e: CancellationException) {
            opened?.let { withContext(NonCancellable) { it.close() } }
            throw e
        }
    }

    /**
     * Wraps an opened session. The companion has already counted it toward its cap, so if the
     * streams cannot be set up the session is hung up here rather than left open until the
     * companion notices.
     */
    private suspend fun sessionFrom(handle: SessionHandle): ShellSession {
        val socket: ParcelFileDescriptor? = handle.socket
        val sessionId: String? = handle.sessionId
        return try {
            if (sessionId == null || socket == null) {
                throw IOException("Gotcha Shell sent no ${if (sessionId == null) "id" else "socket"} for the session")
            }
            ShellSession(sessionId, socket, link)
        } catch (e: IOException) {
            runCatching { socket?.close() }
            // Without an id there is nothing to name; closing the socket ends it on its own.
            sessionId?.let { hangUp(it) }
            throw e
        }
    }

    private suspend fun hangUp(sessionId: String) {
        try {
            link.call { it.closeSession(sessionId) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Closing the socket ends the session on the companion's side as well.
        }
    }

    private fun refused(refusal: Refusal) = Opened.Refused(refusal)

    /** The companion words its refusal "Too many sessions (limit 4)"; 0 when no number is there. */
    private fun limitIn(message: String?): Int =
        message?.let { LIMIT.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0

    private companion object {
        const val TOO_MANY = "Too many sessions"
        val LIMIT = Regex("""limit (\d+)""")
    }
}

/**
 * One open terminal session. [input] is what the shell prints and [output] is what the user
 * types; closing either hangs the shell up. [resize] and [close] are Binder calls, not bytes on
 * the stream, so a program in the shell cannot forge them.
 */
class ShellSession internal constructor(
    val id: String,
    socket: ParcelFileDescriptor,
    private val link: CompanionLink
) {
    val input: InputStream = ParcelFileDescriptor.AutoCloseInputStream(socket.dup())
    val output: OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(socket)
    private val closed = AtomicBoolean(false)

    /** Tells the pty its new size. False when the session already ended or the companion is gone. */
    suspend fun resize(rows: Int, cols: Int): Boolean = try {
        link.call { it.resizeSession(id, rows, cols) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /**
     * Hangs the session up. Safe to call more than once, from more than one thread, and after the
     * shell exited: only the first call does anything.
     */
    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { output.close() }
        runCatching { input.close() }
        try {
            link.call { it.closeSession(id) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Gone already: the companion ends a session whose socket closed.
        }
    }
}
