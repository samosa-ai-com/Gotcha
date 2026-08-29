# Changelog

All notable changes to Gotcha are documented here.

## [Unreleased]

## [1.2.0]
### Added
- **Podcast generation.** Gotcha can now turn text into listenable audio. Ask
  for a topic, an article or your notes as a podcast and the assistant writes
  the script and speaks it through your configured text-to-speech API — as a
  single narrator (`synthesize_podcast`) or as two hosts in conversation with
  distinct voices (`synthesize_podcast_dialogue`; pick the host voices in
  Settings → Speech). The assistant paces the conversation as it writes it,
  choosing the silence after each turn — a quick interjection runs straight
  into the reply, a revelation gets a beat first — so an episode breathes
  the way the script intended instead of ticking along on a fixed gap.
  Long scripts are synthesized in segments and joined on the device, episodes
  land under `Gotcha/Podcasts` as `.m4a` (or `.mp3` when Termux's ffmpeg is
  available), and `share_podcast` opens the system share sheet to send one
  anywhere. A voice memo can become an episode too: `transcribe_file` turns a
  saved recording into text — reading the file without ever modifying or
  deleting it — for the assistant to script and re-voice. Speech synthesis and
  transcription use your configured TTS/STT endpoints; the audio assembly never
  leaves the phone. Android's built-in TTS cannot write files, so these tools
  need Samosa AI or an External API selected under Settings → Speech.
- **Audio format conversion.** A new `media_convert` tool converts audio between
  MP3, M4A, AAC, OGG, Opus, WAV and FLAC using Termux's ffmpeg — still entirely
  on the device, with nothing uploaded anywhere. This is the only way to get an
  MP3 out of Gotcha: Android ships no MP3 encoder at all, so `media_edit` cannot
  write one however it is asked. The tool appears only when Termux is installed,
  and if its ffmpeg package is missing the error says exactly what to run.
- **Audio & video editing.** A new `media_edit` tool edits media entirely
  on-device: trim a window out of a clip, pull a video's audio into its own
  file, mute a video, shrink one to a smaller resolution, speed it up or slow it
  down, and join several files end to end, plus an `info` read for duration,
  resolution and tracks. Video and audio files work the same way, so trimming a
  voice memo and trimming a 4K video are the same request. Trimming, extracting
  and muting copy the streams rather than re-encoding, so they finish in seconds
  and lose no quality at all; compressing, re-timing and joining do re-encode,
  and Gotcha now says which of the two happened so you know whether a file was
  degraded. Every operation writes a new file and leaves the original alone.
  Adding music, overlaying text and editing what is inside a frame are not
  possible, and DRM-protected media cannot be opened at all — a bundled
  `media_editing` skill teaches the assistant to say so rather than attempt a
  workaround.
- **PDF editing.** A new `pdf_edit` tool reshapes PDFs entirely on-device: merge
  several files into one, split into single pages, extract or delete a page
  range, and rotate pages, plus an `info` read for the page count. Encrypted
  files open with a password you supply — but because the edited copy cannot keep
  that protection, Gotcha now refuses the edit until it has told you the copy
  will open without a password and you have agreed. Every operation writes a new
  file and leaves the original alone unless you ask for it to be replaced. Editing the
  text or images printed on a page is not possible — PDF stores glyphs at fixed
  coordinates, so there is nothing to reflow — and a bundled `pdf_editing` skill
  teaches the assistant to say so rather than fake it.

## [1.1.1]
### Fixed
- The influencer program card now appears in release builds. 1.1.0 was assembled
  without its form configuration, so the card was hidden and there was no way to
  apply from inside the app.

### Changed
- The download on GitHub Releases is now named `Gotcha.apk` instead of
  `app-release.apk`. Existing installs are unaffected — "Check for Updates"
  follows the link in the update manifest, whatever the file is called — and
  the older releases keep their original filename.

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
