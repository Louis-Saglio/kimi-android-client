# Move the chat socket to `subscribe` and `response`

- **ID:** 187
- **Created:** 2026-11-12
- **Depends on:** 186

## Context

Server 2.6.0 (nightly drift run, 2026-11-12) removed the WebSocket handshake
and every ack (commit `725194d7`). `subscribe_v2` no longer exists: the server
treats it as an unknown frame and ignores it, so the app's chat socket receives
no session frames at all. `server_hello` and `ack` are also gone.

The replacement is a flat command: `{"type":"subscribe","request_id":…,
"session_id":…,"agent_ids":[…]}`. The server answers with one
`{"type":"response","request_id":…,"code":…}`. An unknown session answers
`40401`; a malformed subscribe answers `40001`; a command without a readable
`request_id` closes the socket with `1008`.

## Description

In `SessionSocket`:

- send one flat `subscribe` per connection with a fresh `request_id`, a
  `session_id`, and `agent_ids:["main"]` (children are a later ticket);
- match the `response` frame by `request_id` instead of the old `ack`/`id`
  pair; `code:0` means subscribed, `40401` ends the flow with
  `DataError.SessionNotFound`, any other code logs and ends the attempt;
- delete the `ack` case, `AckPayload`, the `subscribe_v2` builder, the
  `transcript` grade map and the `transcript_since` payload;
- keep the reconnect loop: every new connection sends a new subscribe with a
  new `request_id` and re-emits `SocketEvent.Reconnected`;
- keep the `response` handling used by task controls (ticket 179) — the frame
  now serves both commands, so route replies by `request_id` in one place.

`agent_ids` should be built from a parameter (default `["main"]`) so the
sub-agent work (ticket 192) can reuse the same builder.

## Acceptance criteria

- [ ] The first frame on the wire is exactly
      `{"type":"subscribe","request_id":…,"session_id":<id>,"agent_ids":["main"]}`.
- [ ] A fake `response` with `code:0` and matching `request_id` marks the
      connection subscribed; no `ack` frame is read.
- [ ] A `response` with `code:40401` emits `SocketEvent.Failed(SessionNotFound)`
      and stops the flow.
- [ ] A `response` with `code:40001` logs and ends the attempt without crashing.
- [ ] No source or test references `subscribe_v2`, `unsubscribe_v2`,
      `server_hello`, `AckPayload` or `transcript_since`.
- [ ] Live against 2.6.0: the socket receives the state frames and `turns`
      after the response.

## Out of scope

- Interpreting the conversation frames that follow (ticket 188).
- Task-control frame changes: their flat shape and `response` handling are
  unchanged.
- Heartbeat behaviour (ticket 186).

## Notes

- `docs/server-api.md` → WebSocket → Commands and responses / Subscribing to a
  session.
