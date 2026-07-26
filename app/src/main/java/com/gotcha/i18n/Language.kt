package com.gotcha.i18n

/**
 * Single source of truth for supported languages. Persisted as [label] in prefs
 * (see SettingsRepository.preferredLanguage) — values must stay stable.
 */
enum class Language(
    /** Persisted value + UI dropdown label. Must stay stable — it is stored in prefs. */
    val label: String,
    /** BCP-47 tag for Android TextToSpeech / RecognizerIntent.EXTRA_LANGUAGE. */
    val bcp47: String,
    /** ISO-639-1 code for Whisper-style API STT (`sttLanguage`). */
    val iso639: String
) {
    ENGLISH("English", "en-US", "en"),
    SPANISH("Spanish", "es-ES", "es"),
    FRENCH("French", "fr-FR", "fr"),
    GERMAN("German", "de-DE", "de"),
    HINDI("Hindi", "hi-IN", "hi"),
    JAPANESE("Japanese", "ja-JP", "ja"),
    CHINESE("Chinese", "zh-CN", "zh"),
    ITALIAN("Italian", "it-IT", "it"),
    PORTUGUESE("Portuguese", "pt-BR", "pt");

    val locale: java.util.Locale get() = java.util.Locale.forLanguageTag(bcp47)

    companion object {
        val labels: List<String> get() = entries.map { it.label }

        /** Tolerant resolution — unknown/legacy persisted values fall back to English. */
        fun fromLabel(label: String?): Language =
            entries.firstOrNull { it.label.equals(label?.trim(), ignoreCase = true) } ?: ENGLISH
    }
}
