package com.gotcha.tools

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
 * a path with newlines or quotes; a single-use nonce scopes the socket to one sender; the sender
 * announces the file size up front and the receiver verifies the byte count on arrival; the body
 * is streamed to a temp file that is renamed over the destination only after the sender has
 * exited 0 AND the full size has arrived, so a failed transfer never leaves a corrupt file behind.
 */
class TermuxFileBridge(
    private val context: Context,
    private val termux: TermuxTool = TermuxTool(context)
) {

    private val resolver = FileResolver(context)

    companion object {
        /** Below this size the `/sdcard` bridge is fine; above it FUSE starts to matter. */
        const val SHARED_STORAGE_MAX_BYTES = 100L * 1024 * 1024

        /** How long the storage-link probe result is trusted before re-probing. */
        private const val STORAGE_PROBE_CACHE_MS = 30_000L

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

    /**
     * Whether Termux's `/sdcard` bridge (`~/storage`) is actually usable, not merely requested.
     *
     * The probe is cached briefly: the linked state only changes when the user runs
     * `termux-setup-storage` and grants the dialog, so a pull that fails and is retried should not
     * re-probe on every attempt.
     */
    suspend fun isStorageLinked(): Boolean {
        val now = System.currentTimeMillis()
        if (now - storageProbedAt < STORAGE_PROBE_CACHE_MS) return storageLinked ?: false
        val result = termux.runCommand(PROBE_STORAGE_COMMAND, timeoutSeconds = 15)
        val linked = result.success && linksStorage(result.message)
        storageLinked = linked
        storageProbedAt = now
        return linked
    }

    private var storageLinked: Boolean? = null
    private var storageProbedAt: Long = Long.MIN_VALUE

    /**
     * Pulls [termuxPath] out of Termux into [destination].
     *
     * Tries the `/sdcard` bridge when it is genuinely linked and the file is small enough that
     * FUSE is not the bottleneck; falls back to a loopback transfer when it is not linked, when the
     * file is above [SHARED_STORAGE_MAX_BYTES], or when a `cp` through FUSE fails, so the outcome
     * never depends on whether the user granted the storage dialog.
     */
    suspend fun pull(termuxPath: String, destination: File): ToolResult {
        val source = expandHome(termuxPath)
        resolver.checkWritePermission(destination)?.let { return it }
        destination.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                return ToolResult.error("Could not create the destination directory '${parent.canonicalPath}'.")
            }
        }
        val destinationCanonical = destination.canonicalPath
        var sharedError: String? = null
        // Short-circuit before probing Termux at all: a destination inside Gotcha's sandbox is
        // unreachable from Termux, so only the loopback path can serve it.
        if (FfmpegCommand.isReachableFromTermux(destinationCanonical)) {
            val storageLinked = isStorageLinked()
            // Only stat the source when the bridge is actually usable — the size gate is about
            // choosing a transport, not about files we are not going to move this way.
            val sourceBytes = if (storageLinked) sourceSizeBytes(source) else null
            if (usesSharedStorage(storageLinked, destinationCanonical, sourceBytes)) {
                val viaShared = copyViaSharedStorage(source, destination)
                if (viaShared.success) return viaShared
                sharedError = viaShared.message
                // Fall through: FUSE refused us; loopback can still reach the destination directly.
            }
        }
        val loopback = copyViaLoopback(source, destination)
        if (loopback.success) return loopback
        // The loopback failure is the actionable one, but the shared-storage refusal explains WHY we
        // fell back — dropping it would mislead a FUSE/permission debug.
        return if (sharedError == null) {
            loopback
        } else {
            ToolResult.error(
                "${loopback.message}\n(The shared-storage attempt also failed: ${sharedError.take(200)})"
            )
        }
    }

    /** The size of [source] as Termux sees it, or null when it cannot be determined. */
    private suspend fun sourceSizeBytes(source: String): Long? {
        val result = termux.runCommand(
            "stat -c %s -- ${FfmpegCommand.shellQuote(source)}",
            timeoutSeconds = 15
        )
        return result.message.lineSequence().lastOrNull()?.trim()?.toLongOrNull()
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
     * a Kotlin [ServerSocket] bound to loopback. Both run concurrently, and the temp file is moved
     * over [destination] only after **both** the sender has exited 0 and the receiver has verified
     * the byte count — a sender killed mid-transfer (lmkd, force-stop, battery manager) fails one
     * of those first, so a partial body is never finalised.
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

                val tmp = try {
                    receive.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@coroutineScope ToolResult.error(
                        "The loopback transfer from Termux failed (${e.message ?: "no detail"}). " +
                            "Termux said: ${sendResult.message.take(200)}."
                    )
                }

                // Both halves succeeded; only now is the temp file finalised over the destination.
                if (!finalizeTransfer(tmp, destination)) {
                    tmp.delete()
                    return@coroutineScope ToolResult.error(
                        "The transfer completed but could not be saved to '${destination.canonicalPath}'."
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
     * Listens for the sender, validates the single-use nonce, and streams the body into a temp file.
     * Returns the temp file on a verified transfer; it never touches [destination] — the caller
     * finalises that only after the sender has also reported success, so a partial body cannot land
     * in place. A connection that presents the wrong nonce (or closes before the handshake) is
     * refused and we keep listening for the real sender until [ACCEPT_TIMEOUT_MS] elapses.
     */
    internal fun receiveInto(server: ServerSocket, nonce: String, destination: File): File {
        destination.parentFile?.mkdirs()
        val tmp = File(
            destination.parentFile,
            ".${destination.name}.${UUID.randomUUID().toString().take(8)}.tmp"
        )
        val deadline = System.currentTimeMillis() + ACCEPT_TIMEOUT_MS
        try {
            while (true) {
                if (System.currentTimeMillis() > deadline) error("no sender connected before the timeout")
                handleCandidate(server.accept(), nonce, tmp)?.let { return it }
            }
        } catch (e: Throwable) {
            // A failed transfer must not linger as a stray temp file.
            tmp.delete()
            throw e
        }
    }

    /**
     * Validates one connecting socket's nonce and streams its body into [tmp], verifying the
     * sender's announced byte count. Returns [tmp] on a complete, size-checked transfer; null on a
     * wrong nonce or an early close, so the caller keeps listening for the real sender.
     */
    internal fun handleCandidate(socket: Socket, nonce: String, tmp: File): File? = socket.use { s ->
        s.soTimeout = BRIDGE_TIMEOUT_SECONDS * 1000
        val header = ByteArray(nonce.length)
        val gotHeader = readFully(s.getInputStream(), header)
        if (!gotHeader || header.decodeToString() != nonce) {
            s.getOutputStream().write(DENIED_ACK.toByteArray())
            s.getOutputStream().flush()
            return@use null
        }
        s.getOutputStream().write(READY_ACK.toByteArray())
        s.getOutputStream().flush()
        val expected = readSizeLine(s.getInputStream())
            ?: throw IOException("sender closed before announcing a valid file size")
        FileOutputStream(tmp).use { out ->
            val buffer = ByteArray(CHUNK_BYTES)
            var received = 0L
            while (true) {
                val read = s.getInputStream().read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                received += read
            }
            if (received != expected) {
                throw IOException("transfer truncated: received $received of $expected bytes")
            }
        }
        tmp
    }

    /** Reads a single `\n`-terminated line as a byte count; null on EOF or non-numeric text. */
    private fun readSizeLine(input: InputStream): Long? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.size() == 0) null else line.toString("UTF-8").trim().toLongOrNull()
            if (b == '\n'.code) break
            line.write(b)
        }
        return line.toString("UTF-8").trim().toLongOrNull()
    }

    /** Atomically moves [tmp] over [destination], replacing an existing file if needed. */
    private fun finalizeTransfer(tmp: File, destination: File): Boolean {
        if (tmp.renameTo(destination)) return true
        if (destination.exists() && !destination.delete()) return false
        return tmp.renameTo(destination)
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

    /**
     * The shared-storage path is only the primary route when the link exists AND Termux can see the
     * destination AND the file is small enough that FUSE is not the bottleneck — above
     * [SHARED_STORAGE_MAX_BYTES] the loopback stream is used even on a linked bridge.
     */
    internal fun usesSharedStorage(
        storageLinked: Boolean,
        destinationCanonical: String,
        sourceBytes: Long? = null
    ): Boolean =
        storageLinked &&
            FfmpegCommand.isReachableFromTermux(destinationCanonical) &&
            (sourceBytes == null || sourceBytes < SHARED_STORAGE_MAX_BYTES)

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

    /** The `python3` client program, reading port/nonce/path from argv. It announces the file size
     * before streaming so the receiver can reject a truncated body rather than accept it. */
    internal fun senderScript(): String =
        "import socket, sys, os\n" +
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
            "size = os.path.getsize(path)\n" +
            "s.sendall(str(size).encode() + b'\\n')\n" +
            "with open(path, 'rb') as f:\n" +
            "    while True:\n" +
            "        chunk = f.read($CHUNK_BYTES)\n" +
            "        if not chunk:\n" +
            "            break\n" +
            "        s.sendall(chunk)\n" +
            "s.close()\n"
}
