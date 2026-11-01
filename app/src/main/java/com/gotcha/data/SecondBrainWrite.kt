package com.gotcha.data

/** Which memory write a tool line reports. */
enum class SecondBrainWrite {
    /** A new file appeared in memory. */
    SAVED,

    /** An existing memory file changed. */
    UPDATED
}

/**
 * Reads a tool line and says whether it wrote inside [memoryRoot].
 *
 * Two shapes reach the transcript. While a run is live, [com.gotcha.agent.AgentEngine]
 * prefixes the tool name — "write_file: Wrote 1.2 KB to '…' (resolved: …)". A
 * reopened chat rebuilds its transcript from history, where the stored tool
 * message is only the result — "Wrote … (resolved: …)" for a write, "Replaced
 * 1 occurrence in '/…/note.md'." for an edit. Both have to be recognised, or
 * the label would disappear the first time the chat is opened again.
 *
 * Deliberately strict: a read, a write elsewhere, or a line truncated before
 * its path makes no claim at all.
 */
fun secondBrainWriteOf(text: String, memoryRoot: String): SecondBrainWrite? {
    val write = when {
        text.startsWith("write_file:") ||
            text.startsWith("Wrote ") ||
            text.startsWith("Appended ") -> SecondBrainWrite.SAVED

        text.startsWith("edit:") || text.startsWith("Replaced ") -> SecondBrainWrite.UPDATED

        else -> return null
    }
    val path = resolvedPathIn(text) ?: editedPathIn(text) ?: return null
    val root = memoryRoot.trimEnd('/')
    if (root.isEmpty()) return null
    return if (path == root || path.startsWith("$root/")) write else null
}

/** The canonical target the file tools print as "(resolved: …)", when present. */
private fun resolvedPathIn(text: String): String? {
    val marker = "(resolved: "
    val at = text.lastIndexOf(marker)
    if (at < 0) return null
    return text.substring(at + marker.length).substringBefore(')').trim().ifEmpty { null }
}

/** The target of an edit result: "Replaced 1 occurrence in '/path'." */
private fun editedPathIn(text: String): String? {
    val marker = " in '"
    val at = text.indexOf(marker)
    if (at < 0) return null
    val start = at + marker.length
    val end = text.indexOf('\'', start)
    if (end < 0) return null
    return text.substring(start, end).trim().ifEmpty { null }
}
