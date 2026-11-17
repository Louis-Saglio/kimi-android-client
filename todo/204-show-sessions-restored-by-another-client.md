# Show sessions restored by another client

- **ID:** 204
- **Created:** 2026-11-17
- **Depends on:** none

## Context

Server 2.6.3 (nightly drift run, 2026-11-17, after the 2.6.x line was reinstated
from the 2026-11-13 withdrawal) broadcasts a global `event.session.restored`
when an archived session is restored. The upstream commit is `795a214d`, new
in 2.6.0; verified live on 2.6.3:

```json
{"type":"event.session.restored","session_id":"__global__","timestamp":"…",
 "payload":{"type":"event.session.restored","workspace_id":"wd_…",
            "agentId":"main","sessionId":"session_…"}}
```

The phone is not the only client: Louis also archives, restores and deletes
sessions from the web client and the CLI. `OkHttpSessionListSocket` already
consumes `event.session.archived` and `event.session.deleted` by removing the
row, and `event.session.created` by inserting one, but it drops
`event.session.restored`. `SessionListSocket.SessionListEvent` has no variant
for it. A restore made elsewhere therefore leaves the phone's list missing the
row until the next full refresh or the screen's next connect.

## Description

- Add `SessionListEvent.SessionRestored(sessionId)` to `SessionListSocket`.
- In `OkHttpSessionListSocket.handleFrame`, parse `event.session.restored` like
  the archived/deleted frames: the envelope `session_id` is `__global__`, the
  real id is the payload's camelCase `sessionId`.
- In `SessionListViewModel`, on `SessionRestored`:
  - if the id is already loaded, do nothing (its own undo path already
    reinserted it);
  - otherwise fetch `GET /api/v1/sessions/{id}` and insert the returned session
    at its sorted position, reusing the `event.session.created` insertion path;
    an error leaves the list unchanged.
- Keep the existing archive undo and archived-screen restore behavior: both
  already reinsert locally, so the new event must be a no-op for them.

## Acceptance criteria

- [ ] A fake `event.session.restored` for a session not in the list issues
      exactly one `GET /api/v1/sessions/{id}` and inserts the row.
- [ ] The same frame for a loaded session issues no request and leaves one row.
- [ ] The app's archive undo (or the archived screen's restore) followed by the
      server's restored frame still shows one row.
- [ ] A failed fetch leaves the list unchanged and does not crash.
- [ ] No source references `event.session.restored` outside the socket and the
      ViewModel handling added here.

## Out of scope

- Refetching the whole list on every restored frame.
- `event.workspace.*` handling.
- Restoring from the phone's own archived screen (already works).

## Notes

- `docs/server-api.md` → WebSocket → Global events and Sessions → Archive /
  Restore.
- The frame is global, so the session-list socket sees it without subscribing.
