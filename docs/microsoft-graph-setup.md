# Microsoft connector setup (Outlook, Calendar, To Do)

The Microsoft connector uses **your own** Entra (Azure AD) app registration, the same
bring-your-own-OAuth approach as the Gmail connector. Nothing is shared with other users of
the app, and no publisher verification is involved. One sign-in covers Outlook mail, the
Outlook calendar and Microsoft To Do.

Registering an app is free and works with a personal Microsoft account.

## 1. Register the app

1. Sign in at [portal.azure.com](https://portal.azure.com) and open **Microsoft Entra ID →
   App registrations → New registration**.
2. Name it anything (e.g. `Gotcha`).
3. Under **Supported account types**, pick the option that matches your account:
   - personal Outlook/Hotmail/Live account → *Accounts in any organizational directory and
     personal Microsoft accounts*
   - work or school account only → *Accounts in this organizational directory only*
4. Under **Redirect URI**, choose platform **Public client/native (mobile & desktop)** and
   enter `http://localhost`.
5. Register.

The redirect URI matters: Entra accepts **any port** on a `http://localhost` loopback
redirect for public clients, which is what lets the app catch the sign-in on a random local
port without you registering each one.

## 2. Add the permissions

**API permissions → Add a permission → Microsoft Graph → Delegated permissions**, then add:

| Permission | What it enables |
|---|---|
| `offline_access` | staying signed in — **without this no refresh token is issued** |
| `User.Read` | identifying which account is connected |
| `Mail.ReadWrite` | listing and reading mail, marking read/unread |
| `Mail.Send` | `send_email` |
| `Calendars.ReadWrite` | reading and creating calendar events, free/busy |
| `Tasks.ReadWrite` | Microsoft To Do |

You can leave out the ones you don't want — the corresponding tools will simply report a
permission error. On a work or school account an administrator may need to consent.

## 3. Connect

1. Copy the **Application (client) ID** from the registration's Overview page.
2. In the app: **Settings → Connectors → Microsoft**.
3. Paste the client ID. Leave **Tenant** as `common` for a personal account; for a work
   account that restricts external sign-in, use your tenant ID or domain.
4. Tap **Connect**. A browser tab opens for sign-in and consent, then returns to the app.

There is no client secret — this is a public client authenticated with PKCE.

### If the browser doesn't come back

Tap **"Browser didn't return? Paste redirect URL"**, copy the full
`http://localhost:.../?code=…` URL out of the browser's address bar, and paste it there.
This only works immediately after a Connect attempt, because it reuses that attempt's PKCE
verifier.

## What you get

| Tool | Notes |
|---|---|
| `list_emails`, `read_email`, `send_email`, `mark_email_read` | The normal email tools. Outlook message ids are prefixed `ms:`. If a Gmail connector is also set up, Gmail takes precedence for new listings and sends. |
| `list_calendar_events`, `create_calendar_event` | Pass `source="microsoft"`. The default stays the phone's own calendar. |
| `check_availability` | Free/busy across the connected account. |
| `list_tasks`, `create_task`, `complete_task` | Microsoft To Do. Distinct from `todowrite`, which is only the agent's in-conversation scratch plan. |

## Reconnecting

If the refresh token is invalidated — password change, admin revoke, MFA reset, or the grant
removed at [myaccount.microsoft.com](https://myaccount.microsoft.com/privacy#apps) — the
card shows *"Reconnect needed"* and the tools steer the agent to Settings. Tap Connect
again; the client ID is remembered.

## Manual end-to-end check

1. Connect, and confirm the card shows *Connected as …*.
2. `list_emails` → ids start with `ms:`.
3. `read_email` on one of those ids.
4. `send_email` → confirm the dialog appears **before** anything is sent.
5. `mark_email_read`, then check the message in Outlook.
6. `list_calendar_events` with `source="microsoft"`.
7. `check_availability` → busy blocks match the real calendar.
8. `list_tasks` → `create_task` → the task appears in Microsoft To Do → `complete_task`.
9. Revoke the grant at myaccount.microsoft.com, run any tool, and confirm it reports that a
   reconnect is needed rather than failing obscurely. Then reconnect.
