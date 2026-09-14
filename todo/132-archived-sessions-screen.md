# Archived sessions screen

- **ID:** 132
- **Created:** 2026-09-14
- **Depends on:** 131

## Context

Ticket 131 lets Louis archive a session from the phone, with a 5-second Undo
snackbar as the only way back. That is not enough: undo expires, and the 20
sessions already archived from the web client are invisible in the app. The
web client solves this with Settings → Archived tab (browse, search, filter
by workspace, sort, per-row Restore). This ticket is the phone-sized version:
a dedicated read-only list with a Restore action per row.

UX decisions from the product discussion (Louis confirmed 2026-09-14):

- Entry point: a MoreVert overflow menu on the session list top bar with a
  single "Archived sessions" entry. Settings stays about connection and
  server config; session management lives next to the session list.
- Tapping an archived row does nothing. Opening an archived session's chat
  is deferred — the app has no handling for an archived open session and the
  server-side behavior (prompting an archived session) is unverified.
- Restore pushes no WS event (verified 2026-09-14), so this screen cannot
  live-update when another client restores a session; pull-to-refresh is the
  heal path.

Wire facts (verified live on 0.42.0, 2026-09-14):

- `GET /api/v1/sessions?archived_only=true` returns archived sessions, each
  with `archived:true` and `archived_at` (ISO). Combine with
  `exclude_empty=true` to skip prompt-less sessions, as the web client does.
- Order is the default list order (`updated_at` desc) and archiving does not
  bump `updated_at`, so a stale session archived today sorts low. Accepted
  compromise: page in server order and show the archive time on each row,
  rather than re-sorting client-side across pages.

## Description

1. New navigation destination `archived` following the existing route pattern
   (chat/settings). Entry: the session list top bar gains a MoreVert icon
   with an overflow menu holding "Archived sessions".
2. `SessionsRepository.listArchivedSessions(beforeId, pageSize)`: v1 list
   with `archived_only=true&exclude_empty=true` and the existing cursor
   pagination (`before_id`, `has_more`). Uses the `archived`/`archived_at`
   DTO fields from ticket 131. No disk cache — the list is transient.
3. New `ArchivedSessionsScreen` + `ArchivedSessionsViewModel`:
   - App bar: back arrow, title "Archived sessions".
   - Rows: title (same fallback as the main list), workspace folder basename
     from `metadata.cwd`, "Archived <time>" from `archived_at` (fall back to
     `updated_at` if absent). Trailing Restore action (undo icon button).
     Row tap and long-press do nothing.
   - Flat `LazyColumn` with the same paging footer pattern as the main list
     (loading spinner, error + retry), an empty state ("No archived
     sessions"), and pull-to-refresh if the shared component makes it cheap.
4. Restore: `restoreSession` (ticket 131). On success the row leaves the
   archived list and a plain "Session restored" snackbar shows (no undo —
   re-archiving is one gesture away in the main list). On failure the row
   stays and the error snackbar shows via `toUserMessage()`.
5. Main-list heal on return: the list socket closes when the archived screen
   covers the list (ON_STOP) and `silentRefresh()` runs on reconnect, which
   picks up restored sessions. Verify this works over Navigation-Compose; if
   the list destination never drops to STOPPED, add an explicit refresh on
   return (e.g. a nav-result flag the list ViewModel turns into a
   `silentRefresh()`).

## Acceptance criteria

- [ ] MockWebServer test: the request carries `archived_only=true` and
  `exclude_empty=true`; `archived_at` is parsed.
- [ ] ViewModel test: first page maps rows (title fallback, cwd basename,
  archive time); `has_more` drives the footer; pagination appends.
- [ ] ViewModel test: restore success removes the row and emits the snackbar
  effect; restore failure keeps the row and emits the error effect.
- [ ] Manual: archive two sessions from the app, open Archived sessions from
  the list overflow, both appear with workspace folder and archive time;
  restore one and it leaves the list; back in the main list the restored
  session is visible without pull-to-refresh (or the explicit-refresh
  fallback is in place and noted in code).

## Out of scope

- Search, workspace filter, and sort controls (the web client has them; a
  20-row list on a phone does not need them).
- Opening an archived session's chat (needs archived-session handling in the
  chat screen first).
- Multi-select / batch restore.
- Grouping by workspace, disk caching, live updates from other clients.
- Delete from the archived list (not planned).

## Notes

- Web reference: Settings → Archived tab in the 0.42.0 bundle
  (`archivedTitle`, `archivedRestore`, "Browse and restore archived
  sessions").
- `docs/server-api.md` covers `archived_only`/`exclude_empty` under
  Sessions → List; extend it only if the probes above prove incomplete.
- Keep the screen consistent with the main list's components
  (`SessionsScreen.kt` row/footer/empty-state idioms) rather than inventing
  new ones.
