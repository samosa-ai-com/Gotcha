package com.gotcha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The counts and the empty check the Second Brain page opens on. */
class SecondBrainSummaryTest {

    private fun file(name: String) = MemoryStore.BrainFile(name = name, content = "", truncated = false)

    private fun snapshot(
        notes: Int = 0,
        people: Int = 0,
        areas: Int = 4
    ) = MemoryStore.SecondBrainSnapshot(
        available = true,
        soul = file("SOUL.md"),
        memoryIndex = file("MEMORY.md"),
        userCard = file("user.md"),
        areas = List(areas) { file("area$it.md") },
        people = List(people) { file("person$it.md") },
        notes = List(notes) { file("note$it.md") }
    )

    @Test
    fun `the seeded skeleton counts as empty`() {
        val summary = snapshot().summary()

        assertTrue(summary.empty)
        assertEquals(0, summary.notes)
        assertEquals(0, summary.people)
        assertEquals(4, summary.areas)
    }

    @Test
    fun `a note ends the empty state`() {
        assertFalse(snapshot(notes = 1).summary().empty)
    }

    @Test
    fun `a person ends the empty state too`() {
        assertFalse(snapshot(people = 1).summary().empty)
    }

    @Test
    fun `counts follow the snapshot`() {
        val summary = snapshot(notes = 3, people = 2, areas = 4).summary()

        assertEquals(3, summary.notes)
        assertEquals(2, summary.people)
        assertEquals(4, summary.areas)
        assertFalse(summary.empty)
    }
}
