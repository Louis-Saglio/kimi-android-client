# Unify the seed-vs-event generation guards

- **ID:** 066
- **Created:** 2026-07-31
- **Depends on:** none

## Context

Tickets 028 and 029 fixed the same bug class — a REST seed response racing
the event stream clobbers newer event state — with two hand-rolled
generation counters in `ChatViewModel`: `turnEventCount` (guards the
`isWorking` seed) and `questionEventCount` (guards the `pendingQuestions`
seed). The pattern is right; the debt is that each seeded field re-invents
it, and the next one (session title, context usage) would need a third
hand-rolled counter with the same subtle semantics rediscovered.

Semantics that must survive (from 028/029, hard-won):

- The guard only covers the **screen-start** seeds. The reconnect and
  turn.ended polls keep unconditional poll-wins behavior: their snapshots
  are taken at the trigger point, and on reconnect a replayed event bumping
  the counter must not cancel the heal that covers gaps beyond the server's
  replay buffer.
- The counter must bump on *both* edges of the tracked state (turn.started
  **and** turn.ended) — the 028 notes show the one-directional guard only
  protects one direction.

## Description

- Introduce a small reusable guard (e.g. a `SeedGuard` with `capture()` at
  fire time and `isStale(captured)` when applying the response; bump methods
  called from the event dispatcher) and migrate both counters to it.
- Fire sites and bump sites stay exactly where they are semantically — this
  is a mechanism extraction, not a behavior change.
- Direct unit tests for the guard (stale/warm/one-direction cases);
  the existing 028/029 regression tests (`status seed in flight does not
  clear a turn that just started`, `questions poll in flight does not clear
  a question that just arrived`) must pass unmodified.

## Acceptance criteria

- [x] One guard implementation serves both seeded fields; the raw counters
  are gone from `ChatViewModel`. *(2026-07-31: `SeedGuard` (ui/chat, internal)
  serves all three guarded seeds — `isWorking`, `pendingQuestions`, and the
  ticket-041 `contextUsage` sibling, plus the 048 resync's `clearLive`
  downgrade; grep-verifiable: no `EventCount` left in `ChatViewModel`.)*
- [x] 028/029 regression tests green unmodified; new guard unit tests green.
  *(`status seed in flight does not clear a turn that just started`,
  `questions poll in flight does not clear a question that just arrived`
  untouched; `SeedGuardTest` covers stale/warm/both-edges/re-capture.)*
- [x] `./gradlew :app:testDebugUnitTest` green. *(2026-07-31: full suite
  green, 300 tests.)*

## Out of scope

- Rethinking which source (poll vs events) owns the question queue — the
  poll stays the source of truth on reconnect/turn.ended (docs/server-api.md,
  ticket 029).
- Guarding fields that don't have the race today — add them to the guard
  when a seed is introduced, not speculatively.

## Notes

- Ticket 029's notes explain why reconnect polls must NOT be guarded; keep
  that rationale visible at the guard's call sites. *(2026-07-31: kept — it
  lives in `SeedGuard`'s KDoc and, unchanged, above `seedPendingQuestions` /
  `refreshPendingQuestions`.)*
- 2026-07-31: since this ticket was written, ticket 041 added a third raw
  counter (`contextUsageEventCount`) and 048 a second capture site for the
  turn counter (`resyncHistoryAfterDrop`'s `clearLive` downgrade) — both
  migrated too, so no raw counter remains. `SeedGuard.bump()` calls sit
  exactly where the `++`s were; `capture()`/`isStale()` where the
  `val …AtSeed = count` / `count == …AtSeed` pairs were.
