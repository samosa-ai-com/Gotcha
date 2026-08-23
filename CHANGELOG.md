# Changelog

All notable changes to Gotcha are documented here.

## [Unreleased]
### Fixed
- The influencer program card now appears in release builds. 1.1.0 was assembled
  without its form configuration, so the card was hidden and there was no way to
  apply from inside the app.

## [1.1.0]
### Added
- **Referral program.** You can now share your referral code from a dedicated
  in-app dialog (with one-tap copy), and accounts that joined through a code
  show a referral badge. Referral codes are accepted on their own, without
  requiring the referrer's full account details.
- **Account tiers and tags.** Sign-in now carries your tier and tags through to
  the app, so tier-gated features can be surfaced correctly rather than failing
  with an opaque error.
- **Influencer program card**, with in-app handling of the program link.
- **Local HTML serve.** When you ask Gotcha to build an app, game or page
  without naming a runtime, it now defaults to a self-contained HTML file in the
  chat's folder and serves it on a local port, handing you back a
  `http://127.0.0.1:PORT` link instead of detouring through a heavier setup. It
  asks a single clarifying question when the request is genuinely ambiguous.
- **Five bundled skills targeting Termux** (`termux_operations`,
  `termux_repositories`, `termux_filesystem`, `termux_background`,
  `termux_proot`) so the model can speak authoritatively about Termux's limits
  instead of rediscovering them one failure at a time. See
  `docs/termux-setup.md` for the user-facing summary.
- Expanded `run_termux_command` tool description with realistic limits (no
  interactive prompts, 32KB output cap, 600s timeout, 4-command concurrency,
  Android 12+ background rule) and pointers to the bundled skills.
- `TermuxMessages.timedOut` and `startFailed` now point at the relevant skill via
  `search_skills` so the model recovers with the right pattern on mirror failures
  and backgrounded-start refusals.

### Changed
- Tier-restricted responses now show a generic, readable message instead of a
  raw permission error, and the referral dialog has a skinned backdrop.
- Termux commands are noticeably faster and more reliable on long jobs:
  adaptive timeouts, non-interactive mirror switching, APT tuning, quieter
  output handling, and a wake-lock so background work is not killed mid-run.
- When Termux is available, longer-running local servers now run there rather
  than through the lighter in-app fallback, avoiding redundant probing.

### Fixed
- Fixed a layout crash caused by the intrinsic-size rail calculation; long text
  is now truncated rather than breaking the screen. (#55)
- Interrupting an agent mid-run no longer breaks the rest of the chat. Cancelled
  tool calls left the conversation in a state the provider rejected, which made
  every later message in that chat fail with an HTTP 400.
- The chat's working folder is now stable for the whole run, so the agent no
  longer loses track of paths when a chat is renamed after its title is
  generated.
- The agent is now told how to stop a stuck Termux command: a `run_termux_command`
  cannot kill a previously running one, so the instructions guide it to open the
  notifications shade and tap Exit on the Termux notification (asking the user to
  do so when it cannot reach it), instead of failing or starting more commands.

## [1.0.2]
### Fixed
- Release builds now ship with the correct Samosa AI service configuration. The
  published 1.0.1 APK was assembled without it and silently fell back to inert
  placeholder endpoints, leaving AI chat, Google sign-in and in-app feedback
  unable to reach the backend. Users on 1.0.1 should update.

### Changed
- Release builds are now verified before publication: the APK must be signed
  with the official Gotcha release key, must not be debuggable, must include
  native libraries for all supported device architectures, and must contain no
  placeholder configuration. The published download is re-checked against the
  update manifest so a corrupted upload can no longer reach devices.

### Note for existing users
- If "Check for Updates" downloads this release but installation fails with
  "App not installed", the copy on your device was signed with a different key
  and Android will not replace it. Uninstall Gotcha, install this release
  manually once, and future in-app updates will apply normally.

## [1.0.1]
### Changed
- Simplified the setup-recommendation copy on the AI Configuration and Speech
  settings screens; the Speech screen now recommends the Android built-in
  engine for mixed-language text (e.g. Hinglish) and Samosa AI for
  single-language speech.

### Fixed
- Corrected the About screen's GitHub link and contact email (and the matching
  legal/company docs) to point at the real repository and address.
