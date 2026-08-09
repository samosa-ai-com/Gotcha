# Security Policy

## Supported Versions

| Version | Supported |
| ------- | ------------------ |
| latest release | :white_check_mark: |
| older releases | :x: |

Gotcha ships as an `.apk` on [GitHub Releases](https://github.com/samosa-ai-com/Gotcha/releases). Fixes land in the next release — please update before reporting a bug against an older build.

## Reporting a Vulnerability

We take security seriously. If you discover a security vulnerability in Gotcha, please report it responsibly.

### How to report

1. **DO NOT** create a public GitHub issue for security vulnerabilities.
2. Use [GitHub's private vulnerability reporting](https://github.com/samosa-ai-com/Gotcha/security/advisories/new), or email the maintainers at [samosa.ai.com@gmail.com](mailto:samosa.ai.com@gmail.com).

### What to include

- Description of the vulnerability
- Steps to reproduce — Android version, device or emulator, app version, and copilot mode (Monitor / Operator) if relevant
- Potential impact
- Suggested fix (if any)

### Response timeline

- **Acknowledgment**: within 48 hours
- **Initial assessment**: within 7 days
- **Resolution**: depends on severity, typically within 30 days

### What to expect

- We will acknowledge your report promptly.
- We will keep you informed of our progress.
- We will credit you in the fix announcement, unless you prefer anonymity.

## Security Considerations

Gotcha is an autonomous copilot with deep device access, so its threat model is unusual for an Android app. The areas below are where we most want your eyes.

### On-device by design

- **Everything runs in one app on your phone** — no companion PC, no remote server, no ADB tether.
- **Credentials** (LLM API keys, connector OAuth tokens) are stored in `EncryptedSharedPreferences`, never logged, and never synced off-device.
- **Chats, action logs, and caches stay local.**
- **What does leave the device**: requests you make are sent to whichever LLM endpoint you configure — including screen contents when the copilot reads the screen, and screenshots when using a vision model. Choose your provider accordingly, or run a local model.

### Safety boundaries

These are the controls a vulnerability report would most usefully target:

| Boundary | What it enforces |
|---|---|
| **Copilot modes** | Monitor is read-only (~30 tools); Operator unlocks the full catalog. A Monitor-mode escape is a security bug. |
| **Capability tiers** | Tier 0–1 everyday, Tier 2 personal (calls, SMS, calendar, location, camera), Tier 3 automation (screen touch, notifications, device admin), Tier 4 privileged (root — fails closed on unrooted devices). |
| **Confirmation gate** | Sensitive actions pop an explicit Allow/Deny prompt before running. |
| **Command deny-list** | Blocks dangerous shell invocations in the terminal tool. |
| **Audit log** | Append-only record of executed actions. |
| **Fixed tool catalog** | Tools are defined in code — the model cannot invent a new tool at runtime. The tool loop is bounded (300 rounds per message by default) with an anti-loop guard. |

**Prompt injection is in scope.** Gotcha reads screen content, notifications, files, and email — all of which can carry attacker-controlled text. A report showing that injected content can drive the copilot past a confirmation gate, a mode restriction, or a capability tier is exactly the kind of finding we want.

### Best practices for users

1. **Start in Monitor mode** and switch to Operator only when you need it.
2. **Evaluate on a spare device or emulator** before pointing Gotcha at a phone you care about.
3. **Grant permissions carefully** — Gotcha only gets what you approve, and each tier meaningfully widens what it can do.
4. **Leave the confirmation gate on.** It can be disabled in Settings; don't, unless you fully understand the consequences.
5. **Choose your LLM provider deliberately** — screen contents go wherever you point it. A local model server keeps everything on your network.
6. **Install only from official sources** — [GitHub Releases](https://github.com/samosa-ai-com/Gotcha/releases) or F-Droid. Verify the APK signature; release builds are signed and the workflow verifies asset integrity.
7. **Keep backups.** An LLM driving your device can get things wrong.

## Disclaimer

Gotcha is powered by Large Language Models operating autonomously across powerful Android APIs. Models can hallucinate, misinterpret instructions, or execute unexpected tool calls, which can cause accidental data loss, unexpected configuration changes, or file corruption. We explicitly assume no responsibility or liability for any loss, damage, system issues, or data corruption incurred through use of Gotcha. Use it responsibly and at your own risk.

## Scope

This policy covers the Gotcha Android application. Third-party dependencies, LLM providers, and connector services (Google, Microsoft Graph, Notion) have their own security policies and disclosure processes.

---

Thank you for helping keep Gotcha secure! 🔒
