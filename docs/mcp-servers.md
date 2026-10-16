# MCP servers

Gotcha can use the tools of any [Model Context Protocol](https://modelcontextprotocol.io)
server you add, as long as the server speaks the **Streamable HTTP** transport. You add a
server with its URL and, if it needs one, a token; Gotcha lists the server's tools and the
assistant can call them while that server is switched on.

## Add a server

**Drawer menu → Connectors → MCP servers → Add server**, then fill in:

| Field | What to put |
|---|---|
| Name | Anything. It labels the server's tools for the assistant (`mcp_<name>__<tool>`). |
| Server URL | The server's Streamable HTTP endpoint, often ending in `/mcp`. |
| Bearer token | Optional. Sent as `Authorization: Bearer <token>`. |
| Extra headers | Optional, one per line as `Name: value`, for servers that want an API-key header instead. |

**Test connection** contacts the server and shows its name and how many tools it offers, or
why it failed. **Save** tests again and stores the server only if it answers. The token and
headers are kept in Gotcha's encrypted connector store on the phone.

Plain `http://` works for servers on your own network: `localhost`, `.local` and `.lan`
names, and private, link-local and Tailscale (100.64.0.0/10) addresses. A token or header
sent over `http://` to anywhere else could be read on the way, so Gotcha asks for `https://`
there.

## What the assistant gets

- Each tool appears as `mcp_<server>__<tool>`, with its description prefixed by the server's
  name. Two servers with the same tool names do not clash.
- Tools the server marks `readOnlyHint` can be used in **Monitor**. Every other tool is
  **Operator** only, because MCP gives no other way to tell what a tool does.
- At most 64 tools per server are offered, so one large server cannot fill the prompt.
- Images and audio in a tool's result are named, not passed on; text, embedded text
  resources and structured content are.

**Trust:** a server's tool descriptions and results reach the assistant as they are, and can
steer what it does next. Add only servers you trust, and prefer read-only tools in Monitor
when you are unsure.

## Switching off and removing

Each server has its own switch: off, its tools are withheld from the assistant but the server
stays saved. The **MCP servers** switch at the top turns all of them off at once.
**Remove** (tap twice) deletes the server and its stored token.

**Refresh tools** asks the server for its tool list again. The Connectors page's auto-sync
and **Refresh all** do the same for every server that is on. A failed refresh keeps the last
known tools and shows the error under the server.

## Not supported yet

- Servers started as local processes (stdio). Android can't run them for Gotcha.
- The older HTTP+SSE transport (MCP 2024-11-05). Gotcha recognises it and says so; use the
  server's Streamable HTTP endpoint if it has one.
- OAuth sign-in. Use a token or header the server issues instead.
- Resources, prompts and sampling: only tools are used.

## Errors you may see

| Message | What to check |
|---|---|
| The server refused the token or headers (HTTP 401/403) | The token, or the header name and value. |
| Nothing answered at this URL (HTTP 404) | The path: many servers serve MCP at `/mcp`. |
| This server uses the older HTTP+SSE transport | Look for a Streamable HTTP endpoint in the server's docs. |
| The server answered, but not as an MCP server | The URL points at a web page or another API. |
| Couldn't reach the server | The address, the network, and whether the server is running. |

Home Assistant has its own connector card, which needs no URL path and adds Home Assistant's
own hints; it uses the same tool gating.
