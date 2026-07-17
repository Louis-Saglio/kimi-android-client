# Spike: document the server wire protocol

- **ID:** 001
- **Created:** 2026-07-17
- **Depends on:** none

## Context

The Android app must talk to the same backend as Kimi Code Web: the HTTP+WebSocket
server embedded in the `kimi` binary (locally `127.0.0.1:58627`, remotely
`http://100.64.0.1:80` via Caddy with HTTP basic auth, Tailscale only).
This API is internal and undocumented. A probe of the web bundle
(`/assets/index-e04Pgjfa.js`, 2026-07-17) revealed the surface: REST-ish endpoints
(`/sessions`, `/sessions/{id}/messages`, `/sessions/{id}/prompts`,
`/sessions/{id}:abort`, `/permission`, `/healthz`, `/meta`, …) plus a single
WebSocket carrying events (`message.created/updated`, `tool.call.delta`,
`tool.call.started`, `turn.started/ended`, `session.status_changed`, …).

Before any app code is written we need the exact request/response shapes for the
core loop only: auth check, list sessions, fetch messages, send prompt, abort,
permission flow, WS event envelope. Alternative considered and rejected:
reverse-engineering lazily inside each feature ticket — every ticket would stall
on protocol unknowns and produce inconsistent client code.

## Description

Produce `docs/server-api.md` at the project root, documented and verified against
the live server. Cover, in this order:

- **Base URL & auth:** how basic auth is passed, which lightweight endpoint
  confirms credentials are valid (candidates: `/healthz`, `/meta`, `/sessions`).
- **Sessions:** list (method, path, full response JSON with field names), create
  (payload — does it need a working directory?), single-session status.
- **Messages:** history endpoint for a session, message JSON shape (roles, parts,
  tool-call representation).
- **Prompts:** send-prompt request/response.
- **Abort:** the `:abort` endpoint, request/response.
- **Permissions:** how a permission request surfaces (WS event? message field?
  `/permission` endpoint?) and how to POST the decision back.
- **WebSocket:** URL, auth during handshake, event envelope format, and exact
  payloads for: streaming text deltas, tool call start/delta/result,
  `turn.started`/`turn.ended`, permission requested, session status changed.

Restrict the doc to core-loop endpoints. Explicitly skip `fs:*`, terminals, cron,
goals, workspaces, providers, skills.

## Acceptance criteria

- [ ] `docs/server-api.md` exists and covers all bullets above with real example
  requests and responses (passwords redacted).
- [ ] Every documented endpoint was verified against the live server; the exact
  `curl`/`websocat` commands used are included in the doc.
- [ ] The WS section comes from a captured real round-trip: send a prompt, record
  the ordered event sequence until `turn.ended`.
- [ ] No app code changed (documentation only).

## Out of scope

Anything beyond the core loop: file browser, terminals, cron/goals views,
workspaces, provider/model switching. App implementation (starts at 002/003).

## Notes

- The server runs locally on this VPS without Caddy in front — check whether
  `127.0.0.1:58627` bypasses basic auth; the remote path
  (`http://100.64.0.1`) definitely requires it.
- The web bundle served at `/assets/index-e04Pgjfa.js` is ground truth for how
  endpoints are constructed (base path, headers).
- `websocat` may need installing for WS capture; install into an isolated dir.
