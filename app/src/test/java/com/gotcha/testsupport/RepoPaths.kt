package com.gotcha.testsupport

import java.io.File

/**
 * Locating repo files from a unit test.
 *
 * Unit tests run with the working directory at the `app/` module dir, so paths are resolved by
 * walking up until the directory holding `settings.gradle.kts` is found rather than hardcoding
 * `../`. `TerminalToolTest` already establishes that filesystem access from unit tests is fine.
 */
object RepoPaths {

    val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return@lazy dir
            dir = dir.parentFile
        }
        error("could not locate the repo root (no settings.gradle.kts above ${System.getProperty("user.dir")})")
    }

    fun file(relativePath: String): File = File(root, relativePath)

    /** Reads a main-source file under `app/src/main/java/`, failing loudly if it moved. */
    fun mainSource(relativePath: String): String {
        val f = file("app/src/main/java/$relativePath")
        check(f.isFile) { "expected main source at ${f.path}; did the file move?" }
        return f.readText()
    }
}
