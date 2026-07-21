# De-flake ChatViewModel timing-sensitive unit tests

- **ID:** 027
- **Created:** 2026-07-21
- **Depends on:** none

## Context

Found while verifying ticket 020: `ChatViewModelTest` fails intermittently on
this VPS with a *different* test each run. Evidence: on a clean checkout of
HEAD (pre-020), 2 of 6 full-suite runs failed — `submit is a no-op while an
item is unanswered` and `failed answer post keeps the card and emits
QuestionFailed`; the 020 runs additionally saw `steered message echo appears
from the socket while the turn continues` and `failed abort emits AbortFailed
and keeps the working state`. All four mix `runBlocking` with real-time waits
(`delay(200)`, MockWebServer `takeRequest(300, MILLISECONDS)` polling), so
they race the machine's thread scheduling — a shared-CPU VPS makes it visible.

## Description

- Make the timing-sensitive `ChatViewModelTest` cases deterministic: drive
  coroutine time with `runTest`/virtual time where the code under test allows,
  and replace fixed `delay(...)`/`takeRequest(...)` margins with state
  assertions or latches (`awaitState` already exists and is the right shape).
- Scope: the abort/steer/question tests named above plus any sibling that
  fails under repetition (`--rerun-tasks` x6 is the reproduction harness).

## Acceptance criteria

- [ ] 6 consecutive `./gradlew :app:testDebugUnitTest --rerun-tasks` runs on
  this VPS are green.

## Out of scope

Changing production behavior of `ChatViewModel`/`ChatRepository` — if a flake
turns out to hide a real race in production code, raise it as its own ticket.

## Notes

- The `android-testing` skill documents the two-schedulers trap
  (`StandardTestDispatcher` default vs `Main` dispatcher); check it before
  editing.
