# Clock tools

`set_alarm`, `set_timer`, `list_alarms`, `list_timers`, `show_alarms`,
`snooze_alarm`, `dismiss_timer`, `delete_alarm`, and `delete_timer` drive the
device's clock app through `AlarmClock` intents. There is no API to read or
modify another app's alarms, so behaviour depends entirely on which clock app
is installed and how it handles the standard extras.

## Manual clock intent checklist

`set_timer(system=true)`, `show_alarms`, `snooze_alarm`, and `dismiss_timer`
send intents to whatever clock app is installed; behavior (especially
`EXTRA_SKIP_UI` and dismiss/snooze support) varies by OEM. Verify manually on
Google Clock at minimum:

- [ ] `set_alarm` / `set_timer` land in Google Clock's lists.
- [ ] `show_alarms` opens the alarms tab.
- [ ] While an alarm is ringing, `snooze_alarm` snoozes it.
- [ ] While a system timer (`set_timer(system=true)`) is ringing, `dismiss_timer`
      stops it.
