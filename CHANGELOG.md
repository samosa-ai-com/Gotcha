# Changelog

All notable changes to Gotcha are documented here.

## [Unreleased]

### Added — Samosa AI provider (Google authentication)

Adds "Samosa AI" as an additional LLM provider alongside the existing
OpenAI-compatible workflow. Samosa AI authenticates with Google Sign-In (via
Android Credential Manager + Google Identity Services) and exchanges the Google
ID token for an `Samosa AI backend` session JWT, which is then used as the bearer
token against the OpenAI-compatible proxy at `https://api.samosa-ai.example/v1/`.
The existing OpenAI-compatible providers (OpenAI, LocalAI, Ollama, vLLM, LM
Studio, OpenRouter, any OpenAI-compatible server) are unchanged.

- **build.gradle.kts**: added `androidx.credentials`,
  `androidx.credentials:credentials-play-services-auth`, and
  `com.google.android.libraries.identity.googleid:googleid` dependencies.
- **data/LlmProvider.kt** (new): `LlmProvider` enum (`SAMOSA_AI`,
  `OPENAI_COMPATIBLE`) plus the Samosa proxy base URL constant.
- **data/SettingsRepository.kt**: added `provider`, `samosaSessionToken`, and
  `samosaEmail` to `Settings`; made `isConfigured` provider-aware; added
  `effectiveBaseUrl` / `effectiveApiKey` / `isSamosaAuthenticated` helpers; persist
  the new fields (JWT stored in the existing EncryptedSharedPreferences); added
  `saveSamosaSession` / `clearSamosaSession`. Defaults keep existing users on the
  OpenAI-compatible flow.
- **auth/SamosaAuthApi.kt** (new): Retrofit interface + models for the
  auth-manager `POST /register`, `GET /me`, and `POST /logout` endpoints against
  `https://api.samosa-ai.example/`.
- **auth/SamosaAuthManager.kt** (new): drives Google Sign-In via Credential
  Manager (WEB client ID as `serverClientId`), exchanges the Google ID token at
  `/register` for a session JWT, stores the JWT (never the Google token), and
  handles logout, session invalidation on 401, and error mapping (401/403/429/502).
- **llm/LLMClient.kt**: added an optional `onUnauthorized` callback fired when the
  server returns 401; the auth interceptor otherwise unchanged (`Bearer <token>`).
- **Provider-aware routing**: all 6 `LLMClient` construction sites (ChatViewModel
  ×2, MainActivity ×2, SubAgentSession, AppNavigatorSession, CallSessionController)
  now use `settings.effectiveApiKey` / `settings.effectiveBaseUrl` instead of the
  raw `apiKey` / `baseUrl`, so Samosa mode transparently targets the JWT + proxy
  URL while OpenAI-compatible mode is byte-for-byte unchanged. ChatViewModel wires
  the 401 callback to clear the Samosa token and return to the sign-in state.
- **ui/SettingsScreen.kt**: added an "LLM Provider" dropdown (Samosa AI / OpenAI
  Compatible). In OpenAI-compatible mode the Base URL + API key fields render
  exactly as before. In Samosa mode those fields are hidden and a
  `SamosaAuthSection` shows sign-in status, connected Google account, and a
  Sign-in / Log-out button; the model dropdown + "Refresh models" still work
  (hitting `/v1/models` with the JWT). Save/Test buttons are gated on the active
  provider's requirements.
- **MainActivity.kt**: instantiates `SamosaAuthManager` and wires `onSamosaSignIn`
  (runs Google sign-in with the Activity context, persists the JWT, refreshes the
  ViewModel) and `onSamosaSignOut`. The initial route already opens Settings when
  the active provider is unconfigured, satisfying the "prompt to sign in" flow.

### Fixed

- **agent/AgentEngine.kt**: added an anti-loop guard to the shared agent tool
  loop. When consecutive tool rounds produce a byte-identical signature (tool-call
  names + tool result text) — e.g. the accessibility service repeatedly returning
  "enabled but not running", or `webfetch` retrying a dead URL that keeps 404-ing —
  the loop now bails out after 3 identical rounds with a clear message instead of
  spinning until `maxToolRounds`. Provider-agnostic; benefits both OpenAI-compatible
  and Samosa AI. (Root cause of the apparent "reply re-sent in a loop" during
  tool-heavy Samosa tasks: the model kept retrying a failing tool with no new
  information; not a re-injection of assistant replies as user turns.)
