# Changelog

All notable changes to Gotcha are documented here.

## [Unreleased]

### Added — Document attachments in chat (#24)

- **Attach more than images.** The composer's "+" now opens a picker that accepts
  documents as well as images: PDF, Word (`.docx`), Excel (`.xlsx`),
  PowerPoint (`.pptx`) and plain text (TXT/CSV/MD/JSON/HTML). A new
  `DocumentParser` (pure Kotlin, ZIP + XML for Office files, pdfbox-android for
  PDF, jsoup for HTML) extracts the file's text; legacy `.doc`/`.xls` and
  unsupported formats show a clear error instead of failing silently.
- **The model reads the file.** Attaching a document sends an
  `[Attached file: name (type, N pages)]` header plus the extracted text (capped
  at ~40k chars, ~10k tokens) as the message's single text part, so token
  accounting, history trimming and compaction all count it correctly. The bytes
  are copied into the app cache at pick time so a revoked content grant can't
  break a later send.
- **Attachment UI.** Pending attachments show as a chip (name, type, size,
  truncated flag) instead of a bitmap preview; sent messages render the same
  chip. Attachment metadata persists with the session, so editing a sent
  document message re-sends the extracted text without re-reading the file.

### Added — Home Assistant MCP connector (#21)

- **Gotcha can now control and query your smart home through Home Assistant's
  Model Context Protocol server** (Settings ▸ Devices & Services ▸ Model Context
  Protocol Server). Connect with your Home Assistant URL plus a long-lived access
  token (profile ▸ Security ▸ Long-lived access tokens).
- **The tools come from your server, not a fixed list.** On connect, the app
  calls the MCP `tools/list` endpoint and registers exactly the tools Home
  Assistant exposes for the entities you share with Assist — e.g. `HassTurnOn`,
  `HassGetState`, `GetLiveContext` — so the agent can toggle a light or read a
  sensor state in the same way the built-in connectors work.
- **Monitor/Operator split is preserved.** Read-only HA tools (state/context
  reads) are offered to Monitor; everything else is Operator-only. Disconnect —
  or switching the connector off — hides the tools again from the model.

### Changed — Wake word & call-mode feel (#25)

- **The wake-word acknowledgment is now one short word.** Saying "Hey Gotcha"
  used to reply with the full "Call started. I'm ready when you are." sentence,
  which made the wake word feel ignored and delayed the mic opening. It now
  answers with a single "Yes?" (translated per language) and the mic opens
  sooner. Normal long-press calls get a tighter "Call started. Go ahead."
- **The ball now visibly reacts when the wake word fires.** It gives a quick
  scale bounce while an accent ring radiates from it on its own overlay window,
  so the pulse still plays after the ball is hidden by the call window. Respects
  the system "remove animations" preference.
- **Long-press to start a call is 1 second instead of 2.** The hold-to-call
  gesture on the floating ball was reduced from 2000 ms to 1000 ms; the
  expanding ring that previews it stays in sync.

### Fixed — Call mode no longer swallows STT/TTS failures (#23)

- **A failed mic tap is now visible.** When `startMic` can't start the
  recognizer (busy, MediaRecorder failure, provider unavailable), the call shows
  "Couldn't start the microphone — tap again to retry." instead of silently doing
  nothing; the mic stays ready for another tap.
- **Hands-free STT failures are no longer mistaken for silence.** The wake-word
  listen loop now tells a real recognition failure apart from "No speech
  detected": genuine errors (network, permissions, recognizer busy, ...) are
  surfaced with the existing error card + vibration and end the call with an
  explanation, while silence keeps its quiet retry-and-auto-end behaviour. Only
  silence counts toward the auto-end strikes.
- **Tool-progress narration TTS failures surface too.** A failed narration shows
  the shared "Couldn't play voice audio — check your Text-to-Speech settings."
  card once per call (repeats are logged), instead of being fire-and-forget.
- **In-call error line.** The two floating call buttons now show a transient
  error line above them, so an in-call error is visible right where the user's
  eyes are even when the overlay card is off-screen. The overlay card remains
  the persistent mid-call surface.
- Hands-free loop also hands control back to the pre-listen state after a blank
  listen, so retry (and answering an agent question in place) works as intended.

### Fixed — Malformed tool call no longer bricks the chat (#13)

- **A tool call with invalid `arguments` JSON can no longer poison a chat.** When
  the model emits a tool call whose `arguments` aren't parseable (e.g. a cut-off
  summary with stray `</summary>` tags), the app used to keep that raw message in
  the history and re-send it on every later turn — and some servers 400 the whole
  request when they see it, so every subsequent message failed too.
- Malformed tool-call arguments are now neutralized to `{}` **before they are
  stored or sent** (sanitized on append and again at the `LLMClient` send
  boundary), so the chat keeps working. The model still receives the truthful
  "Malformed tool arguments" tool result so it can retry. Sessions saved before
  the fix self-heal on their next request.

### Added — `update_user_profile` agent tool (#18)

- **The agent can now keep the user's profile fresh.** A new Operator-only
  `update_user_profile` tool records durable facts the user reveals in
  conversation — a new job/role (`occupation`), background details
  (`background`) and reply preferences (`reply_style`) — straight into
  Settings ▸ Personal Info, so the knowledge isn't lost next session.
- **Modify-and-extend, never erase.** The model is instructed to pass the
  complete merged value for each field it changes, preserving prior facts and
  removing only what the user clearly outgrew; blank values are ignored so
  stored information is never wiped wholesale.
- **Stays compact.** `background` is capped at 250 words and `reply_style` at
  50 words in-app, and a no-op guard skips writes when nothing material
  changed (keeps updates infrequent and the system prompt cache mostly stable).
- **Operator only.** Monitor keeps its read-only contract; the tool is also
  withheld from sub-agents. Works in chat and voice calls.
- A short maintenance directive in the Operator system prompt (next to
  `<user_profile>`) tells the model when to call the tool and the caps.

### Added — Collapsible Connectors cards (#20)

- **Connector cards collapse by default.** The Connectors screen now shows one
  compact row per connector — title, live status line, and (once connected) the
  enable switch — with the setup steps, credential fields and buttons hidden
  until the row is tapped. Each card expands independently via a rotating
  chevron on its header.
- **Enable switch stays reachable.** Toggling a connector on/off no longer
  requires expanding its card; the switch lives on the always-visible header
  once connected. The expanded body keeps the explanatory copy and Disconnect.
- **Connect affordance hint.** A disconnected connector's status reads
  "Not connected — tap to set up" so it is clear the header opens the setup.
  "Reconnect needed" OAuth states keep their fuller message.

### Added — Scaled Samosa credit display (#19)

- **Remaining credit shown, never the raw value.** The AI Config and Speech
  pages now show a "Credits remaining:" line under the signed-in email,
  scaled by ×1000 to a whole number (e.g. `2.0` credits → `2,000`). The raw
  `credits_remaining` float is never rendered.
- The balance is fetched live from the auth-manager `GET /me` on screen open
  and on sign-in (no polling). When not signed in, or the gateway is
  unreachable / the user has no gateway key (`credits_remaining` is null),
  the line is hidden and the page is unaffected.
- `GET /me` now parses the `{ user: … }` envelope correctly, so the feedback
  form's user-id pre-fill uses the real account id instead of always falling
  back to the stored email.

### Changed — PR #77 user-visible behavior

- **Share poster is top-bar only.** The per-message "Share as poster" icon on
  the assistant message bubble has been removed. The share card is now
  reachable only from the top-bar "Create share card" action, which produces
  a recap of the whole chat.
- **Poster branding is fixed.** The poster footer now reads "Built with Samosa
  AI · on Android" and the model-name chip is hardcoded to "🤖 Samosa AI"
  regardless of the configured model. The model identity is no longer carried
  on `PosterStats`.
- **Share-card `eligible: false` is treated as a parse failure.** When the
  marketing LLM returns a JSON verdict that the digest is not worth promoting,
  the share card silently falls back to the deterministic poster instead of
  failing. The fallback always renders, so the card never appears to break
  intermittently.

### Fixed — PR #77 review follow-ups

- `copyTextSelection` in `ScreenLensController` now rejects indices that run
  past the end of the text instead of throwing `StringIndexOutOfBoundsException`.
- `CallSessionController.buildClient()` caches the `LLMClient` under a monitor
  so the main thread and the engine coroutine cannot both rebuild for the same
  fingerprint.
- `loadShareScreenshot()` rejects over-sized data URIs before allocating the
  Base64 byte array.
- `gotcha` overlay system uses a shared `MaxHeightScrollView` (was three
  inline `onMeasure` overrides).
- `GotchaApp` writes uncaught exceptions to `filesDir/crash.log` (capped at
  50 entries) in addition to logcat, so post-mortem analysis is possible
  from a user report after the logcat buffer has rolled over.

### Fixed — Notion databases unreadable (#8)

- `notion_read_page` now reads **databases** as well as pages. A Notion todo
  list is a database, and previously passing its id returned a 404 that wrongly
  blamed page sharing — now it falls back to `GET /databases/{id}` +
  `POST /databases/{id}/query` and renders each row with its checkbox state.
- Inline `child_database` blocks inside a page are queried and embedded instead
  of rendering as `[unsupported block: child_database]`, so a todo list dropped
  onto a page is readable too.
- `notion_search` shows a database's real title instead of `(untitled)`.
- `notion_search` no longer crashes when a database appears in the results: a
  database's title column definition carries `"title": {}` (an empty object,
  not an array), and rendering now treats any non-array as empty text instead
  of throwing "is not a JsonArray".
- Reads paginate past 100 blocks via `start_cursor` and recurse into nested
  blocks (bounded by a shared budget); if a cap is hit, the output says
  `[truncated]` instead of silently dropping content.
- A 404 that names a database steers to re-running `notion_search` rather than
  to re-sharing the page.

### Added — Notion update/delete tools

- `notion_update_page` updates a page's or database row's properties — pass
  column names mapped to simple values, e.g. `{"Done": true}` marks a todo row
  done, `{"Status": "In progress"}` changes a status. The column type is looked
  up from the page so the correct Notion payload is built.
- `notion_mark_todo` checks or unchecks a `to_do` block on a page
  (`checked: true`/`false`).
- `notion_delete_item` moves a page/row to the Notion trash (`item_type:
  "page"`, recoverable) or permanently deletes a block (`item_type: "block"`).
- `notion_read_page` output now marks every editable item with an id —
  `[row-<id>]` for database rows and `[block-<id>]` for to-do blocks — and lists
  each database's `Columns:` so the model knows which values it can update.

### Added — HumanReadableError (PR #77 utility)

- New `com.gotcha.util.HumanReadableError` centralizes HTTP status, STT/TTS
  error codes, and network exceptions. `AgentEngine`, `ChatViewModel`,
  `AssistiveBallService`, and `SttEngine` now route through the helper.

### Added — Voice-call prompt-cache (PR #77 performance)

- `CallSessionController` reuses a single `LLMClient` across turns within a
  call so the in-memory `LLMCache` survives. A stable `CALL_PROMPT_CACHE_KEY`
  is passed to the provider for server-side KV reuse.

### Added — Personal Info, fed into the system prompt

The assistant knew what device it was running on and nothing about the person
using it. Preferred language and currency were the only two facts it had, and
they were filed under Proactive Assistance, where they only looked like settings
for that feature.

- **ui/PersonalInfoScreen.kt** (new, first row on the settings home list): name,
  location, occupation, a free-text background, and a free-text reply style,
  plus the preferred language (with its Test voice button) and currency moved
  over from Proactive Assistance. Every field is optional and stays on the
  device.
- **AgentEngine**: the facts render as a `<user_profile>` block after `<env>` —
  a block of its own because `<env>` describes the device and this describes the
  person, and the model needs to keep the two apart. Blank fields are omitted
  entirely; an untouched profile is two lines, not a list of "unknown"s the
  model then tries to fill in by asking. Newlines inside a field are collapsed
  so a multi-line background can't be read as the start of another fact.
- The reply-style text is injected next to the language directive at the tail of
  the message array instead, since it is an instruction about the reply being
  written now rather than a fact to remember. It is explicitly subordinate to
  safety constraints, the mode restrictions and tool-required formats.
- **ui/ProactiveScreen.kt** is now only the proactive toggles, and no longer
  needs `onTestVoice`.
- **SettingsRepository**: `load()` reads strings through a new `string()` helper
  (the sibling of the existing `stringSet()`). `getString` returns a nullable
  even with a non-null default, so every field carried its own elvis; the new
  Personal Info fields tipped the accumulated count past detekt's complexity
  ceiling.

### Changed — Notifications is a page like everything else

Notifications sat at the top of the settings home list, above every category
row, which put the least-used setting in the place the eye lands first.

- **ui/NotificationsScreen.kt** (new): the vibration and chime switches move to
  a page of their own, last in `SettingsPage` and so last on the home list.
  Appearance stays inline — one three-way control, applied on touch.
- The page keeps its apply-on-touch behaviour and has no Save button, which is
  the point: switching one on plays it once. A preview you had to save first
  would be feedback about a setting you had already committed to.
- **SettingsToggleRow** gained `switchTestTag`, so the notification tags stay on
  the `Switch` rather than moving to the row around it — a tag on the row finds
  a node that isn't toggleable, and a test clicking it would no-op in silence.

### Changed — Settings is now a list of pages, not one long scroll

Settings was a single scrolling screen with five accordions, one of which
(Permissions) nested a second accordion level inside the first. It now works like
the system Settings app: a home list of categories, each opening its own page,
Back returning to the list.

- **ui/SettingsScreen.kt**: reduced from 1930 lines to a nav host over the new
  `SettingsPage` enum plus the home list. Appearance and Notifications stayed on
  the home list at this point — two controls each, applied on touch; Notifications
  moved to a page of its own afterwards (see above). The open page is
  `rememberSaveable`, so a rotation doesn't return the user to the list.
- **ui/SettingsCommon.kt** (new): `SettingsPage` (title, summary and test tag per
  category, so a new category is one enum entry), `SettingsScaffold`,
  `SettingsOverlayState` (owns the transient message *and* its auto-dismiss timer),
  `SettingsNavRow`, `SamosaAuthSection`, `SettingsToggleRow`.
- **ui/AiConfigScreen.kt**, **SpeechScreen.kt**, **PermissionsScreen.kt**,
  **SkillsScreen.kt**, **ProactiveScreen.kt** (new): one file per page, each
  holding only the state it owns. The audio pickers moved to `SpeechScreen.kt`,
  their only caller.
- **Fixed:** every section's Save button previously rebuilt a complete `Settings`
  from all ~45 form fields, so saving in AI Configuration also persisted whatever
  had been typed into Speech or Proactive, half-finished or not. Each page now
  supplies a mutator that copies only its own fields onto freshly loaded settings.
  `onNotifyAlertChange`, which existed solely to route the reply-alert switches
  around that hazard, is gone. The form also no longer writes
  `samosaSessionToken` / `samosaEmail` — `SamosaAuthManager` owns those, and
  echoing a stale copy back could resurrect a session cleared by a 401.
- **MainActivity.kt**: an unconfigured install still opens Settings, but now lands
  directly on the AI Configuration page rather than on a category list that gives
  no hint where the API key lives.

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
ID token for a backend session JWT, which is then used as the bearer
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
  backend `POST /register`, `GET /me`, and `POST /logout` endpoints against
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
