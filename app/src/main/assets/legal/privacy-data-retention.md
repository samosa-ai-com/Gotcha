DATA RETENTION AND PRIVACY POLICY

Last updated: 23 November 2026

This policy explains what data the Gotcha App ("App") and the underlying
AI backends handle, how long it is kept, and where it lives. The short
version: the App itself does not collect or retain your content on
our servers — everything stays on your device unless you choose to use
a backend that transmits it, such as Samosa AI (Section 4.2) or an AI
provider you configure yourself. On first
launch the App registers the install with us; that registration
describes the build and the device in broad terms and identifies no
one. Usage data, which is counts and outcomes only, is on by default
and can be turned off; see Section 11.

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
   1.4 The App does not ship any third-party analytics,
       crash-reporting or tracking SDK. The only server-side records we
       keep are the install registration made on first launch and the
       usage data described in Section 11. Neither contains your
       content.

2. WHAT IS TRANSMITTED, AND WHEN

   2.1 The App transmits data when it registers an install on first
       launch (Section 11), when you send a request to an AI backend, or
       when usage data is on (also Section 11). An AI request is sent
       only when you (or the agent you have configured) initiate one —
       for example, by typing a message, starting a voice call, or
       invoking a tool that needs vision.

   2.2 If you choose to use Samosa AI as your backend, requests are
       transmitted to Samosa AI over HTTPS, authenticated with the
       short-lived JWT issued by Samosa AIR after your Google sign-in.
       See Section 4 for how Samosa AI handles that request.

   2.3 If you choose to use a User-Provided Provider, requests are
       transmitted directly to that provider's endpoint over HTTPS,
       authenticated with the API key you supplied. The App does not
       proxy or store that key on any server we operate.

3. WHAT WE KEEP ON OUR SIDE

   3.1 The App never sends your chats, calls, screenshots, prompts,
       completions, or credentials to Gotcha. The one exception is when
       you choose Samosa AI as your backend: the requests you send it
       are handled by Samosa AIR as described in Section 4.2. We keep
       the install registration and, when usage data is on, the counts
       and outcomes described in Section 11. That data is kept
       indefinitely in anonymised form; recent data sits in a rolling
       30-day working window used for day-to-day analysis. Nothing in
       it identifies you, so there is no per-person record to delete.
   3.2 If you want to remove local data, uninstall the App, or use the
       in-app controls (e.g. "Clear chat," "Clear LLM cache").

4. THIRD-PARTY AI BACKENDS — PLEASE READ

   4.1 The App is a frontend. The actual AI processing is performed by
       a third-party backend you select. Each backend has its own data
       practices. Once a request leaves your device, the backend's
       terms apply.

   4.2 Samosa AI (the Samosa AIR service):
         (a) Samosa AIR is a separate service, operated by Samosa AI,
             with its own Samosa AIR Terms of Service, shown in the
             App under Settings ▸ About ▸ Legal. When you sign in to
             Samosa AI, you accept those terms in addition to these.
             They are the authoritative description of what Samosa AIR
             keeps.
         (b) In short: Samosa AIR keeps the requests you send and the
             responses it returns, encrypted, for 30 days, to run and
             bill the service and to prevent abuse. It never uses them
             to train AI models and does not sell them. It also keeps
             your account details (email, name, profile picture), your
             plan and credit records, and usage and sign-in records
             with IP addresses, for the periods its terms set out.
         (c) Samosa AIR routes requests to underlying model providers.
             Before forwarding, it removes your account details and IP
             address from the request.
         (d) However, Samosa AIR cannot fully eliminate provider-side
             exposure. The underlying model provider will receive the
             contents of your prompt and may receive provider-managed
             identifiers (request IDs, abuse-prevention tokens) for the
             purpose of operating the service.

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
         (b) clear the LLM response cache (Settings → Chats and storage);
         (c) clear debug screenshots (Settings → Chats and storage);
         (d) uninstall the App, which removes all app-private data,
             including settings, caches, and history.
       Uninstalling does not withdraw the install registration or usage
       data already sent; neither contains anything that identifies
       you, so there is nothing to delete on request (Section 11.9).
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

   7.1 Because the App does not keep your content on our servers, it
       is your responsibility to:
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

   9.1 Questions about this policy can be sent to contact@samosa-ai.com.

10. OPTIONAL WAKE WORD ("Hey Gotcha")

   10.1 The wake-word feature is opt-in. When turned on in Settings
        (Settings → Floating ball and wake word → "Wake word: Hey Gotcha"), the
        App keeps a microphone-type foreground service alive while the Assistive
        Ball is on and no voice call is in progress.

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

11. INSTALL REGISTRATION AND USAGE DATA

    11.1 Install registration. On first launch, whether usage data is
         on or off, the App registers the install with a server
         operated by Samosa AI over HTTPS. The registration contains:
           (a) the App's version, build number and build type, and
               whether it came from a store or was sideloaded;
           (b) the Android version, API level and security patch level;
           (c) the device's primary CPU ABI, manufacturer and model,
               and the manufacturer's software skin as a broad kind;
           (d) memory, storage, screen size, screen density and refresh
               rate as broad buckets;
           (e) the language setting and the country derived from the
               connection;
           (f) the install time and the time-zone offset at install;
               and
           (g) whether the device looks like an emulator or shows root
               signals.
         It contains no name, email address, account, phone number,
         advertising ID or other device identifier, and it identifies
         no one. We use it to operate the service — for example, to
         know which Android versions and screen sizes to support and
         which builds are in use — and to protect the service from
         abuse.

    11.2 Usage data is on by default in every country. The switch is
         always in Settings → Privacy → "Share usage data". On the
         first screen it is shown as well where the law treats prior
         consent as required (for example the European Economic Area
         and the United Kingdom), so the disclosure and the control sit
         together; elsewhere it is on by default and only in Settings.
         Nothing in the App depends on it, and turning it off costs you
         no feature.

    11.3 Purpose: to learn which features are used and where the App
         fails or crashes, so we can fix and improve it. It is never
         sold, and never used for advertising.

    11.4 What is sent: counts and outcomes only. Each event is one of a
         fixed list, and each of its values is a yes/no, a range (for
         example "6–30 seconds"), or a word from a fixed list. The full
         list of events is shown in the App under Settings → Privacy →
         "What we send". With each batch the App also sends its version,
         the Android version, the language setting and whether the
         device is a phone or a tablet.

    11.5 What is never sent: your messages or the assistant's replies,
         what is on your screen, files or file names, contacts, text
         messages, call logs, calendar entries, notifications, location,
         health data, shell commands or their output, the names of your
         apps, web addresses, your API key, your Samosa AI account or
         email, and any device identifier (Android ID, IMEI, serial
         number or advertising ID). The text of an error is never sent;
         for a crash, only the type of error and the name of the method
         it came from. Usage data never carries your content, and none
         of the content you type, say, see, save or run leaves the
         phone as part of it.

    11.6 Identifier: the install registration gives the App a random
         install id and a token. They are not derived from your device
         or your account, and they are not linked to your Samosa AI
         account. Nothing else is needed to send usage data.

    11.7 Where it goes and how long it is kept: to a server operated by
         Samosa AI, over HTTPS. Data is kept indefinitely in anonymised
         form. Recent data sits in a rolling 30-day working window used
         for day-to-day analysis, and older data is retained in
         anonymised aggregate for longer-term product decisions. Your
         IP address is not stored with it.

    11.8 Consent record. The App records your choice — whether you
         accept or decline usage data — as evidence of that choice,
         together with the surface it was made on (first run or
         Settings) and the policy version. This record is kept whether
         you accept or decline, and it is kept if you later change the
         choice.

    11.9 Your choices: turning usage data off stops collection at once
         and deletes anything waiting on the phone. Turning it back on
         records the new choice. There is no "delete my data" request,
         because there is nothing in what we hold that identifies you
         to delete. Questions can go to contact@samosa-ai.com.

    11.10 Builds of the App that you compile yourself from the public
          source code send no usage data at all.

— END OF DATA RETENTION POLICY —
