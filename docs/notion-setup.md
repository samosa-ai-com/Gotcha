# Notion connector setup

The Notion connector uses an **internal integration token** rather than OAuth: you create an
integration inside your own workspace and paste its secret. There is no redirect URI, no
refresh cycle, and nothing to register with Notion for review — the same shape as the IMAP
app-password connector.

## 1. Create the integration

1. Go to [notion.so/my-integrations](https://www.notion.so/my-integrations) and click
   **New integration**.
2. Give it a name and pick the workspace it belongs to.
3. Under **Capabilities**, enable **Read content**, **Insert content** and **Update
   content**. (User information is not needed.)
4. Copy the **Internal Integration Secret** — it starts with `ntn_` (older ones start with
   `secret_`).

## 2. Connect

**Settings → Connectors → Notion**, paste the token, tap **Connect**. The token is validated
against Notion immediately, so a bad paste fails right there rather than on the first tool
call. The card then shows the integration/workspace name.

## 3. Share the pages — this is the step everyone misses

A fresh integration can see **nothing**. Notion requires each page to be shared with it
explicitly:

> open the page in Notion → **⋯** (top right) → **Connections** → **Connect to** → pick your
> integration

Sharing a page also shares everything nested under it, so sharing one top-level page per area
is usually enough.

If `notion_search` returns nothing, or `notion_read_page` reports "could not find page", this
is almost always why — not that the page is missing. Both tools say so in their output.

## What you get

| Tool | Notes |
|---|---|
| `notion_search` | Finds pages by title. Start here — the other tools need an id from it. |
| `notion_read_page` | Returns the page body as Markdown. |
| `notion_create_page` | Requires a `parent_page_id`: Notion gives integrations no workspace root to write into. |
| `notion_append_to_page` | Adds to the end of a page; never overwrites. |

Supported block types round-trip between Notion and Markdown: paragraphs, headings 1–3,
bulleted and numbered lists, to-dos (including checked state), quotes, code blocks and
dividers. Anything else is read as a `[unsupported block: …]` placeholder rather than
silently vanishing, and unrecognised Markdown is written as a plain paragraph so no content
is lost.

## Disconnecting

**Disconnect** on the card clears the stored token from the app. To revoke it properly, also
delete the integration at [notion.so/my-integrations](https://www.notion.so/my-integrations)
— Notion has no token-revocation endpoint for the app to call.

## Manual end-to-end check

1. Paste a deliberately wrong token → connecting fails and nothing is stored.
2. Paste the real token → the card names your workspace.
3. `notion_search` **before** sharing any page → empty, with the sharing guidance.
4. Share a page, then `notion_search` → the page appears with an id.
5. `notion_read_page` on that id → title, URL and Markdown body.
6. `notion_create_page` with that id as `parent_page_id` → the new page appears in Notion.
7. `notion_append_to_page` → the content is added at the end, with earlier content intact.
