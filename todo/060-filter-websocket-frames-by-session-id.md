# Filter WebSocket event frames by session_id

- **ID:** 060
- **Created:** 2026-07-31
- **Depends on:** none

## Context

Found in the 2026-07-31 tech-debt audit. `docs/server-api.md` documents an
observed 0.31.0 caveat: an authenticated WS connection receives events for
sessions it **never subscribed to**, and clients **MUST** filter incoming
events on `session_id`. The client doesn't: `WsFrame` (`ChatDtos.kt:137-146`)
declares only `type/seq/epoch/volatile/payload`, and `SessionSocket.handleFrame`
applies every parsed event to the subscribed session's stream.

Louis runs multiple concurrent agent sessions on the server, so another
session's `assistant.delta`/`tool.result` can render in the open chat, and a
foreign `turn.ended` would falsely clear `isWorking`. The event envelope
carries `session_id` at the top level (doc section "Event envelope"), so the
fix is additive: one field + one guard.

Alternatives considered: filtering on the payload-side `sessionId` instead —
rejected, not every payload carries it and the envelope field is the documented
one. Failing closed on absent `session_id` — rejected, the doc only guarantees
the field on *event* frames; control frames (ack, server_hello, standalone
`resync_required`) must pass, and doc drift shouldn't brick the socket.

## Description

- Add `@SerialName("session_id") val sessionId: String? = null` to `WsFrame`
  in `app/src/main/java/fr/louissaglio/kimi/android/data/chat/ChatDtos.kt`.
- In `SessionSocket.handleFrame` (`SessionSocket.kt:187`), after parsing:
  if `frame.sessionId != null && frame.sessionId != sessionId`, `Log.w` and
  drop the frame. Frames with a matching or absent `session_id` pass
  (fail-open, per the rationale above).
- Regression test: interleave frames for two session ids on one socket; only
  the subscribed session's events reach the collector, and the foreign frame
  is logged. MockWebServer supports WebSocket upgrades (see
  `ChatRepositoryTest` for the harness pattern); if that harness is too heavy,
  make the frame-dispatch seam `internal` and test it directly — the
  implementer chooses, but the foreign-frame path must be covered.

## Acceptance criteria

- [ ] Unit test: a frame carrying a foreign `session_id` is dropped and
  logged; frames with matching or absent `session_id` are delivered.
- [ ] `./gradlew :app:testDebugUnitTest` green.

## Out of scope

- The sub-agent `agentId` tagging/filtering (ticket 045 machinery) — already
  handled on the payload side.
- Server-side fix (the unsolicited frames are a server quirk; the doc
  mandates client-side filtering).

## Notes

- Doc references: "Caveat — unsolicited frames" (~line 1466) and "Event
  envelope" (~line 1476) in `docs/server-api.md`.
