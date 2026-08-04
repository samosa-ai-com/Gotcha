# Wake Word

Gotcha's optional wake word uses Vosk locally. No audio is sent anywhere while
the listener is waiting; only the existing voice call sends the spoken command
to the configured speech-to-text provider.

The Android build expects the Vosk model directory
`app/src/main/assets/vosk-model-small-en-us-0.15`. Download the corresponding
small English model from the official Vosk model distribution and place its
contents under that directory. The model is intentionally not committed to the
source repository because it is a large binary asset.

The listener is active only when both Assistive Ball and Wake word are enabled,
the microphone permission is granted, and the call state is idle. A missing
model or unavailable microphone disables detection without crashing the service.
