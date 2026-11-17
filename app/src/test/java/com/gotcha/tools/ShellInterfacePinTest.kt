package com.gotcha.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/**
 * The `.aidl` files are a contract owned by the Gotcha Shell repository; Gotcha only carries a
 * copy. This fails when the copy no longer matches what `shell-interface.lock` pins, so an edit
 * made here — or a re-copy without updating the pin — cannot pass unnoticed.
 */
class ShellInterfacePinTest {

    private val moduleDir: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { if (File(it, "app/shell-interface.lock").isFile) File(it, "app") else it }
            .first { File(it, "shell-interface.lock").isFile }

    private val lock = Properties().apply {
        File(moduleDir, "shell-interface.lock").inputStream().use { load(it) }
    }

    @Test
    fun `aidl copy matches the pinned hash`() {
        val files = File(moduleDir, "src/main/aidl/com/gotcha/shell")
            .listFiles { file -> file.extension == "aidl" }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue("no .aidl files found under ${moduleDir.path}", files.isNotEmpty())

        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { digest.update(it.readBytes()) }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }

        assertEquals(
            "The .aidl files differ from the pinned interface. They are owned by Gotcha-Shell: " +
                "do not edit them here. If you copied a newer interface, update shell-interface.lock.",
            lock.getProperty("sha256"),
            actual
        )
    }

    @Test
    fun `pinned interface version is the one the backend expects`() {
        assertEquals(
            lock.getProperty("interfaceVersion").toInt(),
            CompanionShellBackend.INTERFACE_VERSION
        )
    }
}
