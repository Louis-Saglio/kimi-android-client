# Read context fill from the `context` frame

- **ID:** 210
- **Created:** 2026-11-19
- **Depends on:** none

## Context

Server 2.7.0 (nightly drift run, 2026-11-19; commit `2f33641b`) renamed the
per-agent context frame from `usage` to `context` (verified live). The payload
is unchanged: `{type:"context", session_id, agent_id, event_created_at,
context_tokens, max_context_tokens?}`. The frame is sent on subscribe and
coalesced while it changes.

`SessionSocket.handleFrame` matches `"usage"` and decodes `UsageFrame`, so on
2.7.0 the chat's context ring never receives a value: it stays at its initial
state for the whole session.

## Description

- Match `"context"` instead of `"usage"` in `SessionSocket`.
- Rename the DTO `UsageFrame` → `ContextFrame` (same fields) and the
  `ChatEvent`/ViewModel path that consumes it if it is named after `usage`;
  keep the UI model (`contextTokens`/`maxContextTokens`) unchanged.
- Ignore a stray `usage` frame (it no longer exists) without logging it as an
  error.
- Update the socket unit tests to feed a `context` frame.

## Acceptance criteria

- [ ] A fake `{"type":"context",…,"context_tokens":12345,
      "max_context_tokens":1000000}` updates the chat's context ring to
      12345/1000000.
- [ ] A `context` frame without `max_context_tokens` (or with `0`) leaves the
      ring's "unknown limit" state, as today.
- [ ] A `usage` frame changes nothing and does not crash.
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- The `agent` frame's new `status:"ended"` value (the app treats any
  non-`running` status as not busy; no change needed).
- The `context` frame on the session-list socket (it is not subscribed).

## Notes

- `docs/server-api.md` → WebSocket → Session frames (`context`).
- Ticket 177 introduced the `usage` decoder; this is its 2.7.0 rename.
