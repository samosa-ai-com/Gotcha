package com.gotcha.agent.skills

import kotlinx.serialization.Serializable

@Serializable
data class Skill(
    val id: String,
    val targetPackageNames: List<String> = emptyList(),
    val instructions: String,
    val description: String = "",
    val title: String = "",
    /**
     * Tools this skill teaches. The skill is only injected when at least one of
     * them is currently callable — advice for a tool the agent cannot reach
     * costs tokens and invites a doomed tool call.
     *
     * Empty (the default) means always eligible, so skills that teach UI
     * automation or device tools need no annotation, and existing community
     * skills keep working unchanged.
     *
     * Named as tools rather than as a connector on purpose: `list_emails` is
     * served by Gmail *or* Microsoft *or* IMAP, so naming one connector would be
     * wrong for the other two, and this also covers permission-gated tools.
     */
    val requiresTools: List<String> = emptyList(),
    val keywords: List<String> = emptyList()
) {
    private val compiledKeywordRegexes: List<Regex> by lazy {
        keywords.map { kw ->
            Regex("""\b${Regex.escape(kw)}\b""", RegexOption.IGNORE_CASE)
        }
    }

    /** True when nothing this skill teaches is currently withheld from the model. */
    fun isAvailable(hiddenTools: Set<String>): Boolean =
        requiresTools.isEmpty() || requiresTools.any { it !in hiddenTools }

    /**
     * True when keywords are empty, or when at least one keyword matches a word
     * boundary in [text] (case-insensitive). Word-boundary matching prevents false
     * positives like keyword "call" matching "recall" or "vocaller".
     */
    fun matchesIntent(text: String): Boolean {
        if (keywords.isEmpty() || text.isBlank()) return true
        return compiledKeywordRegexes.any { regex ->
            regex.containsMatchIn(text)
        }
    }
}
