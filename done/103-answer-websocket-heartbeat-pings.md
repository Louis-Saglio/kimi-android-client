# Answer the WebSocket heartbeat pings so idle sockets stop reconnecting every 20 s

- **ID:** 103
- **Created:** 2026-08-12
- **Depends on:** none

## Context

Server 0.35.0 reintroduced the WebSocket application-level heartbeat
(upstream commit `619564dc`, "add WebSocket heartbeat to survive proxy idle
timeouts"; docs/server-api.md, WebSocket → Heartbeat):

- `server_hello` now always carries `heartbeat_ms: 10000`.
- The server sends `{"type":"ping","payload":{"nonce":"<ulid>"}}` every 10 s.
- After ~2 cycles of total client silence (~20 s) it closes the socket with
  code `1001`, reason `"heartbeat timeout"`. **Any inbound frame resets the
  liveness timer** — answering with
  `{"type":"pong","payload":{"nonce":<echo>}}` keeps an idle socket alive.

From 0.29.0 to 0.34.0 the server never initiated a disconnect, so the app's
sockets only ever sent control frames (subscribe/unsubscribe) and stayed open
while idle. Both sockets — `SessionSocket.kt` (chat) and
`OkHttpSessionListSocket.kt` (session list) — send **nothing** while the user
just watches a screen, so on 0.35.0 every idle socket is force-closed ~20 s
after its last outbound frame and the reconnect loop (1 s → 30 s backoff,
cursor resume) fires all day: replay traffic on every resume, a
`ConnectionLost`/reconnecting flicker in the UI, and extra battery drain.
The `ping` frames themselves are harmless — they fall into the unknown-type
ignore in both sockets.

## Description

In both WebSocket listeners (`SessionSocket.kt` and
`OkHttpSessionListSocket.kt`), handle inbound `"ping"` frames by sending back
`{"type":"pong","payload":{"nonce":<echoed nonce>}}` over the same socket.
Malformed `ping` payloads (missing `nonce`) keep the current ignore-and-log
behavior. No DTO, reconnect-loop, or UI changes — the heartbeat answer rides
the existing send path.

## Acceptance criteria

- [ ] Unit test: an inbound `{"type":"ping","payload":{"nonce":"abc"}}` frame
      makes the socket send exactly one
      `{"type":"pong","payload":{"nonce":"abc"}}` frame (both sockets).
- [ ] Unit test: a `ping` without a decodable `nonce` is ignored (logged, no
      outbound frame, no crash) — both sockets.
- [ ] Existing socket tests (frame dispatch, reconnect/backoff, cursor
      resume) pass unchanged.
- [ ] Manual verification note: against server ≥ 0.35.0, an idle chat screen
      no longer logs `1001`/`heartbeat timeout` closes.

## Out of scope

- Reading `heartbeat_ms` from `server_hello` or scheduling client-initiated
  pings — answering server pings is sufficient (any inbound frame resets the
  timer, and the server pings every 10 s).
- Changing the reconnect/backoff loop or the cursor-resume logic.
- OkHttp protocol-level ping/pong (unrelated to this app-level heartbeat).
- Any REST-side change — the heartbeat is WebSocket-only.
