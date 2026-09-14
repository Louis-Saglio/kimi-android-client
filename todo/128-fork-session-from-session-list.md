# Fork a session from the session list

- **ID:** 128
- **Created:** 2026-09-14
- **Depends on:** 127

## Context

Ticket 127 adds `SessionsRepository.forkSession` and the chat-screen entry
point. The web client also offers fork on every sidebar session row; this
ticket brings the same reach to the app's session list, so Louis can fork
a session he is not currently inside.

The list's only row affordances today are tap (open chat) and long-press
(`SessionFullNameDialog`, full selectable name, ticket 117). There is no
swipe or per-row overflow menu. Chosen UX: add a "Fork session" action to
the existing long-press dialog rather than inventing a new row component —
smallest change that reaches the goal, consistent with the current
long-press affordance.

Behavior decision (product): after forking from the list, STAY in the list
and show a snackbar — from the list Louis is browsing, not working, and
yanking him into the forked chat would be surprising. Contrast with the
chat-screen fork (127), which navigates into the fork. The fork inherits
the source's `updated_at` (see 127's Notes), so it should be inserted
sorted by `updated_at` — it lands adjacent to the source — NOT at the top
like create-session does.

## Description

**1. Dialog.** Add a "Fork session" button/action to
`SessionFullNameDialog` (ui/sessions). Disable it when the session
`isRunning` (same rule as the chat menu: running sessions always fail
with `40901`).

**2. ViewModel.** New `SessionListViewModel` intent
(`onForkSession(session)`): calls `SessionsRepository.forkSession`; on
success insert the row into BOTH the flat list and the matching workspace
group at its `updated_at` position (the create flow at
SessionListViewModel.kt:488-545 inserts at top — fork needs sorted
insert instead), then show a "Session forked" snackbar via the existing
effect channel. On failure, snackbar with the server message (covers
`40401` staleness and any unexpected busy race).

**3. Tests.** `SessionListViewModelTest`: success inserts the fork at the
sorted position in flat and grouped modes and emits the snackbar effect;
running session has the action disabled; repository failure emits the
error snackbar and leaves the list untouched.

## Acceptance criteria

- [ ] Long-press on a session row offers "Fork session"; it is disabled
      for running sessions.
- [ ] Forking an idle session inserts the fork into the visible list
      (flat and grouped modes) at its `updated_at` position, without a
      refresh, and shows a confirmation snackbar.
- [ ] The user remains in the session list (no navigation).
- [ ] Failure shows the server's error message in a snackbar.
- [ ] `SessionListViewModelTest` additions green; full
      `:app:testDebugUnitTest` run passes.

## Out of scope

- Navigating into the fork from the list (deliberate; see Context).
- A swipe action or per-row overflow menu — would be a broader row-actions
  redesign, not needed for one more action.
- Forking archived sessions (the list only shows live ones today).
- `event.session.created` cross-client sync (ticket 129) — the local
  insert here must still work when 129 lands; 129 owns the dedup.

## Notes

- Reuses `SessionsRepository.forkSession` and the wire captures from
  ticket 127 — no data-layer work expected beyond what 127 ships.
