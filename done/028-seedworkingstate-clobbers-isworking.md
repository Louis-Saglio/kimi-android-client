# seedWorkingState can clobber a live turn's isWorking

- **ID:** 028
- **Created:** 2026-07-23
- **Depends on:** none

## Context

Found while landing the ticket-026 follow-up (top-bar title seeding): the
known-flaky `ChatViewModelTest.steered message echo appears from the socket
while the turn continues` (see 027) failed at
`assertTrue(viewModel.uiState.value.isWorking)`. The flake hides a real race
in production code — 027 explicitly asks for such races to be raised as their
own ticket.

`ChatViewModel.onScreenStarted()` fires `seedWorkingState()` alongside the
socket start. The seed GETs `/status` and then applies `isWorking = running`
**unconditionally**. If a `turn.started` frame arrives while the status call
is in flight (the common case: opening a busy session), the stale
`busy: false`-style response — or any late response — overwrites the
newer, authoritative event state.

## Description

- In `ChatViewModel.seedWorkingState()`, don't let the seed response turn off
  a running state that an event already established — e.g. only apply
  `isWorking = running` when no turn.started has landed since the seed was
  fired (simplest correct shape: skip the update when `isWorking` is already
  true; verify against the abort path before settling on the guard).
- Add a regression test: turn.started arrives before the status response;
  isWorking stays true.

## Acceptance criteria

- [x] The race above cannot clear `isWorking` (unit-tested). *(2026-07-23:
  `status seed in flight does not clear a turn that just started` — RED before
  the fix, GREEN after.)*
- [x] `./gradlew :app:testDebugUnitTest` green. *(2026-07-23: full suite green,
  then 6 consecutive `--rerun-tasks` runs green under ticket 027.)*

## Out of scope

The broader test-suite de-flake (027: virtual time, latch-based waits).

## Notes

- 2026-07-23: settled on a generation counter instead of the plain
  `isWorking == true` guard — verifying against the abort path showed the guard
  only protects one direction (a stale `busy: true` landing after a quick
  abort's turn.ended would wrongly re-light the indicator). `turnEventCount`
  bumps on turn.started and turn.ended; the seed captures it at fire time and
  drops the response when it changed. The regression test parks the `/status`
  response in the MockWebServer dispatcher (`statusResponseGate`) so the event
  deterministically wins.
