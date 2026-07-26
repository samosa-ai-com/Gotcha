package com.gotcha.audio

/** Cleans up text before it's handed to a TTS engine. */
object SpeechTextSanitizer {

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

    private val EMOJI_REGEX = Regex(
        "[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE0F}\\x{FE0E}\\x{200D}]+"
    )
}
