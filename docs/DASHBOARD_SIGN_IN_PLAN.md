# Plan: Samosa sign-in through the account website

Status: implemented (see [`samosa-sign-in.md`](samosa-sign-in.md)). Gotcha-side work only.

## Goal

Gotcha users sign in to Samosa AI on the Samosa account website
(`SAMOSA_DASHBOARD_URL`), opened inside the app in a Custom Tab, instead of a
Google account picker inside the app. One sign-in covers all Samosa apps, and
the same website is where the user manages their account. Gotcha gets
everything a user expects from that: sign in, stay signed in, see credits,
manage the account, sign out, and clear messages when the session ends.

The release ships at the same time as the website. Google sign-in stays in this
release as a fallback and is removed in the next one.

## Starting point

- **PR #177 (session renewal) is the base.** It adds `SamosaSession` and
  `SamosaAuthInterceptor`, stores the refresh token, and renews it. This work
  is stacked on its branch (`feat/samosa-token-refresh`) and rebased onto
  `development` once #177 merges.
- **Uncommitted work-in-progress on `development`** has two parts:
  1. Website sign-in (Custom Tab, PKCE, redirect handling, code exchange). This part is kept.
  2. A second, separate implementation of session renewal. This part is thrown
     away in favour of #177, because it overlaps #177 and has a bug: on any
     401, from any provider, it could retry the request with the Samosa token,
     sending that token to a different provider.

## Phase 0: Branch

1. Commit the work-in-progress to a throwaway branch so nothing is lost.
2. Create `feature/dashboard-sign-in` from `feat/samosa-token-refresh`.
3. Bring over only the sign-in parts:
   - `AndroidManifest.xml` intent filter
   - `SAMOSA_DASHBOARD_URL` in `build.gradle.kts`
   - `OAuthTokenRequest` and `exchangeCode` in `SamosaAuthApi`
   - the sign-in flow in `SamosaAuthManager`
   - the redirect handling in `MainActivity`
   - the new strings
4. Leave out:
   - `SamosaSessionRefresher` and `refreshSession()`
   - the 401 retry blocks in `LLMClient` and `AudioApi`
   - the refresh fields and the `save()` change in `SettingsRepository`
   - the `RefreshRequest`/`RefreshResponse` duplicates

## Phase 1: Sign-in that holds up on real phones

| # | Task | Where |
|---|------|-------|
| 1.1 | Build flag `SAMOSA_DASHBOARD_SIGN_IN` (default `true`, overridable from env or `local.properties`, like the other Samosa config). On: website sign-in is the main button and "Use Google instead" is a secondary link. Off: the current Google flow only. | `build.gradle.kts`, `SamosaAuthManager`, `AiConfigScreen` |
| 1.2 | Keep Google sign-in working. Restore the Credential Manager path from `development` next to the new flow instead of deleting it. | `SamosaAuthManager` |
| 1.3 | Save the pending request (verifier, state, start time) in encrypted prefs, not only in memory. Android can kill Gotcha while the browser is open. Today the redirect would then arrive with nothing waiting for it, and the sign-in would be lost without any message. Expire it after 5 minutes. | `SamosaAuthManager`, `SettingsRepository` |
| 1.4 | Handle the redirect in `onCreate` as well as `onNewIntent`. After process death the activity is recreated and only `onCreate` sees the intent. | `MainActivity` |
| 1.5 | Accept only the exact redirect path (`/oauth2redirect`), and ignore and log any other `com.gotcha:` link. Android ignores path filters on a URI with no host, so this check lives in code (`SamosaAuthManager.isSignInRedirect`), not the manifest. | `AndroidManifest.xml`, `SamosaAuthManager` |
| 1.6 | Detect a closed Custom Tab. When Settings resumes and no redirect arrives within about 2 s, end the attempt as `Cancelled` rather than waiting out the 4-minute timeout. Keep the timeout as a backstop. | `AiConfigScreen`, `SamosaAuthManager` |
| 1.7 | Ignore a second tap on sign-in while one is in progress. Keep the "replace a pending request" behaviour for a deliberate retry. | `SamosaAuthManager` |
| 1.8 | Use #177's helpers: `APP_ID`, `deviceLabel()` (capped at 120 characters), and `saveSamosaSession(token, email, refreshToken.orEmpty())`. | `SamosaAuthManager` |
| 1.9 | Error messages: user cancelled or denied → silent `Cancelled`. Expired or already-used code (400 on exchange) → "Sign-in expired, please try again". 403 → the existing "account disabled" string. Network → the existing string. `state` mismatch → the new mismatch string. No browser at all → a new string asking the user to install or enable a browser. | `SamosaAuthManager`, `strings.xml` |
| 1.10 | Make the sign-in copy neutral. "Signing in with Google…" becomes "Opening Samosa sign-in…" when the flag is on. | `strings.xml`, `AiConfigScreen` |

## Phase 2: Account features in the app

| # | Task | Notes |
|---|------|-------|
| 2.1 | **Manage account** row when signed in. It opens the account website in a Custom Tab, where usage, devices, billing, referrals and account deletion live. | The Custom Tab shares the browser's cookies, so a user who just signed in there usually stays signed in. |
| 2.2 | **Add credits / Upgrade** action next to the credit balance, opening the same website. | Starts on the website's home page. It can jump to a section once the website supports that. |
| 2.3 | **Out of credits in chat.** An HTTP 402 now gets its own message pointing to Add credits. A tappable action in the chat bubble waits until the exact out-of-credits response is confirmed. | `HumanReadableError` |
| 2.4 | **Signed out from elsewhere.** When the session was ended from the website's device list (or expired), renewal fails and #177 signs the user out. Show "You were signed out. Sign in again to keep using Samosa AI." and open the sign-in card. Keep #177's separate banned message. | `MainActivity` sign-out toast; `SignOutReason` from #177 |
| 2.5 | **Sign out** ends the session on the server with the live token from #177, then clears it locally. Signing out on the website, then using the app, behaves as in 2.4. | Already mostly there; verify with #177's live token. |
| 2.6 | **Refresh after returning from the website.** When the app resumes after Manage account / Add credits, reload the profile and credits. | `AiConfigScreen` `LaunchedEffect`/resume |
| 2.7 | **Invite codes.** New accounts are now created on the website, so Gotcha can't send an invite code at sign-up. Make sure the existing "enter invite code" prompt (claim window) appears after a first website sign-in. | Existing referral claim flow |

## Phase 3: Per-app attribution

- Every Gotcha sign-in path identifies the app as `gotcha-android`:
  `oauth_client` on the website URL and `app` on Google sign-in (#177).
- Chat, audio and notification requests need no change: the session token
  already says which app it belongs to.
- Test: a website sign-in followed by one chat request shows up as Gotcha usage
  on the account website.

## Phase 4: Existing users

- Users who are already signed in stay signed in after the update. There is no
  forced re-login. Their old token keeps working, and #177 handles the
  move to renewable sessions.
- Test: install the current Play build, sign in, update to the new build,
  chat, then restart the app.

## Phase 5: Tests

**Unit tests:**
- PKCE values (S256, verifier 43–128 characters)
- URL building
- redirect parsing: ours, foreign, wrong path, `error=`, missing code, `state` mismatch
- the pending request surviving a recreated `SamosaAuthManager` (process death)
- expiry of the saved request
- double tap
- each code-exchange error mapping
- refresh token saved after exchange
- flag on and off
- the `FeedbackChannelTest` fake updated to the new API

**On a phone:**
- browsers: Chrome, Samsung Internet, Firefox, and no Custom Tabs browser
- "Don't keep activities" turned on (process death)
- closing the tab mid-sign-in
- denying on the website
- airplane mode during the code exchange
- Arabic (right-to-left)
- dark mode
- Manage account and Add credits both return to the app

**Checks:** `./gradlew testDebugUnitTest detekt lint`.

## Device test checklist (hand-off)

The code and unit tests are done on `feature/dashboard-sign-in`: 2,382 unit
tests pass, detekt and lint are clean. Work through this list on a real phone
and record each result as pass, fail or blocked, with a screenshot for each
failure.

### Before installing

- [ ] **Don't lose the phone's data.** A phone that already has a Play or
  release build of Gotcha (`com.gotcha`) won't accept a debug build signed
  with a different key. Never uninstall it to make room without the owner's
  OK, because that deletes their chats, memory and settings. Either:
  - sign the test build with the release key, so it installs as an update; or
  - add a separate debug app id. That needs its own redirect scheme, and the
    account website has to allow it.
- [ ] Build and install: `./gradlew :app:installDebug`, or `assembleRelease`
  with the release key. Watch the logs with
  `adb logcat -s SamosaAuth SamosaSession SamosaBrowser`.
- [ ] Check which backend the build points at (`SAMOSA_API_URL`,
  `SAMOSA_DASHBOARD_URL`). The full website sign-in needs a backend with
  `/oauth/token` and `/auth/refresh` live. Until then, the cases marked
  **(backend)** are blocked, not failed.

### Sign-in

1. [ ] Settings → AI Configuration → provider Samosa AI.
   - The button reads "Sign in to Samosa AI", with "Use Google instead" under it.
   - The hint names the website.
2. [ ] Tap sign-in: a Custom Tab opens on the account website, and the
   status reads "Opening Samosa sign-in…".
3. [ ] **(backend)** Finish signing in on the website.
   - The tab closes and Gotcha shows "Signed in as …" with the credit balance.
   - A first-time account gets the invite-code dialog.
4. [ ] Close the tab without signing in. Within about 2 s the status reads
   "Sign-in cancelled." and the button works again.
5. [ ] **(backend)** Decline on the website: the result is a cancel, not an error.
6. [ ] **Process death (backend):**
   1. Turn on Developer options → "Don't keep activities".
   2. Start the sign-in and finish it on the website.
   3. Gotcha should come back signed in, with a "Signed in as …" toast, and
      Settings should show the account.
   4. Turn the option off again afterwards.
7. [ ] **(backend)** Turn on airplane mode just before the website returns:
   the network error message appears and nothing is half-signed-in.
8. [ ] Tap "Use Google instead": the Google account picker appears and
   signing in works as in 1.4.0.
9. [ ] Repeat 2–4 in Samsung Internet and in Firefox, with each one set as
   the default browser.
10. [ ] Disable every browser, if the phone allows it: sign-in says no browser
    is available.
11. [ ] Rotate the phone during steps 2–4: no crash, and no second sign-in page.

### Signed in

12. [ ] "Manage account" opens the account website in a Custom Tab, still
    signed in.
13. [ ] "Add credits" next to the balance opens the website. It may land on
    the overview page for now.
14. [ ] Come back from the website: credits and tier reload.
15. [ ] **(backend)** End this device's session from the website's device
    list, then send a chat.
    - "You were signed out of Samosa AI…" appears.
    - The Settings card goes back to the sign-in button.
16. [ ] Log out: the card returns to the sign-in button, and signing in again works.
17. [ ] **(backend)** Leave the app idle past the access-token lifetime, then
    chat: it still works, because the token renewed silently.
18. [ ] **Existing users.**
    1. Install the current Play build and sign in with Google.
    2. Update to this build: still signed in, and chat works.
    3. Restart the app: still signed in.

### Look and language

19. [ ] Do steps 1, 12 and 13 in dark mode and in Arabic (right-to-left).
    - Nothing overlaps or is cut off.
    - The Arabic strings read naturally. Flag any that don't for a native speaker.
20. [ ] Settings → Speech with Samosa AI selected shows the same sign-in card
    and buttons.

### Reporting

- For a failure, file the step number, phone model, Android version, browser,
  the screenshot and the matching `logcat` lines.
- Fix bugs on `feature/dashboard-sign-in`. The sign-in flow lives in
  `auth/SamosaAuthManager.kt`, the website links in `auth/SamosaDashboard.kt`,
  the card in `ui/SettingsCommon.kt` (`SamosaAuthSection`), and the redirect
  and resume handling in `MainActivity.kt`.
- Unit tests: `./gradlew :app:testDebugUnitTest --tests 'com.gotcha.auth.*'`.

## Device test results (2026-11-14)

Run on a physical Samsung phone against a local test backend, with Chrome as
the default browser. Numbers match the checklist above.

| Step | Result |
|------|--------|
| 1. Sign-in card, website button and "Use Google instead" | Pass |
| 2. Custom Tab opens on the sign-in page | Pass |
| 3. Finish signing in on the website | Pass, including the invite-code dialog for a new account |
| 4. Close the tab | Pass: "Sign-in cancelled." within a few seconds |
| 5. Decline on the website | Pass: ends as a cancel |
| 6. Gotcha killed while the browser is open | Pass: it restarts, finishes the sign-in and shows "Signed in as …" |
| 12. Manage account | Pass: opens already signed in, same credits as the app |
| 15. Session ended from another device | Pass after a fix (below) |
| 17. Silent renewal of an expired token | Pass: renewed once, still signed in |
| 8, 9, 10, 11, 13, 14, 16, 18, 19, 20 | Not run yet |

Found and fixed during the run:

- **Sign-in crashed the app for an account with no profile picture.** The
  backend sends `null` there and the app only accepted text. The reply is now
  read leniently, and an unreadable reply ends the sign-in with an error
  instead of crashing.
- **A session ended from another device left the card on "Signed in".** Only
  the chat path had a callback for it. The session itself now reports it, so
  every screen shows the message and the sign-in button.

The test phone's default browser could not open local test pages, so the
default was switched to Chrome for the run.

## Testing against a local backend

Nothing about the backend is fixed in the code. Point a debug build at a local
stack with build settings, then reach it from the phone over USB:

```
SAMOSA_API_URL=http://localhost:9000 \
SAMOSA_DASHBOARD_URL=http://localhost:9001 \
./gradlew :app:assembleDebug

adb reverse tcp:9000 tcp:<backend port on this computer>
adb reverse tcp:9001 tcp:<website port on this computer>
```

The same settings (`SAMOSA_API_URL`, `SAMOSA_DASHBOARD_URL`,
`SAMOSA_DASHBOARD_SIGN_IN`) go in the release environment or
`local.properties`. The release build uses the production API address and the
website address; no code changes when the backend moves.

## Phase 6: Release

- CHANGELOG entry and translations for the new strings.
- The release goes out at the same time as the website. If website sign-in has
  a problem on launch day, "Use Google instead" still works with no new build.
- Next release: remove Google sign-in, the flag and the Credential Manager
  dependencies.

## Out of scope

- Changes to the account website or the Samosa backend.
- Removing Google sign-in (next release).
