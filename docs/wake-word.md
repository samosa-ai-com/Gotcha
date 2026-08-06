# Wake Word

Gotcha's optional wake word is **"Hey Gotcha"**. It listens locally with the
bundled [OpenWakeWord](https://github.com/dscripka/openWakeWord) ONNX models —
no audio leaves the device while the listener is waiting. Only the existing
voice call sends the spoken command to the configured speech-to-text provider.

## Bundled models

Three Apache-2.0 models live under `app/src/main/assets/openwakeword/`:

| File | Role |
| :--- | :--- |
| `hey_gotcha.onnx` | Custom classifier head trained by the Gotcha project on synthetic Piper-TTS positives and OpenWakeWord's standard negative/background data |
| `embedding_model.onnx` | Google's speech-embedding backbone, as used by OpenWakeWord |
| `melspectrogram.onnx` | OpenWakeWord melspectrogram front-end |

Provenance and license details are in
`app/src/main/assets/licenses/openwakeword-notice.txt` and
[`docs/MODEL_CARD.md`](MODEL_CARD.md).

## Pipeline

Per 80 ms (1280-sample) frame @ 16 kHz mono PCM-16, the
`WakeWordDetector` runs:

1. **Melspectrogram** — the most recent 1760 samples → `melspectrogram.onnx` →
   32-bin mel rows, transformed with `spec / 10 + 2`.
2. **Embedding** — the latest 76 mel rows → `embedding_model.onnx` → one
   96-dim speech embedding per frame.
3. **Classification** — the last 16 embeddings → `hey_gotcha.onnx` → a score
   in [0, 1].

A `WakeWordMatcher` then maps the user-facing sensitivity slider to the
score threshold (`threshold = 0.70 − 0.27 × sensitivity`) and requires two
consecutive qualifying frames before firing. The default sensitivity of 0.75
corresponds to the model card's balanced threshold of 0.50.

## When the listener runs

- **Wake word enabled** AND **Assistive Ball enabled** AND microphone
  permission granted AND the call state is idle.
- An unloadable model or unavailable microphone disables detection without
  crashing the service.
- The detector is paused while the App's own text-to-speech is playing, so
  the App reading "gotcha" aloud does not self-trigger a call.

## Hands-free calls (wake word only)

A wake-word-triggered call is **hands-free**, not push-to-talk:

1. The wake word starts the call and a short acknowledgment ("Yes?") is spoken.
2. The microphone **opens automatically** once that announcement finishes.
3. A voice-activity detector listens for the user's speech. When the user
   stops talking for **~3 seconds** (API STT; the on-device Android recognizer
   uses a ~2 s platform end-of-speech hint), the microphone **stops itself**.
4. From that point the flow is identical to a normal call: the recording is
   transcribed, cleaned, sent to the agent, and the reply is spoken.
5. The call **ends itself** after the reply (the existing wake-word
   auto-end behaviour), so it never stays open waiting for another tap.

If the agent asks a clarifying question mid-call, the microphone re-opens by
itself and the answer is captured the same way. After two silent listen
attempts the call ends gracefully instead of listening forever.

Normal long-press calls are unchanged: they stay push-to-talk, with the same
mic/stop buttons and the same states.

## Black / blank screen handling

Every voice-call turn attaches a screenshot of the current screen. Before it
is injected into the model, the screenshot is checked: a frame that is
essentially solid black (the screen was off or blank — common during
wake-word use) is **not sent**; instead the turn carries a short note
"(The screen was blank or off — no screenshot was sent.)" so the agent does
not reason over a meaningless black frame. Dark apps with visible content
have enough bright pixels/variance to still be sent.

## Battery and reliability

OEM battery managers may kill the always-on listener. The Assistive Ball
settings page offers a one-tap link to Android's battery-optimization
exemption request when the wake word is on.

## Play Store disclosure

The wake-word listener keeps the microphone open while the Assistive Ball is
on, which is material for Play Store policy. The current implementation:

- Adds `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` to the manifest and offers a
  one-tap exemption link in settings — required to receive the Play
  "background audio" carve-out for always-listening apps.
- Documents wake-word data flow in `docs/privacy-data-retention.md`
  (Section 10): audio frames stay on-device; only the post-trigger command
  uses the configured STT provider.

Before publishing, declare the wake-word mic use on the Play Console and
ensure the in-app legal copy is up to date (bump `LEGAL_VERSION` in
`SettingsRepository` whenever the legal docs change so existing users
re-accept).

## Custom training

The bundled classifier was trained on two pronunciation variants
(`hey gotcha`, `hey gah chuh`). To retrain for a different phrase or
accent, follow the [official openWakeWord training
Colab](https://colab.research.google.com/drive/1q1oe2zOyZp7UsB3jJiQ1IFn8z5YfjwEb),
export `model.onnx`, replace `app/src/main/assets/openwakeword/hey_gotcha.onnx`,
and update `WakeWordDetector.CLASSIFIER_MODEL` if the filename changes.

See [`docs/MODEL_CARD.md`](MODEL_CARD.md) for the threshold recommendations and
[`docs/wake-word-handoff.md`](wake-word-handoff.md) for the implementation
checkpoint and ongoing work.
