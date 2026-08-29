package com.gotcha.tools

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/**
 * Moves a file from Termux's private filesystem into a location Gotcha (and the user) can reach.
 *
 * Gotcha runs at `/storage/emulated/0/Gotcha/...` under its own uid, Termux at
 * `/data/data/com.termux/files/home` under another — the two cannot see each other's files. The
 * documented bridge is shared storage (`/sdcard`) via `termux-setup-storage`, but that only exists
 * after the user has run the command AND granted Android's dialog, and `run_termux_command` always
 * reports `exit 0` whether or not the grant happened. So the bridge here is chosen
 * deterministically, not left to the model:
 *
 * 1. **Shared storage** when Termux's `~/storage` link actually exists and the destination is
 *    reachable from Termux — the documented, debuggable path, a plain `cp`.
 * 2. **Loopback TCP** otherwise: Termux streams the file with `python3` over `127.0.0.1` and
 *    Gotcha receives it with a Kotlin `ServerSocket`. Loopback crosses the uid boundary without
 *    touching scoped-storage permissions at all, which is exactly what a fresh Termux (or a user
 *    who never granted `termux-setup-storage`) needs.
 *
 * The `python3` sender is wrapped in a wake-lock so Doze cannot stall a slow transfer; the values
 * it needs (port, nonce, source path) are passed as argv so no quoting or interpolation can break
 * a path with newlines or quotes; a single-use nonce scopes the socket to one sender; the body is
 * streamed to a temp file and atomically renamed on success so a failed transfer never leaves a
 * corrupt file behind.
 */
class TermuxFileBridge(
    private val context: Context,
    private val termux: TermuxTool = TermuxTool(context)
) {

    private val resolver = FileResolver(context)

    companion object {
        /** Below this size the `/sdcard` bridge is fine; above it FUSE starts to matter. */
        const val SHARED_STORAGE_MAX_BYTES = 100L * 1024 * 1024

        /** The probe that decides whether `termux-setup-storage` actually linked the bridge. */
        const val PROBE_STORAGE_COMMAND =
            "ls -ld ~/storage/downloads >/dev/null 2>&1 && echo STORAGE_LINKED || echo STORAGE_UNLINKED"

        private const val STORAGE_LINKED_MARKER = "STORAGE_LINKED"

        /** Upper bound for one transfer; the socket also guards with its own read timeout. */
        const val BRIDGE_TIMEOUT_SECONDS = 300

        /** How long we wait for the sender to connect at all. */
        private const val ACCEPT_TIMEOUT_MS = 30_000L

        private const val CHUNK_BYTES = 64 * 1024

        /** Handshake the receiver sends once the sender presents the right nonce. */
        private const val READY_ACK = "READY\n"

        private const val DENIED_ACK = "DENIED\n"
    }

    /** Whether Termux's `/sdcard` bridge (`~/storage`) is actually usable, not merely requested. */
    suspend fun isStorageLinked(): Boolean {
        val result = termux.runCommand(PROBE_STORAGE_COMMAND, timeoutSeconds = 15)
        return result.success && linksStorage(result.message)
    }

    /**
     * Pulls [termuxPath] out of Termux into [destination].
     *
     * Tries the `/sdcard` bridge when it is genuinely linked; falls back to a loopback transfer
     * when it is not (or when a `cp` through FUSE fails), so the outcome never depends on whether
     * the user granted the storage dialog.
     */
    suspend fun pull(termuxPath: String, destination: File): ToolResult {
        val source = expandHome(termuxPath)
        resolver.checkWritePermission(destination)?.let { return it }
        destination.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                return ToolResult.error("Could not create the destination directory '${parent.canonicalPath}'.")
            }
        }
        if (usesSharedStorage(isStorageLinked(), destination.canonicalPath)) {
            val viaShared = copyViaSharedStorage(source, destination)
            if (viaShared.success) return viaShared
            // Fall through: FUSE refused us; loopback can still reach the destination directly.
        }
        return copyViaLoopback(source, destination)
    }

    // ---- shared-storage path ----

    private suspend fun copyViaSharedStorage(source: String, destination: File): ToolResult {
        val termuxDest = FfmpegCommand.termuxPath(destination.canonicalPath)
        val command = "cp -- ${FfmpegCommand.shellQuote(source)} ${FfmpegCommand.shellQuote(termuxDest)} " +
            "&& test -f ${FfmpegCommand.shellQuote(termuxDest)} && echo COPIED"
        val result = termux.runCommand(command, timeoutSeconds = BRIDGE_TIMEOUT_SECONDS)
        if (!result.success) {
            return ToolResult.error(
                "Could not copy through shared storage: ${result.message.take(200)}"
            )
        }
        if (!result.message.contains("COPIED")) {
            return ToolResult.error(
                "The copy reported success but '$termuxDest' is missing or empty."
            )
        }
        return ToolResult.ok(
            "Copied '$source' to '${destination.canonicalPath}' through shared storage. " +
                "File size on arrival: ${resolver.formatSize(destination.length())}."
        )
    }

    // ---- loopback path ----

    /**
     * Streams [source] from Termux to [destination] over `127.0.0.1`. The Termux side is a small
     * `python3` client that presents a single-use nonce and then sends the file; the Gotcha side is
     * a Kotlin [ServerSocket] bound to loopback. Both run concurrently.
     */
    private suspend fun copyViaLoopback(source: String, destination: File): ToolResult {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        try {
            server.reuseAddress = true
            server.soTimeout = ACCEPT_TIMEOUT_MS.toInt()
            val port = server.localPort

            return coroutineScope {
                val send = async {
                    termux.runCommand(
                        command = senderCommand(port, nonce, source),
                        stdin = senderScript(),
                        timeoutSeconds = BRIDGE_TIMEOUT_SECONDS
                    )
                }
                val receive = async {
                    withContext(Dispatchers.IO) {
                        withTimeout(BRIDGE_TIMEOUT_SECONDS * 1000L) { receiveInto(server, nonce, destination) }
                    }
                }

                val sendResult = send.await()
                if (!sendResult.success) {
                    // The sender never ran (python3 missing, permission missing, bad path). Close the
                    // socket so the blocking accept() in `receive` throws immediately and the scope can
                    // finish without waiting out the accept timeout.
                    server.close()
                    receive.cancel()
                    return@coroutineScope ToolResult.error(
                        "Termux failed to start the file transfer sender: ${sendResult.message.take(200)}. " +
                            "Ensure Termux has python3 ('pkg install python -y') and that the source " +
                            "path '$source' exists."
                    )
                }

                try {
                    receive.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@coroutineScope ToolResult.error(
                        "The loopback transfer from Termux failed (${e.message ?: "no detail"}). " +
                            "Termux said: ${sendResult.message.take(200)}."
                    )
                }

                ToolResult.ok(
                    "Pulled '$source' out of Termux to '${destination.canonicalPath}' " +
                        "(${resolver.formatSize(destination.length())})."
                )
            }
        } finally {
            server.close()
        }
    }

    /**
     * Listens for the sender, validates the single-use nonce, and streams the body into a temp file
     * that is atomically renamed over [destination] only on success. A connection that presents the
     * wrong nonce (or closes before the handshake) is refused and we keep listening for the real
     * sender until [ACCEPT_TIMEOUT_MS] elapses.
     */
    private fun receiveInto(server: ServerSocket, nonce: String, destination: File) {
        destination.parentFile?.mkdirs()
        val tmp = File(
            destination.parentFile,
            ".${destination.name}.${UUID.randomUUID().toString().take(8)}.tmp"
        )
        val deadline = System.currentTimeMillis() + ACCEPT_TIMEOUT_MS
        var transferred = false
        try {
            while (!transferred) {
                if (System.currentTimeMillis() > deadline) error("no sender connected before the timeout")
                transferred = handleCandidate(server.accept(), nonce, tmp)
            }
            if (!tmp.renameTo(destination)) {
                if (destination.exists() && !destination.delete()) {
                    error("could not replace the existing destination '${destination.canonicalPath}'")
                }
                if (!tmp.renameTo(destination)) {
                    error("could not finalize the transfer at '${destination.canonicalPath}'")
                }
            }
        } finally {
            // On success the temp was renamed away; on failure it must not linger.
            tmp.delete()
        }
    }

    /**
     * Validates one connecting socket's nonce and, if it matches, streams its body into [tmp].
     * A wrong nonce or an early close is refused and the caller keeps listening.
     */
    private fun handleCandidate(socket: Socket, nonce: String, tmp: File): Boolean {
        var transferred = false
        socket.use {
            it.soTimeout = BRIDGE_TIMEOUT_SECONDS * 1000
            val header = ByteArray(nonce.length)
            val gotHeader = readFully(it.getInputStream(), header)
            if (!gotHeader || header.decodeToString() != nonce) {
                it.getOutputStream().write(DENIED_ACK.toByteArray())
                it.getOutputStream().flush()
                return@use
            }
            it.getOutputStream().write(READY_ACK.toByteArray())
            it.getOutputStream().flush()
            FileOutputStream(tmp).use { out ->
                val buffer = ByteArray(CHUNK_BYTES)
                while (true) {
                    val read = it.getInputStream().read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
            }
            transferred = true
        }
        return transferred
    }

    /** Reads exactly [into].size bytes; returns false if the connection closed early. */
    private fun readFully(input: InputStream, into: ByteArray): Boolean {
        var offset = 0
        while (offset < into.size) {
            val read = input.read(into, offset, into.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    // ---- pure helpers, split out for tests ----

    /** Expands a leading `~/` or a literal `$HOME` to Termux's home directory. */
    internal fun expandHome(path: String): String {
        val home = termux.termuxHome()
        return when {
            path == "~" -> home
            path.startsWith("~/") -> home + path.removePrefix("~")
            path.startsWith("\$HOME") -> home + path.removePrefix("\$HOME")
            else -> path
        }
    }

    /** The shared-storage path is only the primary route when the link exists AND Termux can see the destination. */
    internal fun usesSharedStorage(storageLinked: Boolean, destinationCanonical: String): Boolean =
        storageLinked && FfmpegCommand.isReachableFromTermux(destinationCanonical)

    /** A probe message names the bridge as linked only when the explicit marker is present. */
    internal fun linksStorage(message: String): Boolean = message.contains(STORAGE_LINKED_MARKER)

    /** Test seam: Termux's home without needing a live Termux install. */
    internal fun termuxHomeForTest(): String = termux.termuxHome()

    /**
     * The command that runs the sender in Termux. The python program is piped via stdin
     * (`python3 -`); the port, nonce and source path ride as argv so nothing is interpolated into
     * the program text — a path with newlines or quotes cannot break it. Wake-lock so Doze cannot
     * stall the transfer.
     */
    internal fun senderCommand(port: Int, nonce: String, source: String): String =
        TermuxTool.withWakeLock("python3 - $port $nonce ${FfmpegCommand.shellQuote(source)}")

    /** The `python3` client program, reading port/nonce/path from argv. */
    internal fun senderScript(): String =
        "import socket, sys\n" +
            "port = int(sys.argv[1])\n" +
            "nonce = sys.argv[2]\n" +
            "path = sys.argv[3]\n" +
            "s = socket.socket()\n" +
            "s.connect(('127.0.0.1', port))\n" +
            "s.sendall(nonce.encode())\n" +
            "ack = s.recv(64)\n" +
            "if ack != b'READY\\n':\n" +
            "    s.close()\n" +
            "    sys.exit(1)\n" +
            "with open(path, 'rb') as f:\n" +
            "    while True:\n" +
            "        chunk = f.read($CHUNK_BYTES)\n" +
            "        if not chunk:\n" +
            "            break\n" +
            "        s.sendall(chunk)\n" +
            "s.close()\n"
}
