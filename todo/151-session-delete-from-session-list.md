# Session delete from the session list

- **ID:** 151
- **Created:** 2026-09-23
- **Depends on:** none

## Context

Server 0.42.0 added `POST /api/v1/sessions/{id}:delete` (body `{}` →
`{"deleted":true}`; unknown id `40401`; cleanup serialized server-side;
verified live 2026-09-09, docs/server-api.md:1801-1814). Ticket 126
(todo) handles the global `event.session.deleted` frame so the list does
not go stale when *someone else* deletes — and explicitly scopes the UI
out: "No delete UI is requested here". With 500+ sessions accumulated,
deletion is a real housekeeping need on the phone.

## Description

**1. Data layer.** `SessionsApi.delete(id): DeleteResponse` next to the
archive route (131); repository method returns `DataError` per
conventions.

**2. UI.** The same row affordance that hosts Archive (131) gains
"Delete": confirmation dialog with destructive styling ("Delete session
…? This cannot be undone.") → on confirm, call the route → on success
drop the row locally (same local-removal path a 126 event would use); on
`40401` drop the row anyway (already gone) with no error toast; other
errors keep the row and toast.

**3. Tests.** Repository success/`40401`/generic-error mapping; ViewModel
row-drop test; androidTest: dialog → confirm removes the row, cancel
leaves it.

## Acceptance criteria

- [ ] Unit + androidTests above pass; existing suite green.
- [ ] On device: deleting a session removes it from the list and it stays
      gone after a full refresh (and after `GET` on it 404s).
- [ ] Cancel in the dialog changes nothing.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- Handling `event.session.deleted` from other clients — ticket 126.
- Multi-select / batch delete (v2 `:archive`-style batch delete does not
  exist for `:delete`; rejected in the drift review).
- Any undo affordance (the server has none).

## Notes

- Deleting a running session drains its background work server-side
  first; the call may take a moment — show the row-level progress the
  archive flow already uses.
