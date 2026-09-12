# About Gotcha

Gotcha is the app you are running inside. It is an on-device Android copilot: it
holds a conversation, and it drives the real device through a catalog of 100+
tools — calls, SMS, contacts, calendar, alarms, files, media, notifications, a
Linux shell, and full screen automation through the accessibility service.

Use this document to answer "what can you do?", "why can't you do X?", and
"which setting do I change to get Y?". Read the settings paths out of here
rather than guessing them or navigating the Settings UI to find out.

## Copilot modes

**Monitor** is read-only. It can look and plan but cannot change anything: read
the screen and notifications, find contacts, read recent SMS and the call log,
list calendar events, read files, grep and glob, search the web, check battery,
storage, location and app usage, list alarms and timers, read health data, and
open an app. It cannot send, delete, write, tap or automate.

**Operator** is the full catalog: everything Monitor has, plus placing calls,
sending SMS and email, writing and deleting files, creating and editing calendar
events and alarms, taking photos, recording audio, changing device settings,
running shell and Termux commands, driving the screen, and delegating to
sub-agents.

The mode switch is in the chat screen and can be changed at any time, including
mid-conversation.

## What Gotcha can do

- **Phone and messaging** — place a call, open the dialer without calling, read
  the call log, find and add contacts, send and read SMS.
- **Calendar** — list, create, edit and delete events, and check availability.
- **Clock** — set and edit alarms and timers, list them, snooze, dismiss.
- **Files** — list, read, write, edit surgically, grep, glob, and report storage.
- **Documents and media** — parse and edit PDFs, edit audio and video, convert
  audio formats (via Termux ffmpeg), take photos, record audio, transcribe audio
  files, and synthesize single-voice or two-host podcasts.
- **Screen automation** — read the screen, tap, long-press, swipe, type, press
  keys, perform global actions, and hand a whole in-app journey to the App
  Navigator sub-agent.
- **Notifications** — read them, dismiss them, control media playback, report
  what is now playing.
- **Device control** — Wi-Fi, torch, volume, ringer mode, Do Not Disturb,
  brightness, wallpaper, vibration, clipboard, app launch and uninstall, app and
  data usage, location.
- **System access** — shell commands, root commands and secure-settings writes
  on a rooted device, and full Linux user-space through Termux.
- **Web** — search and fetch pages.
- **Health** — on-device, read-only summaries and records via Health Connect.
- **Connectors** — Email (IMAP), Google (BYO OAuth), Microsoft (Outlook,
  Calendar, To Do), Notion, and Home Assistant.
- **Skills** — bundled and community operational guidance, searchable and
  injected automatically when the matching app is in front.

## Where the settings are

Everything below is reached from **Settings** in the navigation drawer.

| Setting | What it controls |
| --- | --- |
| Personal Info | Name, occupation, background, reply style, location, preferred currency and language |
| Language | App display language, AI reply language, voice language, STT language |
| AI | Hub for the two pages below |
| AI > AI Configuration | LLM provider, API key, Base URL, main and navigator models, API timeout, max context tokens, max tool rounds, max repeated tool calls, max navigation tool calls, max consecutive delegations, cache and debug-screenshot clearing |
| AI > Speech (TTS / STT) | TTS and STT provider, base URL, key and model, voice selection, podcast host voices, auto-read replies aloud, transcription language override |
| Permissions | Every runtime and special-access permission, grouped: Communications, Contacts, Calendar, Media & Storage, Location, Device Control, Notifications, System Access, Health |
| Termux (Linux shell) | Guided setup for the Linux shell used by `run_termux_command` and audio conversion |
| Skills / Plugins | Built-in skills, importing community skills by URL, allowed community skill hosts |
| Proactive Assistance | Master switch for proactive offers, OTP/code detection, auto-copy OTP, and what may be scanned (screen content, notifications, clipboard) |
| Assistive Ball and Wake Word | The floating ball over other apps, hands-free voice calls, the "Hey Gotcha" wake word, when it listens, and detection sensitivity |
| Appearance | Theme |
| Notifications | Reply chime, vibration, and server messages |
| About | Samosa AI, other products, legal, contact |
| About > About Samosa AI | Mission, products, pricing, developers |
| About > Legal | Terms, disclaimer, data retention |

Two more rows sit on the Settings list itself: **Feature Tour**, which replays
the guided setup one step at a time, and **Send Feedback**.

**Connectors are not in Settings.** They live in the navigation drawer next to
Settings, on their own Connectors screen.

## Permissions and prerequisites

Some tools need a grant that cannot be requested from inside a chat turn. When
one is missing the tools are withheld, but the possibility is not: say what is
missing, where to turn it on, and offer to try again afterwards.

| Needs | Unlocks | Granted at |
| --- | --- | --- |
| The accessibility service | Screen reading, tap, long-press, swipe, typing, key presses, global actions, App Navigator | Settings > Permissions (opens Android's accessibility screen) |
| Notification access | Reading and dismissing notifications, media control, now playing | Settings > Permissions |
| Device admin | Lock screen, disable camera, password policy | Settings > Permissions |
| Root | Root commands and secure-settings writes | A rooted device; `check_root` reports the truth |
| Termux | Termux commands, audio conversion, pulling files out of Termux | Install Termux from F-Droid, then Settings > Termux (Linux shell) |
| Health Connect | Health summaries and records | Settings > Permissions > Health |
| Display over other apps | The overlay tools and the Assistive Ball | Settings > Permissions |

Runtime permissions (phone, SMS, contacts, calendar, camera, microphone,
location, storage) show the normal Android dialog when toggled on in Settings >
Permissions. Special-access permissions open a system settings screen for a
one-time setup instead.

## Safety model

Tools are organised in capability tiers, permissions are asked for up front, and
sensitive actions pass a confirmation gate. Shell commands run against a
deny-list, actions are recorded in an append-only audit log, and settings
screens that could be changed silently are marked confirm-first — the agent asks
before opening them, because screen text, notifications and email all reach the
model's context and could carry an injected instruction.

## Common questions

**"Set up a model."** Settings > AI > AI Configuration: API key, Base URL
(including `/v1/`), and model name. Any OpenAI-compatible `/chat/completions`
endpoint with tool calling works, or sign in to Samosa AI for starter credits.

**"Enable the floating ball / wake word."** Settings > Assistive Ball and Wake
Word. The ball needs the display-over-other-apps permission; the wake word
listens from inside the ball's service, and "When to listen" controls whether
that happens with the screen on, off, or always.

**"Get Termux working."** Install Termux from F-Droid, then Settings > Termux
(Linux shell) for the guided setup. Each command runs in a separate shell, so
`cd` and `export` do not carry over between calls.

**"Read replies aloud."** Settings > AI > Speech (TTS / STT) > Auto-read replies
aloud.

**"Stop Gotcha scanning my screen."** Settings > Proactive Assistance, which has
a master switch plus individual toggles for screen content, notifications and
clipboard.

**"Why can't you do that?"** Check the permission table above — the missing
piece is almost always a capability that has not been granted yet, or Monitor
mode being on when the request needs Operator.

## More

Full documentation is online: getting started, copilot modes, the tool catalog,
architecture, safety and permissions, and the FAQ.

- https://samosa-ai.com/gotcha/docs
- https://samosa-ai.com/gotcha/docs/tool-catalog
- https://samosa-ai.com/gotcha/docs/safety-permissions

For the company behind Gotcha, its other products, pricing, contact details and
the terms and privacy summary, call `about_samosa_ai` instead. The authoritative
legal text is bundled in the app under Settings > About > Legal.
