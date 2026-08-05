DATA RETENTION AND PRIVACY POLICY

Last updated: [effective date]

This policy explains what data the Gotcha App ("App") and the underlying
AI backends handle, how long it is kept, and where it lives. The short
version: the App itself does not collect or retain data on our
servers — everything stays on your device unless you choose to use a
backend that transmits data to a third-party AI provider.

1. WHAT THE APP STORES, AND WHERE

   1.1 All user-generated content (chat history, call recordings,
       screenshots captured during sessions, custom skills, action audit
       log, cached LLM responses) is stored locally on your device under
       Android's app-private storage:
         (a) /data/data/com.gotcha/files/chats/    — chat history
         (b) /data/data/com.gotcha/files/calls/    — voice-call logs
         (c) /data/data/com.gotcha/files/action_log.txt
         (d) /data/data/com.gotcha/cache/llm_cache/
   1.2 This data is never uploaded to us by the App. We have no way to
       read it. If you uninstall the App, all of it is removed.
   1.3 Settings (preferences, model selections, permission choices,
       legal acceptance) are stored in Android's encrypted
       SharedPreferences on your device. They are never uploaded to us.
   1.4 The App does not ship analytics, crash reporting, telemetry, or
       any background tracking SDK. We have no server-side record of
       your activity in the App.

2. WHAT IS TRANSMITTED, AND WHEN

   2.1 The App does not transmit data unless you send a request to an
       AI backend. A request is sent only when you (or the agent you
       have configured) initiate one — for example, by typing a message,
       starting a voice call, or invoking a tool that needs vision.

   2.2 If you choose to use Samosa AI as your backend, requests are
       transmitted to Samosa AI over HTTPS, authenticated with the
       short-lived JWT issued by Samosa AIR after your Google sign-in.
       See Section 4 for how Samosa AI handles that request.

   2.3 If you choose to use a User-Provided Provider, requests are
       transmitted directly to that provider's endpoint over HTTPS,
       authenticated with the API key you supplied. The App does not
       proxy or store that key on any server we operate.

3. ZERO COLLECTION / ZERO RETENTION BY US

   3.1 We do not operate a server that retains your chats, calls,
       screenshots, prompts, completions, or credentials. We have no
       analytics, no logging pipeline, no telemetry endpoint. There is
       therefore nothing on our side to "delete," because nothing is
       kept there in the first place.
   3.2 If you want to remove local data, uninstall the App, or use the
       in-app controls (e.g. "Clear chat," "Clear LLM cache").

4. THIRD-PARTY AI BACKENDS — PLEASE READ

   4.1 The App is a frontend. The actual AI processing is performed by
       a third-party backend you select. Each backend has its own data
       practices. Once a request leaves your device, the backend's
       terms apply.

   4.2 Samosa AI (the Samosa AIR proxy):
         (a) Samosa AI is operated independently of Gotcha. By
             using Samosa AI as your backend, you accept Samosa AI's
             own terms and privacy policy, in addition to these.
         (b) Samosa AI routes requests to underlying model providers.
             Samosa AI is designed to minimize what is forwarded:
             identifying metadata such as your IP address is not
             included in the prompt payload sent to the underlying
             model provider.
         (c) However, Samosa AI cannot fully eliminate provider-side
             exposure. The underlying model provider will receive the
             contents of your prompt and may receive provider-managed
             identifiers (request IDs, abuse-prevention tokens) for the
             purpose of operating the service.
         (d) Underlying model providers may, under their own policies,
             retain prompts and completions, and may use them to train
             and improve their models. Samosa AI uses providers that
             commit not to train on inputs delivered via the Samosa
             AIR proxy, but those commitments are governed by the
             provider's terms and may change.
         (e) You should review Samosa AI's privacy policy directly for
             the current, authoritative description of how Samosa AI
             and its underlying providers handle your data.

   4.3 User-Provided Providers (your own OpenAI-compatible endpoint):
         (a) When you supply your own API key and base URL, requests
             are sent directly to that endpoint under your credentials.
             The App does not proxy them through us.
         (b) Whatever that provider logs, retains, or trains on is
             governed by that provider's terms — not ours.
         (c) If you supply credentials to a third-party provider,
             those credentials are transmitted to that provider. We do
             not guarantee their security once transmitted, and we are
             not responsible for any resulting misuse, billing,
             retention, or training by that provider.

5. LOCAL DATA — RETENTION AND DELETION

   5.1 Local data persists on your device until you remove it. The App
       provides controls to:
         (a) clear individual chat histories ("Clear chat" in the
             drawer);
         (b) clear the LLM response cache (Settings → AI Configuration);
         (c) clear debug screenshots (Settings → AI Configuration);
         (d) uninstall the App, which removes all app-private data,
             including settings, caches, and history.
   5.2 Android may retain app-private data in backups (e.g. Auto Backup
       to Google Drive) depending on the user's Android settings. We
       do not control backup behavior; please review your device
       settings if this matters to you.

6. PERMISSIONS

   6.1 The App requests only the permissions it needs to operate as
       described in its on-device documentation. The full list is
       enumerated in the Permissions section of the App's Settings.
   6.2 Permissions you grant stay on your device. You can revoke them
       at any time through Android Settings → Apps → Gotcha.

7. YOUR RESPONSIBILITY

   7.1 Because the App does not collect data on our servers, it is
       your responsibility to:
         (a) safeguard your device with a screen lock;
         (b) review what permissions you have granted, and revoke
             those you no longer need;
         (c) back up any data you care about before granting the agent
             broad device access;
         (d) choose a backend whose data practices you are comfortable
             with; and
         (e) clear local data (or uninstall) before selling, gifting,
             or otherwise disposing of the device.

8. CHANGES

   8.1 We may update this policy. Material changes will be surfaced in
       the App (in-app notice or first-launch dialog). The "Last
       updated" date at the top will reflect the current version.

9. CONTACT

   9.1 Questions about this policy can be sent to samosa.ai.com@gmail.com.

10. OPTIONAL WAKE WORD ("Hey Gotcha")

   10.1 The wake-word feature is opt-in. When turned on in Settings
        (Settings → Assistive Ball → "Wake word: Hey Gotcha"), the App keeps a
        microphone-type foreground service alive while the Assistive Ball is on
        and no voice call is in progress.

   10.2 The wake-word listener processes all microphone audio **on-device**
        using the bundled OpenWakeWord models (Apache 2.0, see
        `app/src/main/assets/licenses/openwakeword-notice.txt`). Audio frames
        are fed to the OpenWakeWord pipeline to decide whether the phrase
        "Hey Gotcha" was spoken. No wake-word audio leaves the device — it is
        never sent to the App's servers, to the configured STT provider, or
        to any third party.

   10.3 The App's own text-to-speech (e.g. reading on-screen text aloud, or
        narrating replies inside a call) can sound similar to the wake word.
        To prevent the listener from triggering a call on the App's own
        speech, the wake-word detector is paused automatically while the
        App's text-to-speech is playing.

   10.4 A wake-word-triggered call ends itself after the spoken reply
        finishes (so the assistant can answer the wake-word command and
        then go back to listening for the next activation). This
        self-terminating behaviour is local; the App still does not stream
        the wake-word audio anywhere.

   10.5 For the wake-word listener to remain reliable while the screen is
        off or the device is dozing, the App may ask you to exempt it from
        Android battery optimization. That exemption is voluntary and can
        be revoked at any time through Android Settings.

   10.6 You can turn the wake-word feature off at any time; when off, the
        App does not retain or process any audio for wake-word purposes.

— END OF DATA RETENTION POLICY —
