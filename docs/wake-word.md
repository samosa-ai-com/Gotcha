# Wake Word

Gotcha's optional wake word uses Vosk locally. No audio is sent anywhere while
the listener is waiting; only the existing voice call sends the spoken command
to the configured speech-to-text provider.

The build uses the small English Vosk model at
`app/src/main/assets/vosk-model-small-en-us-0.15`. The model is currently
present in the working tree and was downloaded from the official Vosk model
catalog, which lists it as Apache 2.0. It adds roughly 40 MB of compressed
model data to the app, so decide deliberately before committing it.

The listener is active only when both Assistive Ball and Wake word are enabled,
the microphone permission is granted, and the call state is idle. An unloadable
model or unavailable microphone disables detection without crashing the service.

See `docs/wake-word-handoff.md` for the current implementation checkpoint and
remaining work.
