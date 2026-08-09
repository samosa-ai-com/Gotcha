# Gotcha

An Android assistant that actually operates the phone. You describe a task in
chat; an LLM plans it and carries it out through a catalog of on-device tools —
reading the screen via the accessibility service, tapping and typing, opening
apps and settings, managing files, alarms, media, mail and calendar.

Gotcha talks to **any OpenAI-compatible `/chat/completions` endpoint**, so it
runs against OpenAI, OpenRouter, Groq, or a model served locally by
llama.cpp / LM Studio / Ollama.

> **Status:** early (`versionName 1.0.0`). Expect rough edges, and read the
> safety notes below before pointing it at a phone you care about.

## What it can do

101 tools are catalogued and classified. A sample:

| Area | Tools |
|---|---|
| Screen control | `read_screen`, `tap`, `swipe`, `input_text`, `press_key`, `global_action`, `navigate_app` |
| Apps & settings | `open_app`, `open_setting` |
| Files | `read_file`, `write_file`, `edit`, `list_files`, `glob`, `grep`, `run_command` |
| Communication | `send_email`, `compose_email`, `send_sms`, `dial_number`, `call_number` |
| Calendar & tasks | `create_calendar_event`, `edit_calendar_event`, `create_task`, `complete_task` |
| Device | alarms, timers, media control, camera and audio capture, screenshots |
| Connectors | Google, Microsoft Graph, Notion, generic IMAP |

The full table — every tool, its test tier, and how it is verified — is
generated from a manifest into [`docs/FEATURE_TEST_COVERAGE.md`](docs/FEATURE_TEST_COVERAGE.md).

### Monitor vs Operator

Two agent modes, switchable mid-conversation:

- **Monitor** — read-only. Inspects, lists, and reads; cannot create, modify, or
  delete. Safe for exploration.
- **Operator** — full permissions, including accessibility automation, SMS,
  calls, deletion, and root commands.

Sensitive actions — dialling, writing files, running commands, and similar —
additionally pop an Allow/Deny prompt before they run. That gate can be turned
off in Settings.

## Requirements

| | |
|---|---|
| Device | Android 11 (API 30) or newer — phone or emulator |
| Build | Android Studio Hedgehog+, JDK 17, Android SDK Platform 34 |
| LLM | An API key for any OpenAI-compatible endpoint |

## Quick start

```bash
./gradlew installDebug
```

Then open the app — it lands on Settings, because no model is configured yet.
Fill in **API key**, **Base URL** (including `/v1/`), and **Model name**, and
grant the permissions the app prompts for.

Full setup, permission-by-permission notes, on-disk layout, and troubleshooting
live in [`RUNNING.md`](RUNNING.md). For the test suite, see
[`docs/RUNNING_TESTS.md`](docs/RUNNING_TESTS.md).

## Safety

Gotcha drives a real device through the accessibility service and, in Operator
mode, can send messages, place calls, delete data, and run shell commands. An
LLM planning those actions will sometimes get them wrong.

- Start in **Monitor** mode.
- Prefer a spare device or an emulator while evaluating.
- API keys are stored in `EncryptedSharedPreferences` and are never logged, but
  screen contents are sent to whichever model provider you configure.

## Development

```bash
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew detekt lintDebug     # static analysis — no baselines, everything must pass
./gradlew assembleDebug
```

The unit tests shell out to a POSIX `sh`, so run them from Linux, macOS, or Git
Bash. CI (`.github/workflows/ci.yml`) is disabled by default to conserve Actions
budget and can be run manually from the Actions tab.

## License

[GNU Affero General Public License v3.0](LICENSE) — Copyright © 2026 Samosa AI.
