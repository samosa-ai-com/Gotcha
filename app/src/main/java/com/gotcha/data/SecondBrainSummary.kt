package com.gotcha.data

/**
 * What the Second Brain page says above the files, taken from the snapshot it
 * already loaded.
 *
 * [empty] means the vault holds nothing but the seeded skeleton — `SOUL.md`,
 * `MEMORY.md`, `user.md` and the four area hubs. That is the state worth a
 * "start it with a conversation" card: showing the same rows with an empty
 * notes list teaches nothing.
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
    empty = notes.isEmpty() && people.isEmpty()
)
