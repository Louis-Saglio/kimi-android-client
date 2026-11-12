# Answer the bare WebSocket ping on both sockets

- **ID:** 186
- **Created:** 2026-11-12
- **Depends on:** none

## Context

Server 2.6.0 (nightly drift run, 2026-11-12) made the WebSocket heartbeat a
bare ping/pong pair: the server sends `{"type":"ping"}` every 10 seconds and
expects `{"type":"pong"}`. It no longer sends a `nonce` and no longer sends
`server_hello` with `heartbeat_ms`; a connection that sends nothing for 20
seconds is closed with code `1001`.

Both of the app's sockets still require a nonce before answering a ping
(`SessionSocket.handleFrame` and `OkHttpSessionListSocket.handleFrame`), so
they never send a pong. The server therefore drops them every 20 seconds. The
chat reconnects with backoff (masking the problem as churn), and the session
list silently stops updating until its next reconnect.

This blocks every other socket ticket: a live test cannot stay connected long
enough to observe a turn without it.

## Description

In `SessionSocket` and `OkHttpSessionListSocket`:

- answer every `{"type":"ping"}` with `{"type":"pong"}` (no payload);
- delete the nonce parsing and the `pongFrame(nonce)` helper;
- delete the `server_hello` case, `ServerHelloPayload`, and the
  `SocketStallWatchdog.onServerHello` call site; the watchdog keeps its default
  10-second heartbeat, so its 3× threshold stays 30 seconds;
- update the comments that describe the heartbeat.

The protocol-level OkHttp ping (`pingInterval(20s)`) can stay: it is a
transport keepalive, not the server's liveness signal.

## Acceptance criteria

- [ ] A `SessionSocketTest` case feeds `{"type":"ping"}` and asserts the fake
      WebSocket received exactly `{"type":"pong"}`.
- [ ] The same case on the session-list socket
      (`OkHttpSessionListSocketTest`) passes.
- [ ] No source or test references `server_hello`, `heartbeat_ms` or a ping
      `nonce` (`grep` clean).
- [ ] A live socket answers ten consecutive pings and stays open past 30
      seconds against server 2.6.0.

## Out of scope

- Migrating the command framing (`subscribe_v2` → `subscribe`, `ack` →
  `response`): ticket 187.
- Any change to the stall watchdog's thresholds or to OkHttp's ping interval.

## Notes

- `docs/server-api.md` → WebSocket → Heartbeat (2.6.0).
- `SocketStallWatchdog.DEFAULT_HEARTBEAT_MS` is already 10 000, so removing
  `onServerHello` changes nothing at runtime.
