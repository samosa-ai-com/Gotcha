<!--
  GENERATED FILE — DO NOT EDIT BY HAND.
  Source of truth: app/src/test/resources/feature-test-coverage.json
  Regenerate with: ./gradlew :app:testDebugUnitTest -PupdateCoverageDocs=true
  Guarded by FeatureCoverageManifestTest, which fails CI on drift.
-->

# Feature Test Coverage

Every tool the assistant can call is listed below, together with how it is verified. Tools are the app's feature surface, so this table answers "which features are tested, and which are not?".

## Summary

| Tier | Tools | What it means |
|---|---:|---|
| `UNIT` | 19 | Plain JVM unit test — no Android framework needed. |
| `ROBOLECTRIC` | 0 | JVM test against Robolectric's Android framework, often across several API levels. |
| `INSTRUMENTED` | 0 | Runs on a real device or emulator (`app/src/androidTest`). |
| `MANUAL_ONLY` | 80 | No automated test — verified by hand, see the checklist below. |
| **Total** | **99** | |

**19 of 99** tools are covered by an automated test; the remaining 80 are manual-QA-only with a recorded reason.

## Foreground tools (act on the screen)

| Tool | Tier | Tests / reason |
|---|---|---|
| `compose_email` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `dial_number` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `global_action` | `MANUAL_ONLY` | Depends on a live GotchaAccessibilityService bound to a real foreground app; the service cannot reliably self-bind under instrumentation (see AccessibilityServiceTest's @Ignore writeup). |
| `input_text` | `MANUAL_ONLY` | Depends on a live GotchaAccessibilityService bound to a real foreground app; the service cannot reliably self-bind under instrumentation (see AccessibilityServiceTest's @Ignore writeup). |
| `long_press` | `MANUAL_ONLY` | Depends on a live GotchaAccessibilityService bound to a real foreground app; the service cannot reliably self-bind under instrumentation (see AccessibilityServiceTest's @Ignore writeup). |
| `long_press_index` | `MANUAL_ONLY` | Depends on a live GotchaAccessibilityService bound to a real foreground app; the service cannot reliably self-bind under instrumentation (see AccessibilityServiceTest's @Ignore writeup). |
| `navigate_app` | `MANUAL_ONLY` | Nested navigator agent loop over a live accessibility service and a live LLM connection. |
| `open_app` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `press_key` | `MANUAL_ONLY` | Depends on a live GotchaAccessibilityService bound to a real foreground app; the service cannot reliably self-bind under instrumentation (see AccessibilityServiceTest's @Ignore writeup). |
| `read_screen` | `MANUAL_ONLY` | Depends on a live GotchaAccessibilityService bound to a real foreground app; the service cannot reliably self-bind under instrumentation (see AccessibilityServiceTest's @Ignore writeup). |
| `read_screen_raw` | `MANUAL_ONLY` | Depends on a live GotchaAccessibilityService bound to a real foreground app; the service cannot reliably self-bind under instrumentation (see AccessibilityServiceTest's @Ignore writeup). |
| `show_alarms` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `swipe` | `MANUAL_ONLY` | Depends on a live GotchaAccessibilityService bound to a real foreground app; the service cannot reliably self-bind under instrumentation (see AccessibilityServiceTest's @Ignore writeup). |
| `tap` | `MANUAL_ONLY` | Depends on a live GotchaAccessibilityService bound to a real foreground app; the service cannot reliably self-bind under instrumentation (see AccessibilityServiceTest's @Ignore writeup). |
| `tap_index` | `MANUAL_ONLY` | Depends on a live GotchaAccessibilityService bound to a real foreground app; the service cannot reliably self-bind under instrumentation (see AccessibilityServiceTest's @Ignore writeup). |
| `task` | `MANUAL_ONLY` | Delegates to a nested agent loop that needs a live LLM connection. |

## Background tools (change device or account state)

| Tool | Tier | Tests / reason |
|---|---|---|
| `add_contact` | `MANUAL_ONLY` | R |
| `call_number` | `MANUAL_ONLY` | Needs real device hardware; the emulator has no faithful stand-in for this sensor/radio. |
| `complete_task` | `MANUAL_ONLY` | Routed to a live Microsoft Graph account; only the underlying GraphApi has JVM coverage today. |
| `create_calendar_event` | `UNIT` | `CalendarToolsTest` — connector path only; the on-device CalendarProvider path is manual |
| `create_task` | `MANUAL_ONLY` | Routed to a live Microsoft Graph account; only the underlying GraphApi has JVM coverage today. |
| `delete_alarm` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `delete_calendar_event` | `UNIT` | `CalendarToolsTest` — connector path only; destructive, so also gated by the confirmation flow |
| `delete_timer` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `disable_camera` | `MANUAL_ONLY` | Needs an active Device Admin registration, which cannot be granted non-interactively. |
| `dismiss_notifications` | `MANUAL_ONLY` | Needs NotificationListenerService bound with the user's explicit grant; not bindable from a test host. |
| `dismiss_timer` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `edit` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `edit_alarm` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `edit_calendar_event` | `UNIT` | `CalendarToolsTest` — connector path only; the on-device CalendarProvider path is manual |
| `hide_overlay` | `MANUAL_ONLY` | Draws a real SYSTEM_ALERT_WINDOW; needs the 'Display over other apps' grant and a visible screen to verify. |
| `lock_screen` | `MANUAL_ONLY` | Needs an active Device Admin registration, which cannot be granted non-interactively. |
| `mark_email_read` | `UNIT` | `EmailToolsTest` — read/unread toggle routed to the right backend |
| `media_control` | `UNIT` | `MediaSelectionTest` — action parsing and active-session selection |
| `notion_append_to_page` | `UNIT` | `NotionToolsTest` — block append request shape |
| `notion_create_page` | `UNIT` | `NotionToolsTest` — parent/title handling and request shape |
| `pause_audio_recording` | `MANUAL_ONLY` | Needs real device hardware; the emulator has no faithful stand-in for this sensor/radio. |
| `resume_audio_recording` | `MANUAL_ONLY` | Needs real device hardware; the emulator has no faithful stand-in for this sensor/radio. |
| `run_command` | `UNIT` | `TerminalToolTest` — argument splitting, timeout, exit codes, output truncation |
| `run_root_command` | `MANUAL_ONLY` | Requires a rooted device and an interactive superuser grant. |
| `send_email` | `UNIT` | `EmailToolsTest`, `MimeMessageBuilderTest` — confirmation flow, recipient handling, MIME construction with attachments |
| `send_sms` | `MANUAL_ONLY` | Needs real device hardware; the emulator has no faithful stand-in for this sensor/radio. |
| `set_alarm` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `set_brightness` | `MANUAL_ONLY` | C |
| `set_clipboard` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `set_dnd` | `MANUAL_ONLY` | C |
| `set_password_policy` | `MANUAL_ONLY` | Needs an active Device Admin registration, which cannot be granted non-interactively. |
| `set_ringer_mode` | `MANUAL_ONLY` | C |
| `set_timer` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `set_volume` | `MANUAL_ONLY` | C |
| `set_wallpaper` | `MANUAL_ONLY` | C |
| `show_overlay` | `MANUAL_ONLY` | Draws a real SYSTEM_ALERT_WINDOW; needs the 'Display over other apps' grant and a visible screen to verify. |
| `snooze_alarm` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `start_audio_recording` | `MANUAL_ONLY` | Needs real device hardware; the emulator has no faithful stand-in for this sensor/radio. |
| `stop_audio_recording` | `MANUAL_ONLY` | Needs real device hardware; the emulator has no faithful stand-in for this sensor/radio. |
| `take_photo` | `MANUAL_ONLY` | Needs a real camera; CameraX has no usable emulator fake for the capture callback. |
| `toggle_torch` | `MANUAL_ONLY` | Needs real device hardware; the emulator has no faithful stand-in for this sensor/radio. |
| `toggle_wifi` | `MANUAL_ONLY` | C |
| `uninstall_app` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `vibrate` | `MANUAL_ONLY` | Needs real device hardware; the emulator has no faithful stand-in for this sensor/radio. |
| `write_file` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `write_secure_settings` | `MANUAL_ONLY` | Requires WRITE_SECURE_SETTINGS, granted only via adb — and it mutates global device state. |

## Info tools (read-only)

| Tool | Tier | Tests / reason |
|---|---|---|
| `ask_final_answer` | `MANUAL_ONLY` | Only reachable from inside a sub-agent loop with a live LLM connection. |
| `check_availability` | `UNIT` | `CalendarWindowTest` — free/busy window arithmetic |
| `check_root` | `MANUAL_ONLY` | Probes for a real `su` binary; the result is meaningless on a non-rooted test device. |
| `find_contact` | `MANUAL_ONLY` | R |
| `get_app_usage` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `get_audio_recording_status` | `MANUAL_ONLY` | Needs real device hardware; the emulator has no faithful stand-in for this sensor/radio. |
| `get_battery_info` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `get_clipboard` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `get_data_usage` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `get_health_records` | `UNIT` | `HealthFormatTest`, `HealthPermissionScopeTest` — record-type formatting and per-type permission scoping |
| `get_health_summary` | `UNIT` | `HealthFormatTest`, `HealthPermissionScopeTest` — summary formatting and the permission scope requested |
| `get_location` | `MANUAL_ONLY` | Needs real device hardware; the emulator has no faithful stand-in for this sensor/radio. |
| `get_now_playing` | `UNIT` | `MediaSelectionTest` — active-session selection across paused/playing/stopped sessions |
| `get_storage_info` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `get_volume` | `MANUAL_ONLY` | C |
| `glob` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `grep` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `list_alarms` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `list_calendar_events` | `UNIT` | `CalendarToolsTest`, `CalendarWindowTest` — connector path (Google/Microsoft) only; the on-device CalendarProvider path is manual |
| `list_emails` | `UNIT` | `EmailToolsTest` — backend routing, unread filter, result limits |
| `list_files` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `list_installed_apps` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `list_tasks` | `MANUAL_ONLY` | Routed to a live Microsoft Graph account; only the underlying GraphApi has JVM coverage today. |
| `list_timers` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `notion_read_page` | `UNIT` | `NotionToolsTest`, `NotionBlockRendererTest` — page fetch plus block-to-markdown rendering |
| `notion_search` | `UNIT` | `NotionToolsTest` — query routing and result formatting |
| `question` | `MANUAL_ONLY` | Pure UI round-trip: the tool blocks until the user answers a prompt rendered in the chat surface. |
| `read_call_log` | `MANUAL_ONLY` | R |
| `read_email` | `UNIT` | `EmailToolsTest`, `MailBodyExtractorTest` — id routing plus multipart/HTML body extraction |
| `read_file` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `read_notifications` | `MANUAL_ONLY` | Needs NotificationListenerService bound with the user's explicit grant; not bindable from a test host. |
| `read_recent_sms` | `MANUAL_ONLY` | R |
| `search_skills` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `sleep` | `MANUAL_ONLY` | Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier. |
| `todowrite` | `MANUAL_ONLY` | Pure UI round-trip: writes the visible todo list in the chat surface. |
| `webfetch` | `UNIT` | `WebFetchToolTest` — HTML-to-text extraction, truncation, error handling |
| `websearch` | `MANUAL_ONLY` | Performs real network I/O against a search provider; exercising it in CI would make the suite non-hermetic. |

## Manual QA checklist

Run through this before a release. Each item is a tool with no automated coverage, so this list is the pre-release manual test script.

- [ ] **`add_contact`** — Ask the agent to add a contact; confirm it appears in the Contacts app with the right name and number.
  <br>_Why not automated:_ R
- [ ] **`ask_final_answer`** — Run a navigator/sub-agent task; confirm the final answer is returned to the parent agent rather than to the chat.
  <br>_Why not automated:_ Only reachable from inside a sub-agent loop with a live LLM connection.
- [ ] **`call_number`** — Ask the agent to call a number you control; confirm the call is actually placed (and hang up).
  <br>_Why not automated:_ Needs real device hardware; the emulator has no faithful stand-in for this sensor/radio.
- [ ] **`check_root`** — On a rooted device ask 'am I rooted?'; confirm it says yes. On a stock device confirm it says no.
  <br>_Why not automated:_ Probes for a real `su` binary; the result is meaningless on a non-rooted test device.
- [ ] **`complete_task`** — With Microsoft connected, ask the agent to complete a task; confirm To Do shows it as done.
  <br>_Why not automated:_ Routed to a live Microsoft Graph account; only the underlying GraphApi has JVM coverage today.
- [ ] **`compose_email`** — Ask the agent to open a draft email to someone; confirm the mail app opens pre-filled and nothing is sent.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`create_task`** — With Microsoft connected, ask the agent to add a task; confirm it appears in To Do.
  <br>_Why not automated:_ Routed to a live Microsoft Graph account; only the underlying GraphApi has JVM coverage today.
- [ ] **`delete_alarm`** — Ask the agent to delete an alarm; confirm the confirmation prompt appears and the alarm is gone afterwards.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`delete_timer`** — Ask the agent to cancel a running timer; confirm the confirmation prompt appears and the timer stops.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`dial_number`** — Ask the agent to 'open the dialer with +49123456789'; confirm the dialer opens pre-filled and does NOT place the call.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`disable_camera`** — As device admin, ask the agent to disable the camera; confirm the Camera app refuses to open, then re-enable.
  <br>_Why not automated:_ Needs an active Device Admin registration, which cannot be granted non-interactively.
- [ ] **`dismiss_notifications`** — With notifications present, ask the agent to clear them; confirm the shade empties.
  <br>_Why not automated:_ Needs NotificationListenerService bound with the user's explicit grant; not bindable from a test host.
- [ ] **`dismiss_timer`** — With a timer ringing, ask the agent to dismiss it; confirm it stops.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`edit`** — Ask the agent to change one line in a text file; confirm only that line changed and a non-unique oldString is rejected.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`edit_alarm`** — Ask the agent to move an existing alarm 30 minutes later; confirm the Clock app shows the new time.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`find_contact`** — Ask 'what's <a saved contact>'s number?'; confirm it matches the Contacts app.
  <br>_Why not automated:_ R
- [ ] **`get_app_usage`** — Grant Usage Access, then ask 'which app did I use most today?'; compare with Settings → Digital Wellbeing.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`get_audio_recording_status`** — While recording, ask 'am I still recording?'; confirm the elapsed time is reported.
  <br>_Why not automated:_ Needs real device hardware; the emulator has no faithful stand-in for this sensor/radio.
- [ ] **`get_battery_info`** — Ask 'what is my battery level?'; confirm the percentage and charging state match the status bar.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`get_clipboard`** — Copy some text in another app, then ask 'what's on my clipboard?'; confirm it matches (Android 10+ needs the accessibility fallback).
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`get_data_usage`** — Grant Usage Access, then ask 'how much data has Chrome used?'; compare with Settings → Network → Data usage.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`get_location`** — Outdoors or with a mock location set, ask 'where am I?'; confirm the coordinates/address are plausible.
  <br>_Why not automated:_ Needs real device hardware; the emulator has no faithful stand-in for this sensor/radio.
- [ ] **`get_storage_info`** — Ask 'how much storage do I have left?'; confirm the reported free/total roughly matches Settings → Storage.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`get_volume`** — Ask 'how loud is my media volume?'; confirm the answer matches the volume slider.
  <br>_Why not automated:_ C
- [ ] **`glob`** — Ask the agent to find all *.txt files under a directory; confirm the result matches `find`.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`global_action`** — Ask the agent to open the notification shade, then Recents; confirm both happen.
  <br>_Why not automated:_ Depends on a live GotchaAccessibilityService bound to a real foreground app; the service cannot reliably self-bind under instrumentation (see AccessibilityServiceTest's @Ignore writeup).
- [ ] **`grep`** — Ask the agent to search a directory for a phrase; confirm the matching files/lines are right.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`hide_overlay`** — With an overlay showing, ask the agent to hide it; confirm the card disappears.
  <br>_Why not automated:_ Draws a real SYSTEM_ALERT_WINDOW; needs the 'Display over other apps' grant and a visible screen to verify.
- [ ] **`input_text`** — Focus a text field and ask the agent to type a phrase; confirm the text lands in the field.
  <br>_Why not automated:_ Depends on a live GotchaAccessibilityService bound to a real foreground app; the service cannot reliably self-bind under instrumentation (see AccessibilityServiceTest's @Ignore writeup).
- [ ] **`list_alarms`** — With at least one alarm set, ask 'what alarms do I have?'; confirm the list matches the Clock app.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`list_files`** — Ask the agent to list files in the working directory; confirm the listing matches a file manager.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`list_installed_apps`** — Ask 'what apps do I have installed?'; spot-check a few entries against the launcher.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`list_tasks`** — With Microsoft connected, ask 'what tasks do I have?'; confirm the list matches To Do.
  <br>_Why not automated:_ Routed to a live Microsoft Graph account; only the underlying GraphApi has JVM coverage today.
- [ ] **`list_timers`** — With a timer running, ask 'what timers are running?'; confirm the remaining time is right.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`lock_screen`** — Enable Gotcha as a device admin, ask the agent to lock the screen; confirm the device locks.
  <br>_Why not automated:_ Needs an active Device Admin registration, which cannot be granted non-interactively.
- [ ] **`long_press`** — Ask the agent to long-press an icon; confirm the context menu opens.
  <br>_Why not automated:_ Depends on a live GotchaAccessibilityService bound to a real foreground app; the service cannot reliably self-bind under instrumentation (see AccessibilityServiceTest's @Ignore writeup).
- [ ] **`long_press_index`** — Run read_screen_raw, then long-press element N; confirm the right element gets the long press.
  <br>_Why not automated:_ Depends on a live GotchaAccessibilityService bound to a real foreground app; the service cannot reliably self-bind under instrumentation (see AccessibilityServiceTest's @Ignore writeup).
- [ ] **`navigate_app`** — Ask the agent to complete a multi-screen task in another app; confirm it navigates there and reports the outcome.
  <br>_Why not automated:_ Nested navigator agent loop over a live accessibility service and a live LLM connection.
- [ ] **`open_app`** — Ask 'open Settings'; confirm the Settings app comes to the foreground.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`pause_audio_recording`** — Pause an in-progress recording; confirm the elapsed time stops advancing.
  <br>_Why not automated:_ Needs real device hardware; the emulator has no faithful stand-in for this sensor/radio.
- [ ] **`press_key`** — Ask the agent to press Back; confirm the app navigates back.
  <br>_Why not automated:_ Depends on a live GotchaAccessibilityService bound to a real foreground app; the service cannot reliably self-bind under instrumentation (see AccessibilityServiceTest's @Ignore writeup).
- [ ] **`question`** — Ask something ambiguous so the agent asks a clarifying question; answer it and confirm the agent continues with your answer.
  <br>_Why not automated:_ Pure UI round-trip: the tool blocks until the user answers a prompt rendered in the chat surface.
- [ ] **`read_call_log`** — Make a call, then ask 'who did I last call?'; confirm the entry matches the Phone app's log.
  <br>_Why not automated:_ R
- [ ] **`read_file`** — Write a short text file, then ask the agent to read it back; confirm the contents match, including with an offset/limit.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`read_notifications`** — Grant notification access, generate a notification, then ask 'what notifications do I have?'; confirm it is listed.
  <br>_Why not automated:_ Needs NotificationListenerService bound with the user's explicit grant; not bindable from a test host.
- [ ] **`read_recent_sms`** — Ask 'what was my last text?'; confirm it matches the SMS app.
  <br>_Why not automated:_ R
- [ ] **`read_screen`** — With the accessibility service on, open any app and ask 'what's on my screen?'; confirm the summary matches.
  <br>_Why not automated:_ Depends on a live GotchaAccessibilityService bound to a real foreground app; the service cannot reliably self-bind under instrumentation (see AccessibilityServiceTest's @Ignore writeup).
- [ ] **`read_screen_raw`** — Ask for the raw UI hierarchy; confirm element bounds/indices line up with what's on screen.
  <br>_Why not automated:_ Depends on a live GotchaAccessibilityService bound to a real foreground app; the service cannot reliably self-bind under instrumentation (see AccessibilityServiceTest's @Ignore writeup).
- [ ] **`resume_audio_recording`** — Resume a paused recording; confirm the elapsed time advances again and the final file contains both halves.
  <br>_Why not automated:_ Needs real device hardware; the emulator has no faithful stand-in for this sensor/radio.
- [ ] **`run_root_command`** — On a rooted device, ask the agent to run `id`; confirm the superuser prompt appears and the output shows uid=0.
  <br>_Why not automated:_ Requires a rooted device and an interactive superuser grant.
- [ ] **`search_skills`** — Ask about a task with a matching skill; confirm the skill's guidance shows up in the agent's plan.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`send_sms`** — Ask the agent to text a number you control; confirm the message arrives and appears in the SMS app's sent list.
  <br>_Why not automated:_ Needs real device hardware; the emulator has no faithful stand-in for this sensor/radio.
- [ ] **`set_alarm`** — Ask the agent to set an alarm for 5 minutes' time; confirm it appears in the Clock app and fires.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`set_brightness`** — Ask the agent to set brightness to 20%, then 80%; confirm the screen visibly changes and Settings agrees.
  <br>_Why not automated:_ C
- [ ] **`set_clipboard`** — Ask the agent to copy a phrase, then paste it manually somewhere; confirm the phrase pastes.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`set_dnd`** — Ask the agent to turn Do Not Disturb on; confirm the DND icon appears (grant DND access first if prompted).
  <br>_Why not automated:_ C
- [ ] **`set_password_policy`** — As device admin, ask the agent to require a 6-character password; confirm Settings enforces the new minimum.
  <br>_Why not automated:_ Needs an active Device Admin registration, which cannot be granted non-interactively.
- [ ] **`set_ringer_mode`** — Ask the agent to switch to vibrate, then back to normal; confirm the status-bar icon changes.
  <br>_Why not automated:_ C
- [ ] **`set_timer`** — Ask the agent to set a 1-minute timer; confirm it appears in the Clock app and fires.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`set_volume`** — Ask the agent to set media volume to 50%; confirm the volume HUD and Settings agree.
  <br>_Why not automated:_ C
- [ ] **`set_wallpaper`** — Ask the agent to set the wallpaper from a photo; confirm the home screen background changes.
  <br>_Why not automated:_ C
- [ ] **`show_alarms`** — Ask the agent to show your alarms; confirm the Clock app opens on the alarms tab.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`show_overlay`** — Ask the agent to show an overlay message; confirm the floating card appears above other apps.
  <br>_Why not automated:_ Draws a real SYSTEM_ALERT_WINDOW; needs the 'Display over other apps' grant and a visible screen to verify.
- [ ] **`sleep`** — Ask the agent to wait 5 seconds mid-task; confirm the pause happens and the run continues.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`snooze_alarm`** — With an alarm ringing, ask the agent to snooze it; confirm the alarm stops and re-arms.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`start_audio_recording`** — Ask the agent to start recording; confirm the mic indicator appears and a file is created under Recordings/.
  <br>_Why not automated:_ Needs real device hardware; the emulator has no faithful stand-in for this sensor/radio.
- [ ] **`stop_audio_recording`** — Stop an in-progress recording; confirm the file is finalised and playable in the Recorder/Files app.
  <br>_Why not automated:_ Needs real device hardware; the emulator has no faithful stand-in for this sensor/radio.
- [ ] **`swipe`** — Ask the agent to scroll down a list; confirm the list scrolls.
  <br>_Why not automated:_ Depends on a live GotchaAccessibilityService bound to a real foreground app; the service cannot reliably self-bind under instrumentation (see AccessibilityServiceTest's @Ignore writeup).
- [ ] **`take_photo`** — Ask the agent to take a photo; confirm the shutter fires and the file lands in Pictures/.
  <br>_Why not automated:_ Needs a real camera; CameraX has no usable emulator fake for the capture callback.
- [ ] **`tap`** — Ask the agent to tap a named on-screen button; confirm the right element is activated.
  <br>_Why not automated:_ Depends on a live GotchaAccessibilityService bound to a real foreground app; the service cannot reliably self-bind under instrumentation (see AccessibilityServiceTest's @Ignore writeup).
- [ ] **`tap_index`** — Run read_screen_raw, then ask the agent to tap element N; confirm the numbered element is the one activated.
  <br>_Why not automated:_ Depends on a live GotchaAccessibilityService bound to a real foreground app; the service cannot reliably self-bind under instrumentation (see AccessibilityServiceTest's @Ignore writeup).
- [ ] **`task`** — Give the agent a multi-step request; confirm a sub-agent is spawned and its result is folded back into the answer.
  <br>_Why not automated:_ Delegates to a nested agent loop that needs a live LLM connection.
- [ ] **`todowrite`** — Give the agent a multi-step request; confirm a todo list is rendered and items tick off as it progresses.
  <br>_Why not automated:_ Pure UI round-trip: writes the visible todo list in the chat surface.
- [ ] **`toggle_torch`** — Ask the agent to turn the torch on, then off; confirm the flash physically lights up.
  <br>_Why not automated:_ Needs real device hardware; the emulator has no faithful stand-in for this sensor/radio.
- [ ] **`toggle_wifi`** — Ask the agent to turn Wi-Fi off; confirm the Wi-Fi settings panel opens (Android 10+ forbids programmatic toggling).
  <br>_Why not automated:_ C
- [ ] **`uninstall_app`** — Install a throwaway app, ask the agent to uninstall it; confirm the confirmation prompt appears and the app is removed.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`vibrate`** — Ask the agent to vibrate the phone; confirm you feel it.
  <br>_Why not automated:_ Needs real device hardware; the emulator has no faithful stand-in for this sensor/radio.
- [ ] **`websearch`** — Ask a question that needs current information; confirm the agent cites plausible, recent results.
  <br>_Why not automated:_ Performs real network I/O against a search provider; exercising it in CI would make the suite non-hermetic.
- [ ] **`write_file`** — Ask the agent to write a file to the working directory; confirm it appears on disk with the requested contents.
  <br>_Why not automated:_ Needs an Android Context and a system service with real device state; no JVM-tier coverage yet — scheduled for the Robolectric tier.
- [ ] **`write_secure_settings`** — After `adb shell pm grant com.gotcha android.permission.WRITE_SECURE_SETTINGS`, ask the agent to change a secure setting; confirm with `adb shell settings get`.
  <br>_Why not automated:_ Requires WRITE_SECURE_SETTINGS, granted only via adb — and it mutates global device state.
