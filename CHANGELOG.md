# Changelog

All notable changes to Gotcha are documented here.

## [Unreleased]
### Added
- Five bundled skills targeting Termux (`termux_operations`, `termux_repositories`,
  `termux_filesystem`, `termux_background`, `termux_proot`) so the model can speak
  authoritatively about Termux's limits instead of rediscovering them one failure
  at a time. See `docs/termux-setup.md` for the user-facing summary.
- Expanded `run_termux_command` tool description with realistic limits (no
  interactive prompts, 32KB output cap, 600s timeout, 4-command concurrency,
  Android 12+ background rule) and pointers to the bundled skills.
- `TermuxMessages.timedOut` and `startFailed` now point at the relevant skill via
  `search_skills` so the model recovers with the right pattern on mirror failures
  and backgrounded-start refusals.

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
