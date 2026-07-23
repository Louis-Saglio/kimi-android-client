# refreshPendingQuestions can clobber a live question.requested event

- **ID:** 029
- **Created:** 2026-07-23
- **Depends on:** none

## Context

Found while de-flaking `ChatViewModelTest` (ticket 027), same shape as the
`seedWorkingState` race fixed in 028 — 027 explicitly asks for such races to be
raised as their own ticket instead of being fixed under the de-flake.

`ChatViewModel.onScreenStarted()` fires `refreshPendingQuestions()` alongside the
socket start. The poll GETs `/questions` and then applies
`pendingQuestions = questions` **unconditionally**. If a `question.requested`
frame arrives while the poll is in flight (a question created after the poll's
server-side snapshot), the stale empty response clears the card the event just
showed. It self-heals on the next re-derivation (reconnect, turn.ended), so the
user impact is a question card that vanishes right after appearing.

## Description

- In `ChatViewModel.refreshPendingQuestions()`, don't let a poll response
  overwrite questions an event already delivered. Options: a generation counter
  like the 028 fix (bump on `QuestionRequested`/`QuestionResolved`, capture at
  fire time, drop the response when it changed) — but note the reconnect and
  turn.ended paths *want* the poll to win, so the guard must only cover the
  screen-start fire, or the counter must be bumped by events only.
- Add a regression test: `question.requested` arrives before the poll response;
  the card stays up. The `statusResponseGate` pattern from 027/028 (park the
  response in the MockWebServer dispatcher) is the deterministic shape.

## Acceptance criteria

- [x] The race above cannot clear `pendingQuestions` (unit-tested). *(2026-07-23:
  `questions poll in flight does not clear a question that just arrived` — RED
  before the fix, GREEN after.)*
- [x] `./gradlew :app:testDebugUnitTest` green. *(2026-07-23: full suite green,
  then 6 consecutive `--rerun-tasks` runs green.)*

## Out of scope

- Rethinking which source (poll vs events) owns the question queue; the poll
  stays the source of truth on reconnect/turn.ended (docs/server-api.md).

## Notes

- 2026-07-23: went with the first option — `questionEventCount` bumps on
  question.requested/question.resolved, and only the screen-start fire (now
  `seedPendingQuestions()`) captures and checks it. The reconnect and turn.ended
  polls keep their unconditional poll-wins semantics: their snapshots are taken
  at the trigger point, and on reconnect a replayed event bumping the counter
  must not cancel the heal that covers gaps beyond the server's replay buffer.
  The regression test uses a `questionsResponseGate` mirroring the 028
  `statusResponseGate`.
