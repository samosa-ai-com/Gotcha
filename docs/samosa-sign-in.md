# Samosa AI sign-in

Gotcha signs you in to Samosa AI on the Samosa account website, in a browser
tab inside the app. The same website is where you manage the account. This page
describes what the app does and how to work on it. For the build settings, see
[`RUNNING.md`](RUNNING.md#samosa-endpoints-optional-developers-only).

## What you see

1. **Settings → AI → AI Configuration**, with **Samosa AI** as the provider,
   shows **Sign in to Samosa AI** and, under it, **Use Google instead**.
2. Tap sign-in. A browser tab opens on the account website. Sign in there and
   approve **Gotcha**; the tab closes and Gotcha shows your account and credits.
3. Once signed in, the same card has **Manage account** and **Add credits**,
   which open the website. Credits bought there show up when you come back.
4. If the session ends (you signed out of Gotcha on the website, it expired, or
   the account was disabled), Gotcha says so and shows the sign-in button again.
   A disabled account gets its own message.

Closing the tab, or declining on the website, is a cancel, not an error.

## How it works

- The app opens the website's sign-in page with a one-time **PKCE** challenge
  (S256) and a random `state`. The matching secret stays on the phone.
- The website returns to `com.gotcha:/oauth2redirect` with a one-time code and
  the same `state`. The app checks the `state`, then trades the code and the
  PKCE secret for a session. A code is single-use and short-lived.
- The pending sign-in (the PKCE secret and `state`) is kept in encrypted
  storage until it finishes or expires after five minutes. Android may stop
  Gotcha while the browser is open; the return link is handled on a fresh start
  too, so the sign-in still completes.
- Only the exact return link finishes a sign-in. Any other `com.gotcha:` link
  is ignored.
- After sign-in, `SamosaSession` keeps the session renewed in the background.
  See [`CHANGELOG.md`](../CHANGELOG.md) for that change.
- **Google fallback:** "Use Google instead" signs in with a Google account
  through Credential Manager, as earlier versions did. Set
  `SAMOSA_DASHBOARD_SIGN_IN=false` to make it the only sign-in.

The code:

| Part | File |
|---|---|
| Flow, redirect handling, restart survival | `app/src/main/java/com/gotcha/auth/SamosaAuthManager.kt` |
| Opening the website and its pages | `app/src/main/java/com/gotcha/auth/SamosaDashboard.kt` |
| Session renewal and session-ended reporting | `app/src/main/java/com/gotcha/auth/SamosaSession.kt` |
| The sign-in card | `SamosaAuthSection` in `app/src/main/java/com/gotcha/ui/SettingsCommon.kt` |
| Return link and resume handling | `app/src/main/java/com/gotcha/MainActivity.kt` |

## Testing against a local backend

The website and API addresses are build settings, so a debug build can point at
a stack on your computer and reach it over USB:

```bash
SAMOSA_API_URL=http://localhost:9000 \
SAMOSA_DASHBOARD_URL=http://localhost:9001 \
./gradlew :app:assembleDebug

adb reverse tcp:9000 tcp:<API port on your computer>
adb reverse tcp:9001 tcp:<website port on your computer>
```

Notes from doing this on a phone:

- The browser that opens the sign-in tab must be able to open `localhost`
  pages. Some browsers treat `http://localhost:…` as a search; set Chrome as
  the default browser for the test.
- A debug build installs over a release build only if both are signed with the
  same key. Otherwise Android refuses, and you would have to uninstall (which
  deletes the app's data).
- Run the unit tests with
  `./gradlew :app:testDebugUnitTest --tests 'com.gotcha.auth.*'`.

The [sign-in plan](DASHBOARD_SIGN_IN_PLAN.md) has the full device checklist and
the results of the last run.
