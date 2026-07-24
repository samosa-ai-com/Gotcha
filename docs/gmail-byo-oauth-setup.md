# Gmail (BYO OAuth) connector setup

Gotcha doesn't ship a shared Google OAuth client — instead, every user connects
Gmail with a client they create in their own free Google Cloud project. This
"Bring Your Own OAuth" approach (same one n8n uses for self-hosted Gmail nodes)
means restricted scopes like `gmail.modify` don't need Google's CASA security
verification, at the cost of a few minutes of one-time setup.

## Why this instead of a built-in "Sign in with Google" button

- A shared OAuth client requesting `gmail.modify` for public distribution would
  require Google's CASA assessment (an ongoing paid security review).
- Android-type OAuth clients are bound to a package name + SHA-1 fingerprint,
  so users can't each register their own `com.gotcha` client.
- The fix: each user creates a **Desktop app** OAuth client. Desktop clients
  support the "loopback" redirect (`http://127.0.0.1:<port>`), which needs no
  redirect URI to be pre-registered — the app just picks an ephemeral port at
  connect time.

## Setup steps (Settings → Connectors → Gmail (BYO OAuth))

1. **Create a Google Cloud project** at [console.cloud.google.com](https://console.cloud.google.com)
   (any Google account, free tier is enough).
2. **Enable the Gmail API**: APIs & Services → Library → search "Gmail API" → Enable.
3. **Configure the OAuth consent screen**: APIs & Services → OAuth consent screen.
   - User type: **External**.
   - Add your own Google account as a **test user**.
   - Leave the app in **"Testing"** status, or **publish to "In production"**
     (see the note on 7-day expiry below). Either way you'll see an
     "unverified app" warning during sign-in — that's expected and fine,
     since you own the app.
4. **Create a Desktop app OAuth client**: APIs & Services → Credentials →
   Create Credentials → OAuth client ID → Application type: **Desktop app**.
   No redirect URI needs to be entered.
5. **Paste the Client ID and Client secret** into the Gmail card in
   Settings → Connectors.
6. Tap **Connect**. A browser tab opens for Google sign-in; after you approve,
   it redirects to a local `127.0.0.1` address that the app is listening on,
   and the tab shows "Signed in — you can close this tab."

### If the browser tab doesn't return to the app

Some devices kill the app process while the browser tab is open, or the
listener dies before the redirect lands. Use the **"Browser didn't return?
Paste redirect URL"** field: copy the full URL from the browser's address bar
after it shows the "Signed in" page (or any redirect to `127.0.0.1`), paste it
in, and tap "Finish sign-in with pasted URL." This only works immediately
after tapping Connect, since it reuses that attempt's PKCE verifier.

## The 7-day reconnect note

While the consent screen is in **"Testing"** status, Google expires refresh
tokens after 7 days, so you'll need to reconnect weekly. To avoid this,
**publish the consent screen to "In production"** (OAuth consent screen →
Publish app). You'll still see the "unverified app" warning on sign-in — since
restricted scopes on an app only you use don't require CASA verification —
but tokens will stop expiring weekly.

If a refresh does expire, the connector shows "Reconnect needed" and email
tools return an error steering the user back to Settings.

## Revoking access

Settings → Connectors → Gmail → Disconnect clears the locally stored
credentials and best-effort revokes the grant. You can also revoke it directly
at [myaccount.google.com/permissions](https://myaccount.google.com/permissions).

## Manual end-to-end checklist

- [ ] Connect completes and the card shows "Connected as <email>."
- [ ] `list_emails` returns recent messages; `unread_only` and `query` filter correctly.
- [ ] `read_email` shows the plain-text body (including for HTML-only messages).
- [ ] `send_email` shows a confirmation with To/Subject/body preview before sending.
- [ ] `mark_email_read` toggles the unread flag (verify in the Gmail app).
- [ ] Revoke access at myaccount.google.com/permissions, then retry a tool call →
      connector reports "Reconnect needed" instead of crashing.
- [ ] Reconnect succeeds and clears the reconnect-needed state.
- [ ] Kill the app while the Custom Tab is open mid-consent → use the paste-URL
      fallback to complete the connection.

## IMAP connector (no Google Cloud project needed)

For any provider (Gmail included) that supports IMAP with an app password,
Settings → Connectors → Email (IMAP) is the simpler default: enter your email
address and an app password (Google Account → Security → 2-Step Verification →
App passwords, or directly at myaccount.google.com/apppasswords — requires
2-Step Verification to be enabled), use the "Use Gmail preset" button for
Gmail's IMAP/SMTP hosts, or fill in another provider's host/port. This gets
full read/write email access with no OAuth setup, at the
cost of scoping to "everything IMAP/SMTP can do" rather than Gmail's more
granular `gmail.modify` scope.

## Manual clock intent checklist

`set_timer(system=true)`, `show_alarms`, `snooze_alarm`, and `dismiss_timer`
send intents to whatever clock app is installed; behavior (especially
`EXTRA_SKIP_UI` and dismiss/snooze support) varies by OEM. Verify manually on
Google Clock at minimum:

- [ ] `set_alarm` / `set_timer` land in Google Clock's lists.
- [ ] `show_alarms` opens the alarms tab.
- [ ] While an alarm is ringing, `snooze_alarm` snoozes it.
- [ ] While a system timer (`set_timer(system=true)`) is ringing, `dismiss_timer`
      stops it.
