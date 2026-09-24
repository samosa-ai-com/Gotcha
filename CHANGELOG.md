# Changelog

All notable changes to Gotcha are documented here.

## [Unreleased]
### Added
- **Import chats, and back them up** (#83). "Import chats" in the navigation
  drawer reads back a chat exported as Markdown ("Export chat" in a chat's ⋮
  menu), so a shared or saved conversation can be opened and continued. The
  Markdown export holds text only, so an imported one has no images or
  attachments and shows tool calls as the export did. For a complete copy, "Back
  up chat" in the ⋮ menu and "Back up all chats" in the drawer save a
  `.gotcha.json` file that imports back exactly, for moving to a new phone or
  restoring. Before saving, Gotcha says what a backup holds and offers to leave
  images out. Before importing, it says what the file holds; when a chat is
  already here in another version, you choose to keep both, replace yours or
  skip it, and an unchanged copy is skipped. A damaged chat in a backup is
  reported by name and the rest still import; a file from a newer Gotcha, one
  that isn't a Gotcha chat, or one over 64 MB is refused with the reason. A
  Markdown file's "System" sections come in as labelled notes, never as system
  instructions, so a shared file can't use one to steer the assistant. The
  Markdown export now also carries the chat's title.
- **Gotcha's own notifications, and a list of them** (#100). Gotcha now works
  out from your chats, on the phone, when a notification would actually help,
  and nothing about how you use it is uploaded to decide. It reminds you about a
  chat left unfinished (a task that failed or was stopped, or a question you
  didn't answer, a few hours on); suggests a request you make regularly once
  it's due again, opening a new chat with it ready to send; and nudges you after
  a quiet spell (four days by default), offering your last chat back. A new
  "Gotcha notifications" section in Settings → Notifications turns each of these
  and the daily tip on or off, sets the quiet spell, quiet hours (22:00–08:00 by
  default), and how many a day (two by default), and whether notifications may
  name chats. The same occasion is never notified twice, one of a kind a day at
  most, and the lock screen never shows the chat. A bell on the chat screen opens
  the last 30 days of notifications — reminders, tips, finished tasks and
  messages from Samosa AI — each opening where its notification did, and the
  history can be cleared. A chat's new ⋮ menu (which also holds Export and the
  share card) keeps it out of notifications altogether; Doctor chats start that
  way. Task-finished notifications for such chats, or with naming off, say only
  that a task finished.
- **Daily tips** (#101). Once a day, at 10:00 by default, Gotcha sends a
  notification suggesting one thing to try: reading your screen, catching up
  on notifications, texting someone, planning the day. Tapping it opens a new
  chat with the prompt already in the composer, to edit or send as it is. Tips
  for tools you have never used come first, none repeats until ten others have
  been shown, and no tip is sent on a day you have already used Gotcha. Tips for
  device actions start the chat in Operator. On by default; Settings →
  Notifications turns tips off or changes the time, and they have their own
  "Daily tips" channel in Android's settings.
- **The assistant can change Gotcha's settings, with your approval every
  time** (#99). Ask it to turn off the reply chime, switch the skin, stop
  scanning the clipboard or turn a connector off, and it proposes the change
  through a new `update_gotcha_settings` tool. Nothing is written until you
  approve a prompt that lists each setting with its current and new value and
  says whether it touches privacy, notifications, permissions or device
  control, and an approval never carries over to the next change. Only an
  allowlist can be changed — notifications, reply and speech language, read
  aloud, skin, proactive assistance, wake-word listening mode and sensitivity,
  connectors and skills. API keys, sign-in, the model and run limits are out of
  reach, and an unknown key or bad value is refused before you are asked
  anything. Approved changes take effect at once (the skin repaints without a
  restart), and both approvals and refusals go into the action log. Operator
  mode only.
- **Chat personas.** The starter chips said what to ask; nothing said who to ask.
  A persona row now sits under the agent selector on an empty chat — **Doctor,
  Chef, Fitness Coach, Tutor, Travel Planner, Handyman** — and picking one starts
  the chat in that role, with the role's own instructions carried in the system
  prompt for every turn of it. The role is chosen before the first message and
  fixed from there: it is saved with the chat, so reopening one weeks later is
  still answered in role (the top bar names it), and a new chat never inherits
  the last one's. Each persona starts read-only in Monitor and the selector stays
  live, so taking one into Operator is still your call, and a role never loosens
  what the agent is allowed to do — the mode restrictions remain the last word.
  Where a role shadows a regulated profession its prompt says so: the Doctor
  never presents itself as your doctor, and routes emergencies to real care.
- **Search the settings.** Fifteen settings pages hold well over a hundred
  controls between them, and finding one meant scrolling the list and guessing
  which page owned it — "wake word" is under Assistive Ball, "API key" under AI
  › AI Configuration. A search field now sits pinned above the settings list:
  type what the control is called and the list narrows to the pages that hold
  it, matching titles, summaries and the everyday words for the fields inside
  ("read aloud", "dark mode", "otp", "accessibility"). Pages that live inside a
  hub — Speech, AI Configuration, Legal — show up too, named with the hub they
  sit in and opened in one tap, which the list itself can't do. Clearing the
  field brings the whole list back.
- **Voice and language pickers name the language.** The Speech page listed raw
  codes — `hi`, `en-us`, `af_heart` — with nothing saying which language each
  one is. Transcription language entries now read like `hi — Hindi`, the field
  shows the name of the code you typed, and TTS voices read like
  `af_heart — English (United States), female` (the language and gender are
  taken from the server, or read off Kokoro-style voice ids when it sends
  none). The Samosa AI TTS and STT sections also link to the Samosa AI docs on
  choosing a voice and language.
- **Two sample chats on a fresh install.** The chat list opened on nothing at
  all, so the one thing a first-time user couldn't find out was what Gotcha is
  for. A new install now starts with two short transcripts that show it: a
  device action with a follow-up in **Operator** (turn Wi-Fi on, then the
  Bluetooth screen the agent opens because Android won't let it flip that switch
  itself) and a question about the screen in **Monitor**. They are ordinary
  chats — open them, carry them on, or delete them — and both the drawer row and
  a line above the transcript say they're samples, so a demonstration is never
  mistaken for something you said. Seeding happens once per install and only
  into an empty list: upgrading keeps the chats you have, and deleting the
  samples is final.
- **The assistant can now answer questions about Gotcha itself.** There was a
  tool for the company behind the app (`about_samosa_ai`) but none for the app,
  so "what can you do?" and "which setting do I change to read replies aloud?"
  were answered from the model's memory or by driving the Settings screens to
  rediscover a path — both of which produce confident directions to places that
  don't exist. A new `about_gotcha` tool reads a bundled handbook covering the
  capability areas, Monitor vs Operator, the exact path to every settings page,
  which permission each group of tools waits on and where it's granted, and the
  safety model. It's read-only, so **Monitor** has it too. The handbook is
  checked against the `SettingsPage` and `Capability` enums by a test, so
  renaming a settings page fails the build rather than quietly leaving the agent
  with a stale path.
- **Starter prompts on the home screen.** A new chat used to be a greeting and an
  empty composer, which says nothing about what Gotcha can be asked for. Three
  suggestion chips now sit under the agent selector — drawn per session from a
  set covering the things people least expect: driving the device, reading the
  screen, going through the filesystem (`Find the largest files in my Downloads
  folder and tell me what's safe to delete`, `Read the most recent PDF in my
  Downloads and summarise it`) and handling messages. Tapping one **fills the
  composer and stops there**: nothing is sent until you've read it, edited the
  parts left blank, and pressed send yourself. Nor does a chip change the agent
  mode on your behalf — the two device actions need Operator, the rest answer in
  Monitor, and which mode you're in stays the selector's business.

### Changed
- **Permissions are asked for when they're needed, not at startup.** A fresh
  install used to open with thirteen system permission dialogs in a row —
  contacts, SMS, call log, camera, microphone, location — before you had asked
  Gotcha for anything, which reads less like setup than like a shakedown.
  Nothing is requested at launch now. The first time a tool actually reaches for
  one, Gotcha says in a sentence what it is about to do with it — *Gotcha needs
  your contacts to turn a name into a number, so asking it to call Priya reaches
  the right Priya* — and only then does Android's own dialog appear; grant it and the
  action you asked for carries straight on, rather than failing and having to be
  repeated. "Not now" is a real answer, and the next request starts fresh. First
  run works with nothing granted at all: every capability is still listed under
  Settings › Permissions, each row now saying whether it is granted, ready to
  switch on ahead of time. Turning one back off is no longer a toast naming a
  four-level path you can't follow before it vanishes — the switch opens the
  system screen that owns the permission. And on Android 13+, notification
  permission is asked for when you switch server messages on, which is the only
  moment it means anything.
- **Settings → AI.** The model and the voice used to sit as two unrelated rows on
  the settings list, as if choosing what Gotcha thinks with had nothing to do with
  choosing what it speaks with. Both now live under a single `AI` row:
  `AI Configuration` for the provider and models, `Speech (TTS / STT)` for voices
  and transcription. The pages themselves are unchanged, and so is everything
  already saved on them.
- **Advanced settings are collapsed by default.** The knobs almost nobody needs —
  sub-agent and navigator model overrides, the agent-loop limits, the API timeout,
  the cache-clearing buttons, the podcast host voices — are now behind an
  `Advanced settings` disclosure on the page they belong to, instead of standing
  between a new install and the Save button. Nothing moved pages, and expanding
  the section is never required to save.
- **Settings → About.** The hub that collects the company page, the legal
  agreements and the app updater is now titled `About` rather than `About Us`:
  it holds more than company information, so the old title undersold it.
  `About Samosa AI` and `Legal` still sit underneath it, unchanged.
- **Settings → Assistive Ball and Wake Word.** The `Hey Gotcha` wake word has
  always lived on the assistive-ball page — its listener runs inside the ball's
  service and cannot outlive it — but the row said only `Assistive Ball`, so
  there was nothing to tell you where the wake word was or why it switched
  itself off. The row now names both, and the page states the dependency whether
  the ball is on or off instead of only once it is already too late.

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
