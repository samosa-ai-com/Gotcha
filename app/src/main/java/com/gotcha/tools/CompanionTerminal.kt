package com.gotcha.tools

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

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
class CompanionTerminal(
    private val context: Context,
    private val link: CompanionLink = CompanionConnection.get(context),
    private val access: () -> ShellEntitlement.Access = { ShellEntitlement.access(context) }
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
                !info.rootfsReady -> refused(Refusal.NoLinuxSystem(info.installing))
                else -> {
                    val handle = link.call { it.openSessionHandle(rows, cols) }
                    Opened.Session(ShellSession(handle.sessionId, handle.socket, link))
                }
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

    /** Tells the pty its new size. False when the session already ended or the companion is gone. */
    suspend fun resize(rows: Int, cols: Int): Boolean = try {
        link.call { it.resizeSession(id, rows, cols) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /** Hangs the session up. Safe to call more than once and after the shell exited. */
    suspend fun close() {
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
