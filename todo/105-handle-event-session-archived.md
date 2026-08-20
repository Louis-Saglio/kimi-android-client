# Handle the event.session.archived global WS event

- **ID:** 105
- **Created:** 2026-08-20
- **Depends on:** none

## Context

Server 0.38.0 (upstream commit `15da8460`, "add workspace-grouped sessions
view and lifecycle events") added a global WS event `event.session.archived`
that fires whenever a session is archived — via `POST
/api/v2/sessions:archive` or any other client — on both the live and the cold
archive path. It is pushed to every established connection with no
subscription needed; the envelope `session_id` is the global watermark
`__global__` and the real session id rides in the payload as camelCase
`sessionId` (plus snake_case `workspace_id`). Verified live on 0.38.0
(docs/server-api.md, WebSocket → Global events).

The Android app ignores it today, so a session archived from the web client or
CLI stays visible in the app's session list as if live until the next manual
refresh/reconnect heal. Two code-level blockers, both in the session-list
socket (`OkHttpSessionListSocket`):

1. The frame type is unknown, so it falls into the `else -> Log.d(...)` ignore
   branch.
2. Even if parsed, the unsubscribed-session filter
   (`frame.sessionId !in subscribed`) would drop it, because the envelope
   carries `__global__`, not the archived session's id.

No alternative was considered worth it: polling for archives is exactly what
the event exists to avoid, and the event is one small frame.

## Description

Handle `event.session.archived` in `OkHttpSessionListSocket`:

- Add a `SessionListEvent.SessionArchived(val sessionId: String)` variant.
- Parse the frame before (or around) the unsubscribed-session filter: match on
  `type == "event.session.archived"` and read the real id from
  `payload.sessionId` (camelCase — NOT the envelope `session_id`, which is
  `__global__`). Decode payload as `{"workspace_id": String, "sessionId":
  String}` (`agentId` also present, ignored).
- In the session-list ViewModel/repository, remove the row for that id (or
  mark it archived if the list has an archived filter — follow the existing
  list-state shape; simplest is removal from the loaded list).

## Acceptance criteria

- [ ] Unit test: feeding a verbatim 0.38.0 `event.session.archived` frame
  (from docs/server-api.md) through `OkHttpSessionListSocket`'s frame handling
  emits `SessionListEvent.SessionArchived("<real session id>")` — including
  when the envelope `session_id` is `__global__` and the id is not in the
  subscribed set.
- [ ] Unit test: the session list drops the archived session's row on
  `SessionArchived`.
- [ ] Manual check: archive a session from the web client while the app's
  session list is open; the row disappears without a pull-to-refresh.

## Out of scope

- The other new 0.38.0 lifecycle events (`event.workspace.created/updated/deleted`)
  — the app has no workspace UI.
- Any archive/restore UI in the app (archiving sessions from the phone).
- Switching the list to `GET /api/v2/sessions?view=by_workspace` — the v1 list
  still works and v2 remains doc-only for the app.
- Chat-screen handling of the event (an archived open session is an edge case;
  the list coverage is enough).

## Notes

- Wire capture and envelope details: docs/server-api.md, "Global events —
  `event.session.archived` (new in 0.38.0)".
- Observed live: the frame reaches connections that have sent at least one
  inbound frame; the list socket always subscribes, so it qualifies.
- There is no session-deleted event (upstream doc); archives are the only
  removal signal.
