# Changelog

All notable changes to Gotcha are documented here.

## [Unreleased]

### Added — tiered settings control (`open_setting`)

Settings were previously reached one of two ways: silently through an Android API,
or by asking the App Navigator to open the Settings app and search for the control.
The second path is the least reliable thing the agent does, and it was being used
even for settings with a documented direct intent. Two mechanisms Android does
provide — `Settings.Panel.*` sheets and `ACTION_*_SETTINGS` deep links — went
unused. Settings are now routed through four tiers, with the tier decided by what
the platform actually permits rather than by preference.

- **tools/SettingsRouter.kt** (new): the tier-2/3 routing table. 21 entries mapping
  a setting name to a panel or deep-link intent, an SDK floor with a fallback
  action, and a hint describing where the control sits on the screen that opens.
  The routing decision (`decide` / `resolveAction`) is a `Context`-free companion
  function so it is unit-testable on the JVM; only firing the intent needs a device.
  An unknown key returns an error naming `navigate_app`, so a miss degrades to the
  previous behaviour instead of dead-ending.
- **tools/ToolDefinitions.kt**: added the `open_setting` schema (`setting`,
  optional `confirmed`) and registered it in `all`. Corrected `toggle_wifi`'s
  description, which claimed direct toggling worked "on Android 13+" — the reverse
  is true: `WifiManager.setWifiEnabled` has been a no-op since API 29, so the
  panel fallback is what runs on every current device.
- **tools/ToolExecutor.kt**, **ToolCategories.kt**, **ToolRegistry.kt**: dispatch,
  `FOREGROUND` narration category, and Operator + App Navigator availability
  (including a trimmed navigator schema, without which `toolsForNavigator()` would
  silently drop it).
- **tools/SystemTool.kt**: the Wi-Fi fallback now goes through `SettingsRouter`
  rather than a private duplicate of the same intent.
- **Security-relevant screens require confirmation.** `developer_options`,
  `lock_screen`, `vpn` and `device_admin` refuse until `confirmed=true`, directing
  the agent to the existing `question` tool. This guards against accidental and
  prompt-injected changes; it is not a security boundary, since `run_root_command`,
  `write_secure_settings` and `navigate_app` reach the same screens.

### Changed — silent settings changes now report what they overwrote

A background change the user cannot observe was also one they were never told
about. `set_volume` already reported its previous value; the rest now match.

- **tools/SystemTool.kt**: `set_brightness` reports `Brightness 30% → 60%`, and
  reports `auto` when adaptive brightness was on rather than quoting the
  system-chosen value as if it were the user's setting.
- **tools/DeviceTool.kt**: `set_dnd` and `set_ringer_mode` report their previous
  state. `toggle_torch` deliberately does not — `CameraManager` has no synchronous
  torch getter, and a flashlight is visible to the user anyway.
- No undo tool: the model reverts by calling the setter again with the value it was
  told, which avoids a stored revert overwriting something the user changed by hand
  in the meantime.

### Fixed — duplicate Settings skills

`assets/skills/settings.json` and `assets/skills/settings/settings_search.json`
both targeted `com.android.settings`. The asset walk is recursive and
`SkillPromptBuilder` concatenates every match, so both were injected on every
Settings turn with overlapping, partly contradictory advice ("ALWAYS look for a
search bar" vs "start searching manually by swiping").

- Deleted `settings/settings_search.json`; `settings.json` is now the single
  Settings skill and teaches the tier order, with searching demoted to step 4.
- Gated on `requiresTools: ["open_setting"]`.
- **SkillRegistryTest**: the shadowing test asserted on a phrase from the shipped
  skill's text; it now asserts against the community text, so rewording the skill
  cannot fail a test about id collisions.

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
