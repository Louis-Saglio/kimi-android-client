# Extract the delta-coalescing state machine (DeltaCoalescer)

- **ID:** 065
- **Created:** 2026-07-31
- **Depends on:** 062

## Context

Found in the 2026-07-31 tech-debt audit (flagged independently by the
chat-core and UI audits). `ChatViewModel.kt:705-765` and
`SubagentStreamStore.kt:333-394` contain the same state machine nearly
verbatim: `pendingDeltas`, `deltaFlushJob`, `onStreamDelta`,
`runDeltaFlushLoop`, `flushPendingDeltas`, `mergeDelta`, plus twin
`isStreamDelta` helpers — even the doc comments are clones.

The real cost isn't the ~80 duplicated lines; it's that the load-bearing
invariant — **flush pending deltas before any non-delta event mutates the
timeline** — is upheld manually at each entry point (`ChatViewModel.kt:643`,
`SubagentStreamStore.kt:324`, `:288`, `:260`). Ticket 062 is the proof this
gets forgotten: the REST refetch path in the main chat missed the flush that
the store's twin path has. Same class of problem ticket 055 eliminated for
timeline keys: carry the invariant in the structure, not at call sites.

## Description

- Extract a `DeltaCoalescer` (ui/chat, internal) owning
  `pendingDeltas`/`flushJob`/`mergeDelta`, constructed with the coalesce
  window, a coroutine scope, an `apply(event)` sink, and a `publish()`
  callback.
- Expose a single entry point `onEvent(event)` that coalesces stream deltas
  and **flushes-before-apply for anything else**, so callers cannot forget
  the invariant; plus `flush()` for the REST paths (ticket 062's fix moves
  here).
- `ChatViewModel` and `SubagentStreamStore` delegate; both hand-written
  copies are deleted.
- The ticket-049 timing tests (`ChatViewModelTest`,
  `SubagentStreamStoreTest`) must stay green with
  `streamCoalesceWindowMillis = 0`; the window stays injectable through the
  coalescer's constructor.

## Acceptance criteria

- [x] One coalescing implementation remains; both call sites route every
  timeline-bound event through it (grep-verifiable: no `pendingDeltas` left
  in `ChatViewModel`/`SubagentStreamStore`).
- [x] The 062 regression test still passes, now exercising the coalescer's
  `flush()`.
- [x] `./gradlew :app:testDebugUnitTest` green, including the ticket-049
  conflation tests under repeated runs. *(4 consecutive runs, 295 tests, 0
  failures.)*

## Out of scope

- Rethinking the 100 ms window itself (it's a mitigation for the markdown
  renderer's re-parse; ticket 073 may revisit it).
- Unifying the history-paging duplication between the two screens (ticket
  061 notes why that's deferred).

## Notes

- `AGENTS.md` documents "same delta coalescing as ChatViewModel" for the
  store — update that line to point at the shared `DeltaCoalescer`.
