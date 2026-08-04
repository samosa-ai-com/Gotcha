# Wake Word Handoff

## Goal
Add an optional wake word ("Gotcha") to the existing Assistive Ball flow using
Vosk instead of Porcupine, while reusing the current `CallSessionController`
voice-call path.

## Current status

### Already in the current baseline
- Vosk Android dependency in `app/build.gradle.kts`.
- `Settings.wakeWordEnabled` and `Settings.wakeWordSensitivity`, with
  persistence in `SettingsRepository`.
- Initial `WakeWordDetector` integration in `AssistiveBallService`:
  - listens only while the call controller is idle;
  - stops for active call states, `stopBall()`, and `onDestroy()`;
  - on detection, vibrates and starts the existing call flow through
    `callController.startWakeWordCall()`.
- `CallSessionController.startWakeWordCall()` plus `autoEndOnReply`, so a
  wake-word-triggered call ends after the spoken reply while ordinary calls
  keep the existing stay-open behavior.
- `WakeWordBootReceiver` and manifest registration for reboot restart.
- Wake-word toggle in `AssistiveBallScreen`, with `SettingsScreen` passing
  `load`/`onSave`.
- `docs/wake-word.md` setup notes.

### Added in the current uncommitted worktree
- The official `vosk-model-small-en-us-0.15` model is now under
  `app/src/main/assets/vosk-model-small-en-us-0.15/`.
  - Source: `https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip`
  - Upstream catalog lists it as Apache 2.0.
  - Extracted size is about 71 MB; the source zip is about 40 MB.
- New `service/WakeWordMatcher.kt`:
  - exact-token phrase matching;
  - maps sensitivity to 1/2/3 consecutive matching Vosk updates;
  - intended to reduce false triggers from a single noisy partial result.
- `service/WakeWordDetector.kt` is mid-refactor:
  - model extraction/loading moved off the main thread;
  - recognizer now uses the constrained grammar `["gotcha", "[unk]"]`;
  - asset-tree copying was fixed for file leaves;
  - cache extraction is versioned by `BuildConfig.VERSION_CODE`;
  - callbacks were added for started/detected/error;
  - a generation counter was started to avoid stale async initialization after
    rapid stop/start, but that refactor is incomplete.

## Current build state: intentionally paused while red
The earlier synchronous implementation compiled successfully with:

```powershell
./gradlew.bat :app:compileDebugKotlin
```

After the async/sensitivity refactor began, compilation is currently broken.
Known issues:

1. `AssistiveBallService.kt` still constructs `WakeWordDetector` without the
   new required `sensitivityProvider` argument.
2. `WakeWordDetector.start()` calls `initializeAndListen(startGeneration)`, but
   `initializeAndListen()` has not yet been updated to accept a generation
   parameter.
3. The generation guard needs to be carried through initialization, listening,
   and failure reporting so an old coroutine cannot install resources or report
   an error after a newer start/stop cycle.

Do not assume the worktree is green until the next compile succeeds.

## Next implementation steps
1. Finish the `WakeWordDetector` generation refactor:
   - change `initializeAndListen()` to accept `generation: Int`;
   - assign resources only when `running && generation == this.generation`;
   - pass the generation into `listen()`;
   - ignore stale listener failures from an old generation.
2. Update `AssistiveBallService` construction:
   - pass `sensitivityProvider = { settingsRepository.load().wakeWordSensitivity }`;
   - pass `onStarted`, `onDetected`, and `onError` callbacks;
   - surface detector startup failures through the existing overlay once, not
     repeatedly.
3. Add live settings handling in `AssistiveBallService` with
   `settingsChangeNotifier`:
   - track `(wakeWordEnabled, wakeWordSensitivity)`;
   - stop when disabled;
   - restart when sensitivity changes while idle;
   - do not interrupt an active call.
4. Add the sensitivity slider to `AssistiveBallScreen` and persist
   `wakeWordSensitivity`.
5. Decide whether to add the battery-optimization exemption request:
   - manifest permission: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`;
   - contextual button/education from the Assistive Ball or Permissions page.
6. Add/refresh legal copy:
   - explain that wake-word listening uses the microphone while the ball is on
     and idle;
   - state that Vosk wake-word audio is processed on-device;
   - state that only the post-trigger voice command uses the configured STT
     provider;
   - consider bumping `LEGAL_VERSION` because this is a material privacy change.
7. Add an Apache 2.0 license/notice copy for the bundled Vosk model if the
   project intends to commit and redistribute the model asset.
8. Add tests:
   - `WakeWordMatcher` exact-token and sensitivity-streak behavior;
   - settings persistence for `wakeWordEnabled` / `wakeWordSensitivity`;
   - `CallSessionController` wake-word auto-end behavior;
   - optionally boot-receiver gating.
9. Run:
   - `./gradlew.bat :app:compileDebugKotlin`
   - focused unit tests
   - `:app:assembleDebug` to verify the 71 MB model packages correctly
   - `:app:detekt` if time permits.
10. Device-test the full loop:
    - enable ball + microphone + wake word;
    - say "Gotcha";
    - confirm vibration, call start, mic turn, spoken reply, and auto-end;
    - confirm ordinary long-press calls still stay open;
    - test screen off, reboot, OEM battery behavior, and mic contention.

## Files added or changed across the whole effort
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/assets/vosk-model-small-en-us-0.15/**` (new, uncommitted)
- `app/src/main/java/com/gotcha/data/SettingsRepository.kt`
- `app/src/main/java/com/gotcha/service/AssistiveBallService.kt`
- `app/src/main/java/com/gotcha/service/CallSessionController.kt`
- `app/src/main/java/com/gotcha/service/WakeWordDetector.kt`
- `app/src/main/java/com/gotcha/service/WakeWordMatcher.kt` (new, uncommitted)
- `app/src/main/java/com/gotcha/tools/WakeWordBootReceiver.kt`
- `app/src/main/java/com/gotcha/ui/AssistiveBallScreen.kt`
- `app/src/main/java/com/gotcha/ui/SettingsScreen.kt`
- `docs/wake-word.md`
- `docs/wake-word-handoff.md`

## Risks / notes
- Vosk avoids Porcupine's commercial APK licensing issue; the selected model is
  listed upstream as Apache 2.0.
- The model makes the APK substantially larger.
- Vosk is being used with a constrained keyword grammar rather than unrestricted
  transcription, but accuracy and false-positive behavior still require device
  testing.
- The current sensitivity model is app-defined (consecutive recognition hits),
  not a native Vosk confidence threshold.
- Mic contention is handled by the call-state idle gate, but this still needs
  real-device validation.
- The current worktree should be treated as an in-progress checkpoint, not as a
  ready-to-run branch.
