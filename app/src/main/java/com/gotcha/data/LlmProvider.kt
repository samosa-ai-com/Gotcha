package com.gotcha.data

import com.gotcha.BuildConfig

/**
 * Which LLM backend the app talks to.
 *
 * [OPENAI_COMPATIBLE] is the original workflow: the user supplies a Base URL and
 * API key (OpenAI, LocalAI, Ollama, vLLM, LM Studio, OpenRouter, any
 * OpenAI-compatible server).
 *
 * [SAMOSA_AI] authenticates with Google Sign-In and uses a backend-issued
 * session JWT against the OpenAI-compatible proxy at [SAMOSA_BASE_URL]. Base URL
 * and API key are ignored in this mode.
 */
enum class LlmProvider(val label: String) {
    SAMOSA_AI("Samosa AI"),
    OPENAI_COMPATIBLE("OpenAI Compatible");

    companion object {
        /**
         * OpenAI-compatible proxy exposed by the Samosa AI backend. Derived from
         * the build-time `SAMOSA_API_URL` (environment or `local.properties`).
         */
        val SAMOSA_BASE_URL: String = "${BuildConfig.SAMOSA_API_URL}/v1/"

        fun fromName(name: String?): LlmProvider =
            entries.firstOrNull { it.name == name } ?: SAMOSA_AI
    }
}
