# Wake Word Handoff

## Goal
Add an optional wake word ("Hey Gotcha") to the existing Assistive Ball flow
using [openWakeWord](https://github.com/dscripka/openWakeWord) ONNX models,
while reusing the current `CallSessionController` voice-call path.

## Current status

### In production (merged)
- **Engine:** direct Microsoft ONNX Runtime (`com.microsoft.onnxruntime:onnxruntime-android:1.26.0`).
  The detector runs the melspectrogram → speech-embedding → classifier pipeline
  directly, no third-party wrapper.
- **Models:** three Apache-2.0 ONNX models in
  `app/src/main/assets/openwakeword/` (see `docs/wake-word.md`). The
  classifier (`hey_gotcha.onnx`) was trained by the Gotcha project on
  synthetic Piper-TTS positives and OpenWakeWord's standard negatives.
- **Settings:** `Settings.wakeWordEnabled` and `Settings.wakeWordSensitivity`,
  persisted by `SettingsRepository`.
- **Detection integration in `AssistiveBallService`:**
  - listens only while the call controller is idle and both `wakeWordEnabled`
    and `assistiveBallEnabled` are on;
  - stops for active call states, `stopBall()`, and `onDestroy()`;
  - on detection, vibrates and starts the existing call flow through
    `callController.startWakeWordCall()`;
  - pauses while `ttsEngine.isSpeaking` is true so a screen read-aloud that
    contains "gotcha" cannot self-trigger a call;
  - live settings: reacts to `wakeWordSensitivity` changes via
    `settingsChangeNotifier` while idle;
  - surfaces startup errors through the overlay.
- **Auto-end after the task:** `CallSessionController.startWakeWordCall()`
  plus the `autoEndOnReply` flag, so a wake-word-triggered call ends after
  the spoken reply while ordinary calls keep the existing stay-open behavior.
- **Hands-free wake-word calls:** after the short wake-word acknowledgment
  ("Yes?") the microphone opens itself; a voice-activity detector
  (`audio/SilenceDetector.kt`) stops it after ~3 s of silence (API STT, via a
  new `AudioRecord` + WAV path in `SttEngine.listenForUtterance`; ~2 s
  platform end-of-speech hint for the Android provider). The transcript then
  runs through the exact same `finishTurn` path as push-to-talk, so the two
  flows behave identically downstream. The mic re-opens for agent questions,
  and two silent listens end the call. Normal long-press calls are untouched
  (`handsFree` is only set by `startWakeWordCall`; `startMic`/`stopMic` are
  no-ops while it is set).
- **Black/blank screen skip:** `ScreenSnapshot.isMostlyBlack()` drops
  essentially-black screenshots (screen off/blank, common during wake-word
  use) from every voice-call turn and substitutes a short explanatory note,
  so the agent never reasons over a black frame.
- **Boot restart:** `WakeWordBootReceiver` + manifest registration; only
  fires when both `assistiveBallEnabled` and `wakeWordEnabled` are on.
- **UI:** `AssistiveBallScreen` exposes a "Wake word: Hey Gotcha" toggle
  and a sensitivity slider, plus a battery-optimization education row.
- **Tests:** `WakeWordMatcherTest` covers sensitivity → threshold mapping
  and patience; `SettingsTest` covers wake-word defaults; the
  `CallSessionControllerTest` extension covers `startWakeWordCall` success
  and failure paths.
- **Legal:** `docs/privacy-data-retention.md` documents the wake-word data
  flow (on-device only); `LEGAL_VERSION` bumped to 2 to force re-acceptance.
- **License notice:** `app/src/main/assets/licenses/openwakeword-notice.txt`
  lists the three bundled models and their Apache-2.0 provenance.

### Architecture pieces intentionally kept engine-agnostic
- `AssistiveBallService` idle-gating, `stopBall()`, `onDestroy()` cleanup.
- `CallSessionController.startWakeWordCall()` + `autoEndOnReply`.
- The `WakeWordBootReceiver` boot path.
- The `Settings` data class and repository.
- The `WakeWordDetector` public surface (`start()`/`stop()`/`isRunning()` +
  `onStarted`/`onDetected`/`onError` callbacks + `sensitivityProvider`).

These are reusable if the underlying engine is swapped again (e.g. to a
custom TFLite classifier).

## Manual device-verification checklist

- [ ] Ball on, microphone granted, wake word on; say "Hey Gotcha" — the ball
      pulses, a short "Yes?" is spoken, **mic opens by itself**, reply spoken,
      call auto-ends.
- [ ] Speak, pause >3 s (API STT) — mic stops on its own and the turn sends.
- [ ] Keep speaking — mic stays open (no early cut-off).
- [ ] Agent asks a question — mic re-opens itself and the spoken answer is
      captured.
- [ ] Say nothing — after two silent listens the call ends gracefully.
- [ ] Wake word call with screen off — a black screenshot is NOT injected;
      the LLM sees the "(screen was blank)" note.
- [ ] Normal long-press call — still push-to-talk, mic/stop buttons work as
      before, no auto-open.
- [ ] Say "Hey Gotcha" during an active call — ignored (idle gate).
- [ ] Reboot — listener resumes via `WakeWordBootReceiver`.
- [ ] TV / music playing near the phone — no false triggers at the balanced
      threshold.
- [ ] Slide sensitivity to "High precision" — stricter triggers, fewer
      false activations.

## Risks / notes

- The custom classifier is Apache-2.0 because training data and architecture
  are all Apache-2.0 (OpenWakeWord code + Google speech-embedding backbone
  + Piper-TTS-generated positives + OpenWakeWord's standard negatives).
- The detector runs three small ONNX models continuously; on a modern
  Android device CPU usage is negligible compared to a full ASR engine.
- Real-device false-accept rates must be validated before shipping the
  always-on listener to users; the `Hey Gotcha` phrase is short and can
  sound similar to casual speech in noisy environments. The sensitivity
  slider and confuser-style pronunciation training give users a way to
  trade off misses vs. false triggers.
- Mic contention with the call STT is handled by the idle-gate, but
  device-level validation is recommended.

## Files in the wake-word effort

- `app/build.gradle.kts` — adds ONNX Runtime dep.
- `app/src/main/AndroidManifest.xml` — wake-word boot receiver,
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission.
- `app/src/main/assets/openwakeword/{hey_gotcha.onnx,melspectrogram.onnx,embedding_model.onnx}`
- `app/src/main/assets/licenses/openwakeword-notice.txt`
- `app/src/main/assets/legal/privacy-data-retention.md` — wake-word section.
- `app/src/main/java/com/gotcha/data/SettingsRepository.kt` — fields, persistence, `LEGAL_VERSION` bump.
- `app/src/main/java/com/gotcha/audio/TtsEngine.kt` — `isSpeaking` tracking.
- `app/src/main/java/com/gotcha/service/WakeWordDetector.kt` — detector.
- `app/src/main/java/com/gotcha/service/OnnxWakeWordPipeline.kt` — ONNX pipeline.
- `app/src/main/java/com/gotcha/service/WakeWordMatcher.kt` — threshold/patience.
- `app/src/main/java/com/gotcha/service/CallSessionController.kt` — `autoEndOnReply`, hands-free auto-listen loop, black-screen skip.
- `app/src/main/java/com/gotcha/service/AssistiveBallService.kt` — idle-gate, TTS guard, live settings.
- `app/src/main/java/com/gotcha/audio/SttEngine.kt` — `listenForUtterance` (VAD hands-free listen + WAV path).
- `app/src/main/java/com/gotcha/audio/SilenceDetector.kt` — noise-floor-adaptive voice-activity detector.
- `app/src/main/java/com/gotcha/audio/AudioApi.kt` — transcribe content-type parameter.
- `app/src/main/java/com/gotcha/agent/ScreenSnapshot.kt` — `isMostlyBlack()`.
- `app/src/main/java/com/gotcha/tools/WakeWordBootReceiver.kt` — boot restart.
- `app/src/main/java/com/gotcha/ui/AssistiveBallScreen.kt` — toggle + slider + battery row.
- `app/src/test/java/com/gotcha/service/WakeWordMatcherTest.kt` — unit tests.
- `app/src/test/java/com/gotcha/data/SettingsTest.kt` — wake-word defaults.
- `app/src/test/java/com/gotcha/service/CallSessionControllerTest.kt` — wake-word start paths.
- `app/src/test/java/com/gotcha/audio/SilenceDetectorTest.kt` — VAD decision tests.
- `app/src/test/java/com/gotcha/agent/ScreenSnapshotTest.kt` — black-frame classification tests.
- `docs/wake-word.md`, `docs/wake-word-handoff.md`, `docs/MODEL_CARD.md`.
