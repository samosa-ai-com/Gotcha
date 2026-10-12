package com.gotcha.audio

/**
 * What Android's built-in speech will use for one language (#122): the phone's
 * engines by name and whether each has the language. Null fields are unknown,
 * which shows no name and no warning.
 */
data class AndroidSpeechStatus(
    val ttsEngine: String? = null,
    val ttsSupports: Boolean? = null,
    val recognizer: String? = null,
    val recognizerSupports: Boolean? = null
)
