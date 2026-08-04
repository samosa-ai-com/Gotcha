# Wake Word Handoff

## Goal
Add an optional wake word ("Gotcha") to the existing Assistive Ball flow using
Vosk instead of Porcupine, while reusing the current `CallSessionController`
voice-call path.

## Current status

### Implemented
- Added Vosk Android dependency in `app/build.gradle.kts`.
- Added `Settings.wakeWordEnabled` and `Settings.wakeWordSensitivity`, with
  persistence in `SettingsRepository`.
- Added `service/WakeWordDetector.kt`:
  - owns `AudioRecord` only while running;
  - copies a Vosk model from `assets/` into cache;
  - runs Vosk recognition on 16 kHz mono PCM;
  - stops itself and invokes the callback on the main thread when "gotcha" is
    recognized;
  - returns `false` instead of crashing when permission/model/audio init fails.
- Wired the detector into `service/AssistiveBallService.kt`:
  - starts only when Assistive Ball + wake word are enabled, mic permission is
    granted, and the call is idle;
  - stops whenever any call state becomes active;
  - stops in `stopBall()` and `onDestroy()`;
  - on detection, gives vibration feedback and starts the existing call flow
    via `callController.startWakeWordCall()`.
- Added `CallSessionController.startWakeWordCall()` and `autoEndOnReply` so a
  wake-word-triggered call ends after the spoken reply; ordinary calls keep the
  existing stay-open behavior.
- Added `tools/WakeWordBootReceiver.kt` and manifest registration to restart
  the ball service after reboot when Assistive Ball + wake word are enabled.
- Added a wake-word toggle to `ui/AssistiveBallScreen.kt` and updated
  `ui/SettingsScreen.kt` to pass `load`/`onSave`.
- Added `docs/wake-word.md` with the Vosk model asset contract.

### Important limitation
No Vosk model binary has been added to the repo. The implementation expects
the model directory at:
`app/src/main/assets/vosk-model-small-en-us-0.15`
Without that asset, `WakeWordDetector.start()` safely returns `false` and the
wake word simply stays non-functional.

## Files changed
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/gotcha/data/SettingsRepository.kt`
- `app/src/main/java/com/gotcha/service/AssistiveBallService.kt`
- `app/src/main/java/com/gotcha/service/CallSessionController.kt`
- `app/src/main/java/com/gotcha/service/WakeWordDetector.kt` (new)
- `app/src/main/java/com/gotcha/tools/WakeWordBootReceiver.kt` (new)
- `app/src/main/java/com/gotcha/ui/AssistiveBallScreen.kt`
- `app/src/main/java/com/gotcha/ui/SettingsScreen.kt`
- `docs/wake-word.md` (new)

## Verification state
- `git diff --stat` confirmed the patch shape.
- A first Gradle compile attempt was started:
  `./gradlew.bat :app:compileDebugKotlin`
  but it exceeded the 120s shell timeout in this environment, so compilation
  has **not** yet been verified to completion.

## Next steps for the next agent
1. Run `./gradlew.bat :app:compileDebugKotlin` and fix any compile issues.
2. Add the actual Vosk small English model under
   `app/src/main/assets/vosk-model-small-en-us-0.15/`, then run assemble/build.
3. Add or update unit tests:
   - settings persistence for `wakeWordEnabled` / `wakeWordSensitivity`;
   - `CallSessionController` wake-word auto-end behavior;
   - ideally a fakeable detector wrapper or gate test so service logic is
     testable without native Vosk.
4. Consider adding a runtime restart path when the wake-word setting changes:
   currently a running service does not re-read settings until call state goes
   back to idle or the service is restarted.
5. Decide whether to use `wakeWordSensitivity` (currently persisted but unused).
6. Add battery-optimization education/request flow if desired; the plan called
   for it, but no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` flow has been added.
7. Update `app/src/main/assets/legal/privacy-data-retention.md` and/or
   `disclaimer.md` to mention the always-on wake-word listener explicitly.

## Risks / notes
- Vosk is Apache-2.0 and avoids Porcupine's commercial APK licensing concern.
- The detector is intentionally conservative: failure is silent and non-fatal.
- Mic contention is handled by the call-state idle gate, but it has not been
  runtime-tested on a device.
- `WakeWordDetector` uses full Vosk recognition and phrase filtering rather
  than a dedicated grammar-limited KWS configuration; recognition quality and
  false positives need device testing.
