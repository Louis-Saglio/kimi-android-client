# Unify the duplicated streaming-timeline machinery between main chat and sub-agent path

- **ID:** 074
- **Created:** 2026-07-31
- **Depends on:** 061, 062, 065

## Context

The deep structural fact behind three tickets in the 2026-07-31 debt
program: the sub-agent path is a **second implementation** of the same
concept as the main chat — a streaming, paginated, live-healing timeline
screen. The two copies have already drifted in **both** directions:

- the sub-agent copy lost the newest-page paging guard the main chat
  documents (ticket 061 — permanent timeline hole on a reconcile race);
- the main-chat copy lost the flush-before-replace discipline the
  sub-agent copy has (ticket 062 — duplicate-bubble race);
- both share a cloned delta-coalescing state machine (ticket 065 extracts
  it).

061/062/065 fix the drift instances and deduplicate the coalescer, but
leave the rest of the parallel structure standing: history paging
(latest-wins job, in-flight guards, `hasMore`/`isLoadingMore`, prepend-
with-cursor) and the keepLive history-merge logic still exist in two
shapes. Ticket 061's filing note deferred this unification ("revisit only
if a third paged screen appears"); Louis lifted the diff-size constraint
on 2026-07-31 and asked for the root fix instead.

Depends on 061/062/065 landing first: with the drift repaired and the
coalescer shared, what remains duplicated is exactly the machinery this
ticket unifies — no hidden behavioral differences get baked into the
abstraction.

## Description

Unify the remaining shared machinery into one timeline-screen controller
used by both the main chat and the sub-agent pages, with the genuine
differences as parameters:

- **Paging**: one paged-history loader (latest-wins job, in-flight guards,
  `hasMore`/`isLoadingMore`, prepend-with-cursor keyed by message id or
  turn id).
- **History merge**: one keepLive replace/prepend path (post-065 the
  flush-before-replace invariant already lives in `DeltaCoalescer`).
- Differences that stay parameterized, not abstracted away: transcript vs
  messages backing (`ChatMessage` vs the 046 turn/step/frame model),
  `SubagentStreamStore`'s seeded prompt living outside the timeline,
  terminal-state reconcile (`maybeReconcile`) which only the sub-agent
  path has.
- The sub-agent store keeps its session-scoped singleton identity and its
  single-feeder socket invariant (045/048); this ticket re-homes logic,
  not ownership.

Hard requirement: if the two paths turn out to differ more than the
parameter list above, stop and report rather than forcing a leaky
abstraction — the fallback is the post-065 status quo, which is already
correct.

## Acceptance criteria

- [ ] One paging implementation and one history-merge path serve both
  screens (grep-verifiable: no `olderHistoryJob`/`olderPageJob` twins, no
  second prepend-with-cursor loop).
- [ ] Existing timeline/paging tests green unmodified in behavior:
  `ChatViewModelTest`, `SubagentViewModelTest`, `SubagentStreamStoreTest`,
  `ChatTimelineTest`, plus the 061/062 regression tests.
- [ ] `./gradlew :app:testDebugUnitTest` green; chat androidTests green on
  Firebase Test Lab.

## Out of scope

- Merging the `ChatMessage` and transcript domain models (deliberate,
  documented split — `Transcript.kt:1-9`).
- Changing socket ownership/lifecycle (ticket 064) or cursor semantics
  (048).
- Any UI change.

## Notes

- Update `AGENTS.md`'s 045/046 architecture paragraph once the controller
  exists — it currently describes the two-implementation design.
- Sizing honesty: this is the one ticket in the program expected to exceed
  the classic ~500-line budget; it was filed only after Louis lifted that
  constraint for this work.
