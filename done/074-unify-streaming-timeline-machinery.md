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

- [x] One paging implementation and one history-merge path serve both
  screens (grep-verifiable: no `olderHistoryJob`/`olderPageJob` twins, no
  second prepend-with-cursor loop).
  *(`HistoryPager` (ui/chat, internal) is the single loader; grep for
  `historyJob`/`olderHistoryJob`/`transcriptJob`/`olderPageJob` finds
  nothing — the fetch-job pair exists once, as `newestJob`/`olderJob`
  inside the pager, and `loadOlder()` is the only prepend-with-cursor
  path.)*
- [x] Existing timeline/paging tests green unmodified in behavior:
  `ChatViewModelTest`, `SubagentViewModelTest`, `SubagentStreamStoreTest`,
  `ChatTimelineTest`, plus the 061/062 regression tests.
  *(All four suites green unmodified — the store's merge API
  (`applyTranscriptPage`/`prependTranscriptPage`/`oldestTurnId`) and both
  ViewModels' public intents are unchanged, so not one test line moved.)*
- [x] `./gradlew :app:testDebugUnitTest` green; chat androidTests green on
  Firebase Test Lab.
  *(2026-07-31: 306 unit tests, 0 failures — ChatViewModelTest 47,
  ChatTimelineTest 61, SubagentStreamStoreTest 15, SubagentViewModelTest 7.
  Instrumented suite 42/42 passed on MediumPhone.arm-34, incl. all chat
  androidTests.)*

## Implementation notes (2026-07-31)

The controller is `HistoryPager<P>` (ui/chat, internal — the 065/072
collaborator pattern: a logic holder, NOT a second state source). It owns
exactly the machinery that was duplicated — the newest-page latest-wins
job, the older-page in-flight guard, the 061 cancel-older-on-refresh rule,
the newest-page guard on older loads, the `hasMore`/cursor guards, and the
`isLoadingMore` mirror — and takes everything else as constructor
callbacks (`fetch`, `onNewestStart`, `applyNewest`, `applyOlder`,
`onNewestError`, `hasMore`, `cursor`, `setLoadingMore`). Each ViewModel
holds one instance with its own parameterization:

- chat: `getMessages` fetch, direct `ChatTimeline.replaceHistory`/
  `prependHistory` merge (its `DeltaCoalescer.flush()` before the replace,
  ticket 062), sub-agent row seeding on a fresh page, `RefreshFailed`
  effect vs full-screen error, cursor/`hasMore` in ViewModel state;
- sub-agent: `getTranscript` fetch (page size 20), the STORE's
  `applyTranscriptPage`/`prependTranscriptPage` merge (flush included —
  the store keeps its ownership and its `oldestTurnId`/`hasMoreTurns`
  state, pinned by `SubagentStreamStoreTest`), the `isLoading` full-screen
  state via `onNewestStart`, conditional error field.

`ChatViewModel.refreshHistory(clearLive:)` survives as a one-line polarity
adapter (the chat's resync thinks in clearLive, the pager in keepLive —
matching the timeline/store API); `maybeReconcile`, the seeded prompt, and
the socket ownership stayed put, per the parameter list. `ChatTimeline`
kept its `replaceHistory`/`replaceTranscript`/`prependHistory`/
`prependTranscript` four: they already share the single
`replaceCommitted`/`prependCommitted` boundary, and their flatten/dedup
differences ARE the documented transcript-vs-messages parameter.

Two deliberate micro-differences versus the old copies, both strictly
safer and unobservable in practice:

- the sub-agent's older-page trigger gained the chat's null-cursor guard
  (the old copy would have re-fetched the NEWEST page with
  `before_turn = null` had `hasMoreTurns` ever been true without a cursor
  — unreachable, since `has_more: true` implies a landed non-empty page);
- the `isLoadingMore` reset moved into the pager's `try/finally` — same
  success/failure behavior, plus it also covers a cancelled load (already
  reset by `refresh` in that case).

No dedicated `HistoryPagerTest`: every guard is exercised end-to-end
through the two ViewModel suites (061's parked-response race, the
`has_more: false` stop on both screens, the failed-page retry, the
turn-end newest-page reset, the boundary dedup) — the 065 coverage
strategy (DeltaCoalescer has no own test class either). The hard
requirement never triggered: beyond the ticket's parameter list the only
extra differences found were per-screen presentation (error surface,
`isLoading`, sub-agent row seeding), which the 072 doctrine assigns to the
owning ViewModel anyway — they ride the same callback channel.

`AGENTS.md` updated: the 045/046 paragraph now states the paging machinery
is not a second implementation, and `HistoryPager` joined the collaborator
roster.

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
  constraint for this work. *(Landed well under it after all: ~230 changed
  lines across `ChatViewModel`/`SubagentViewModel` plus the 95-line
  `HistoryPager` — the 061/062/065 prep work had already removed the
  behavioral drift, so the unification was a pure move.)*
