package com.gotcha.data

/**
 * What the Second Brain page says above the files, taken from the snapshot it
 * already loaded.
 *
 * [empty] means the vault holds nothing but the seeded skeleton — the generated
 * `SOUL.md` and `MEMORY.md`, `user.md` and the four area hubs. A brain whose
 * identity or map has already been edited is not empty, even with no notes
 * yet: the start card would be claiming it knows nothing when it does.
 */
data class SecondBrainSummary(
    val notes: Int,
    val people: Int,
    val areas: Int,
    val empty: Boolean
)

/** The counts and the empty check for [MemoryStore.SecondBrainSnapshot]. */
fun MemoryStore.SecondBrainSnapshot.summary(): SecondBrainSummary = SecondBrainSummary(
    notes = notes.size,
    people = people.size,
    areas = areas.size,
    empty = notes.isEmpty() &&
        people.isEmpty() &&
        raw.isEmpty() &&
        (soul == null || MemoryStore.isPristineSoul(soul.content)) &&
        (memoryIndex == null || MemoryStore.isPristineMemoryIndex(memoryIndex.content)) &&
        areas.all { MemoryStore.isPristineArea(it.content) }
)
