# Session list: handle the global `event.session.deleted` frame

- **ID:** 126
- **Created:** 2026-09-09
- **Depends on:** 105

## Context

Server 0.42.0 (upstream `fa87d040`) exposed session deletion:
`POST /api/v1/sessions/{id}:delete`, and with it a new **global WS event
`event.session.deleted`** — same `__global__` envelope and camelCase
`sessionId` payload shape as the `event.session.archived` event ticket 105
already handles (docs/server-api.md, WebSocket → Global events, verbatim
capture inline). A session deleted from any other client (web UI, CLI)
now disappears server-side, but the app's session list keeps showing the
row until the next full refresh — the list socket parses only the archived
sibling and drops the deleted frame as unknown.

No delete UI is requested here; this ticket is only about not going stale
when someone else deletes.

## Description

**1. Event.** Add `SessionListEvent.SessionDeleted(sessionId)` next to
`SessionArchived` in `SessionListSocket`, and map
`"event.session.deleted"` to it in `OkHttpSessionListSocket.handleFrame`
— same placement as ticket 105's archived mapping: parsed ahead of the
unsubscribed-session filter (the envelope `session_id` is `__global__`),
payload DTO mirroring `SessionArchivedPayload` (`sessionId` camelCase +
`workspace_id`).

**2. Collector.** Handle the new event wherever `SessionArchived` is
consumed (the list cache/collector that drops the row live) — a deleted
session leaves the list exactly like an archived one.

**3. Tests.** Extend `OkHttpSessionListSocketTest` with the verbatim
0.42.0 capture from docs/server-api.md: the frame maps to
`SessionDeleted(sessionId)` despite the `__global__` envelope, and a
collector test that the row is dropped on the event.

## Acceptance criteria

- [ ] A `event.session.deleted` frame (verbatim docs capture) parsed by
      `OkHttpSessionListSocket` produces `SessionListEvent.SessionDeleted`
      with the payload's camelCase `sessionId` — unit test green.
- [ ] The session-list collector drops the deleted session's row live,
      without a manual refresh — unit test green.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      --max-workers=1 --no-daemon` passes with no behavior-test changes
      beyond the new coverage.
- [ ] The unknown-frame ignore path is unchanged: any OTHER unmapped
      frame type still falls through untouched.

## Out of scope

- Any delete UI (long-press → delete, confirmation sheet, undo) — a
  separate product decision; the wire route is documented now
  (`:delete`, docs/server-api.md → Sessions → Delete) for when it is
  ticketed.
- Handling deletions of the session the user is currently viewing
  (chat screen staleness after an external delete).
- `event.workspace.deleted` or any other unhandled global lifecycle
  event.

## Notes

- Verbatim wire capture (docs/server-api.md):
  `{"type":"event.session.deleted","seq":276,"session_id":"__global__",…,"payload":{"type":"event.session.deleted","workspace_id":"wd_…","agentId":"main","sessionId":"session_…"}}`
- The REST side (`POST /api/v1/sessions/{id}:delete` →
  `{"deleted":true}`; get → `40401`) is verified and documented — reuse
  when a delete feature is ticketed.
