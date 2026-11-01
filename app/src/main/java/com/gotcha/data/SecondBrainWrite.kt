package com.gotcha.data

/** Which memory write a tool line reports. */
enum class SecondBrainWrite {
    /** A new file appeared in memory. */
    SAVED,

    /** An existing memory file changed. */
    UPDATED
}

/**
 * Reads a tool-call line as the transcript shows it — "write_file: Wrote 1.2 KB
 * to '…' (resolved: /…/memory/notes/plan.md)." — and says whether it landed in
 * [memoryRoot].
 *
 * Deliberately strict about the tool name and about the resolved path: the
 * label "Saved to Second Brain" must never appear for a file written somewhere
 * else, and a line that was truncated before its resolved path says nothing.
 */
fun secondBrainWriteOf(text: String, memoryRoot: String): SecondBrainWrite? {
    val write = when {
        text.startsWith("write_file:") -> SecondBrainWrite.SAVED
        text.startsWith("edit:") -> SecondBrainWrite.UPDATED
        else -> return null
    }
    val marker = "(resolved: "
    val at = text.lastIndexOf(marker)
    if (at < 0) return null
    val path = text.substring(at + marker.length).substringBefore(')').trim()
    val root = memoryRoot.trimEnd('/')
    if (root.isEmpty() || path.isEmpty()) return null
    return if (path == root || path.startsWith("$root/")) write else null
}
