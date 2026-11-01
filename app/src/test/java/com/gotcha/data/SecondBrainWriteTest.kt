package com.gotcha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The "Saved to Second Brain" line under a tool result. */
class SecondBrainWriteTest {

    private val root = "/data/user/0/com.gotcha/files/Gotcha/memory"

    @Test
    fun `a write inside the vault says saved`() {
        val line = "write_file: Wrote 1.2 KB to 'notes/plan.md' (resolved: $root/notes/plan.md)."

        assertEquals(SecondBrainWrite.SAVED, secondBrainWriteOf(line, root))
    }

    @Test
    fun `an edit inside the vault says updated`() {
        val line = "edit: Replaced 1 occurrence in 'MEMORY.md' (resolved: $root/MEMORY.md)."

        assertEquals(SecondBrainWrite.UPDATED, secondBrainWriteOf(line, root))
    }

    @Test
    fun `a write elsewhere is not memory`() {
        val line = "write_file: Wrote 10 B to 'a.txt' (resolved: /sdcard/Download/a.txt)."

        assertNull(secondBrainWriteOf(line, root))
    }

    @Test
    fun `a path that only shares the prefix is not memory`() {
        val line = "write_file: Wrote 10 B to 'a.txt' (resolved: ${root}2/notes/a.md)."

        assertNull(secondBrainWriteOf(line, root))
    }

    @Test
    fun `a read of a memory file is not a write`() {
        val line = "read_file: Read MEMORY.md (resolved: $root/MEMORY.md)."

        assertNull(secondBrainWriteOf(line, root))
    }

    @Test
    fun `a truncated line makes no claim`() {
        assertNull(secondBrainWriteOf("write_file: Wrote 10 B to 'a.md'", root))
        assertNull(secondBrainWriteOf("write_file: Wrote 10 B (resolved: )", root))
    }

    @Test
    fun `an empty root is never a match`() {
        val line = "write_file: Wrote 1 B to 'a' (resolved: /memory/a)."
        assertNull(secondBrainWriteOf(line, ""))
    }
}
