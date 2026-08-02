package com.gotcha.data

/**
 * Which LLM backend the app talks to.
 *
 * [OPENAI_COMPATIBLE] is the original workflow: the user supplies a Base URL and
 * API key (OpenAI, LocalAI, Ollama, vLLM, LM Studio, OpenRouter, any
 * OpenAI-compatible server).
 *
 * [SAMOSA_AI] authenticates with Google Sign-In and uses an Samosa AI backend
 * session JWT against the OpenAI-compatible proxy at [SAMOSA_BASE_URL]. Base URL
 * and API key are ignored in this mode.
 */
enum class LlmProvider(val label: String) {
    SAMOSA_AI("Samosa AI"),
    OPENAI_COMPATIBLE("OpenAI Compatible");

    companion object {
        /** OpenAI-compatible proxy exposed by Samosa AI backend. */
        const val SAMOSA_BASE_URL = "https://api.samosa-ai.example/v1/"

        fun fromName(name: String?): LlmProvider =
            entries.firstOrNull { it.name == name } ?: SAMOSA_AI
    }
}
