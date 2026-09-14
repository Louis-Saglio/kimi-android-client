# Archive a session from the session list

- **ID:** 131
- **Created:** 2026-09-14
- **Depends on:** none

## Context

The session list is long (541 sessions on Louis' server, 20 of them already
archived from other clients). Today Louis cannot archive from the phone; he
must open the web client to clean up. The server has single-session
archive/restore endpoints that the 0.42.0 web bundle uses, verified live on
0.42.0 (2026-09-14):

- `POST /api/v1/sessions/{id}:archive` → `{"code":0,"data":{"archived":true}}`
- `POST /api/v1/sessions/{id}:restore` → full session object (standard v1
  session envelope, `archived:false`)

They are not yet documented in `docs/server-api.md` (only the v2 batch
variants are); this ticket adds them. Related wire facts, all verified live:

- Archive pushes the global `event.session.archived` WS frame (handled since
  ticket 105: the list socket parses it and the list drops the row).
- **Restore pushes no WS event at all** — no `event.session.restored`, no
  `event.session.updated`. Other clients learn about restores by refetching.
- Archiving never bumps `updated_at` (upstream commit `43c68f58`).
- The server accepted archive in every probed state (idle, just-prompted).
  There is no busy guard server-side and the web UI has none, so the app must
  not disable the action on running sessions (unlike fork's 40901 rule).

UX decisions from the product discussion (Louis confirmed 2026-09-14):

- Entry point is the existing long-press `SessionFullNameDialog`, matching the
  ticket-128 decision for Fork. No swipe gesture, no per-row overflow.
- No confirmation dialog: archive is fully reversible, and the web client
  confirms nothing either. Instead a snackbar with an **Undo** action — this
  sets the app's first undo precedent (delete will reuse it later).
- The web client references: row context menu "Archive", toast with Undo,
  Settings → Archived tab for browsing/restoring (that screen is ticket 132).

## Description

1. `SessionsRepository` gains `archiveSession(sessionId)` and
   `restoreSession(sessionId)`, both through `ApiClient` per
   docs/conventions.md, returning `Result`. Archive maps the
   `{"archived":true}` payload; restore decodes the full session envelope.
2. `SessionDto` gains `archived: Boolean = false` and
   `archived_at: String? = null` (unknown keys are already ignored; the
   fields are needed for reinsertion and for ticket 132). Mirror onto the
   domain `Session` if the ViewModel needs it.
3. The long-press dialog on `SessionRow` (currently `SessionFullNameDialog`
   in SessionsScreen.kt) gains an "Archive" action row with an archive icon.
   Ticket 128 adds "Fork session" to the same dialog; whichever ticket lands
   second extends the other's component instead of replacing it.
4. `SessionListViewModel` gains `onArchiveSession(sessionId)`:
   - On success: remove the row from flat `sessions` AND from its workspace
     group (same dual-mode removal as the `SessionArchived` socket handling;
     extract a shared helper if that avoids duplication), remembering the
     removed session and its positions (flat index, group id, group index)
     for undo. Emit an Effect that shows the "Session archived" snackbar with
     an Undo action.
   - On failure: keep the row, emit the usual error-snackbar Effect via
     `Throwable.toUserMessage()`.
5. Undo (`SnackbarResult.ActionPerformed` → `onUndoArchive`): call
   `restoreSession`, then reinsert the row at its remembered positions
   (clamped to current list sizes; skip if a row with that id already exists
   — the socket may have beaten us). Restore failure shows the error
   snackbar and leaves the session archived.
6. Update `docs/server-api.md`: new Sessions subsection for the single
   `:archive`/`:restore` endpoints with the verbatim captures above, and a
   note in WebSocket → Global events that restore fires no event (verified
   2026-09-14 on 0.42.0).

## Acceptance criteria

- [ ] MockWebServer test: `archiveSession` POSTs `/api/v1/sessions/{id}:archive`,
  maps success and a non-zero envelope code to `Result`/`DataError.Api`.
- [ ] MockWebServer test: `restoreSession` POSTs `...:restore` and decodes the
  full session envelope.
- [ ] ViewModel test: successful archive removes the row from both the flat
  list and its workspace group, and emits the undo snackbar effect.
- [ ] ViewModel test: failed archive keeps the row and emits the error effect.
- [ ] ViewModel test: undo reinserts the row once (no duplicate if the id is
  already present); failed restore emits the error effect.
- [ ] `docs/server-api.md` documents both endpoints and the no-restore-event
  fact.
- [ ] Manual: archive a session from the app's list; it vanishes, the web
  client's Archived tab shows it; Undo brings the row back in place.

## Out of scope

- The Archived sessions screen and browsing/restoring from it (ticket 132).
- Archiving from the chat screen overflow menu (deferred; archiving the open
  session raises navigation questions).
- Batch archive / multi-select (the web client's admin table covers bulk
  cleanup).
- Handling a restore pushed by another client: no such event exists, the
  list heals on refresh/reconnect.
- Delete-session UI (events are ticket 126; UI is not planned).

## Notes

- `SessionListViewModel.kt` handles `SessionListEvent.SessionArchived` around
  line 223 — reuse that removal path.
- Reinsertion sorting: `updated_at` is stable across archive/restore, so the
  remembered position (or the sorted-insert precedent from ticket 128) is
  correct either way.
- The snackbar host already lives in `SessionsContent`; existing effects are
  message-only, so the undo effect needs to carry the session id and trigger
  `SnackbarHostState.showSnackbar(message, actionLabel)` with a result
  callback.
