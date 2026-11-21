package com.gotcha.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Termux's terminal code under `third_party/termux-terminal` is copied unchanged, so that taking
 * a newer Termux is a plain re-copy and the licence notice stays true. This fails when one of those
 * files differs from the checksum recorded in `UPSTREAM.sha256`, or when a Java file appears that
 * is neither listed there nor Gotcha's own `TerminalSession`.
 */
class TermuxCopyPinTest {

    private val moduleDir: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "third_party/termux-terminal") }
            .first { File(it, "UPSTREAM.sha256").isFile }

    private val pinned: Map<String, String> = File(moduleDir, "UPSTREAM.sha256").readLines()
        .filter { it.isNotBlank() }
        .associate { line -> line.substringAfter("  ") to line.substringBefore("  ") }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun `every copied file is what Termux ships`() {
        assertTrue("UPSTREAM.sha256 is empty", pinned.isNotEmpty())
        pinned.forEach { (path, hash) ->
            val file = File(moduleDir, path)
            assertTrue("$path is listed but missing", file.isFile)
            assertEquals("$path was edited; copied Termux files must stay unchanged", hash, sha256(file))
        }
    }

    @Test
    fun `no other Termux file has crept in`() {
        val found = File(moduleDir, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .map { it.relativeTo(moduleDir).invariantSeparatorsPath }
            .toSet()
        val ours = setOf("src/main/java/com/termux/terminal/TerminalSession.java")
        val strangers = found - pinned.keys - ours
        assertEquals("Java files that are neither pinned nor Gotcha's own", emptySet<String>(), strangers)
    }

    @Test
    fun `Termux's process-spawning session and native code are not here`() {
        val jni = File(moduleDir, "src/main/java/com/termux/terminal/JNI.java")
        assertTrue("JNI.java must not be copied", !jni.exists())
        assertTrue("no native code", File(moduleDir, "src/main/jni").exists().not())
    }
}
