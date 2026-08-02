package com.gotcha.audio

/**
 * Cleans up text before it's handed to a TTS engine.
 *
 * The on-screen display path never routes through this object — only the TTS
 * call sites in `TtsEngine.speak()`, the chat auto-read, voice-call narration,
 * and Screen Companion all funnel through here so assistant text is readable
 * aloud regardless of which surface triggered it.
 */
object SpeechTextSanitizer {

    private const val MAX_ORDINAL = 12

    private const val SENTENCE_TERMINATORS = ".?!"

    private val ORDINALS = arrayOf(
        "First", "Second", "Third", "Fourth", "Fifth", "Sixth",
        "Seventh", "Eighth", "Ninth", "Tenth", "Eleventh", "Twelfth"
    )

    private fun ordinalFor(n: Int): String =
        if (n in 1..MAX_ORDINAL) "${ORDINALS[n - 1]}, " else "Next, "

    /**
     * Strip emoji before speaking — TTS engines vocalize them (e.g. reading out a
     * skin-tone modifier or Unicode name) rather than skipping them silently.
     * Covers the emoji supplementary-plane block (U+1F000-1FFFF, where all
     * emoticons/pictographs/flags/transport/symbols live), the BMP misc-symbols
     * and dingbats blocks, and the variation selectors / zero-width joiners used
     * to compose multi-part emoji.
     */
    fun stripEmoji(text: String): String =
        text.replace(EMOJI_REGEX, "").replace(Regex("[ \\t]{2,}"), " ").trim()

    /**
     * Prepare [text] for the TTS engine. The on-screen display is unaffected —
     * this only runs on the path to the speaker.
     *
     * Strips fenced code blocks, inline code, markdown inline formatting
     * (bold / italic / strikethrough), heading prefixes, and blockquote markers;
     * rewrites contiguous bullet blocks as ordinal sentences (so the engine
     * reads "first, …", "second, …" instead of "dash, …"); replaces bare URLs
     * with the literal word "link"; collapses runs of exclamation/question
     * marks and whitespace; drops the unicode ellipsis to three ASCII dots;
     * and finally strips emoji.
     *
     * Already-numbered lists (1. foo / 2. bar) are left alone — only bare
     * bullets (`-`, `*`, `+`, `•`) get rewritten.
     */
    fun sanitize(text: String): String {
        var s = text
        s = s.replace(FENCED_CODE, "")
        s = s.replace(INLINE_CODE, "$1")
        s = s.replace(BOLD_ASTERISK, "$1")
        s = s.replace(BOLD_UNDERSCORE, "$1")
        s = s.replace(ITALIC_ASTERISK, "$1")
        s = s.replace(ITALIC_UNDERSCORE, "$1")
        s = s.replace(STRIKE, "$1")
        s = s.replace(HEADING_PREFIX, "")
        s = s.replace(BLOCKQUOTE_PREFIX, "")
        s = convertBulletBlocks(s)
        s = s.replace(MARKDOWN_LINK, "$1")
        s = s.replace(MARKDOWN_IMAGE, "$1")
        s = BARE_URL.replace(s) { match -> replaceBareUrl(match.value) }
        s = s.replace(MULTI_EXCLAIM, "!")
        s = s.replace(MULTI_QUESTION, "?")
        s = s.replace(ELLIPSIS_UNICODE, "...")
        s = s.replace(WHITESPACE_RUN, " ")
        s = s.replace(EMOJI_REGEX, "")
        return s.trim()
    }

    /** Replace each contiguous bullet block with "First, … Second, … Third, …." form. */
    private fun convertBulletBlocks(text: String): String {
        val lines = text.split('\n')
        val out = StringBuilder()
        var block = mutableListOf<String>()

        fun flushBlock() {
            if (block.isEmpty()) return
            val lastIdx = block.lastIndex
            val numbered = block.mapIndexed { idx, line ->
                val item = ordinalFor(idx + 1) + BULLET_PREFIX.replace(line, "")
                // Strip trailing sentence punctuation from non-final items so the
                // ". " join separator doesn't double up; preserve the last item's
                // own terminator (? / !) so a question / exclamation survives.
                if (idx == lastIdx) item else item.trimEnd { it in SENTENCE_TERMINATORS }
            }
            val joined = numbered.joinToString(". ")
            val lastChar = joined.lastOrNull()
            val suffix = if (lastChar != null && lastChar in SENTENCE_TERMINATORS) "" else "."
            out.append(joined).append(suffix).append("\n")
            block = mutableListOf()
        }

        for (line in lines) {
            if (BULLET_PREFIX.containsMatchIn(line)) {
                block.add(line)
            } else {
                flushBlock()
                out.append(line).append('\n')
            }
        }
        flushBlock()
        return out.toString().trimEnd('\n')
    }

    /**
     * Replace a bare URL match with the literal word "link", but keep any
     * sentence-ending punctuation that immediately follows it (`link.` rather
     * than eating the period). Trailing ASCII + CJK punctuation chars are
     * treated as outside the URL.
     */
    private fun replaceBareUrl(url: String): String {
        val trailing = url.takeLastWhile { it in TRAILING_PUNCT_CHARS }
        return "link$trailing"
    }

    private const val TRAILING_PUNCT_CHARS =
        ".,;:!?)]'\"" +
            "。，！？、；：）"

    private val EMOJI_REGEX = Regex(
        "[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE0F}\\x{FE0E}\\x{200D}]+"
    )

    private val FENCED_CODE = Regex("```[\\s\\S]*?```")
    private val INLINE_CODE = Regex("`([^`]+)`")
    private val BOLD_ASTERISK = Regex("\\*\\*(.+?)\\*\\*")
    private val BOLD_UNDERSCORE = Regex("__(.+?)__")
    private val ITALIC_ASTERISK = Regex(
        "(?U)(?<![\\w*])\\*(.+?)\\*(?![\\w*])"
    )
    private val ITALIC_UNDERSCORE = Regex(
        "(?U)(?<![\\w])_(.+?)_(?![\\w])"
    )
    private val STRIKE = Regex("~~(.+?)~~")
    private val HEADING_PREFIX = Regex("^[ \\t]{0,3}#{1,6}[ \\t]+", RegexOption.MULTILINE)
    private val BLOCKQUOTE_PREFIX = Regex("^[ \\t]{0,3}>[ \\t]+", RegexOption.MULTILINE)
    private val BULLET_PREFIX = Regex("^[ \\t]{0,3}[-*+•][ \\t]+")
    private val MARKDOWN_LINK = Regex("\\[([^\\]]+)]\\([^)]+\\)")
    private val MARKDOWN_IMAGE = Regex("!\\[([^\\]]*)]\\([^)]+\\)")
    private val BARE_URL = Regex("\\b(?:https?|ftp)://\\S+|\\bwww\\.\\S+")
    private val MULTI_EXCLAIM = Regex("!{2,}")
    private val MULTI_QUESTION = Regex("\\?{2,}")
    private val ELLIPSIS_UNICODE = Regex("…")
    private val WHITESPACE_RUN = Regex("(?U)\\s+")
}
