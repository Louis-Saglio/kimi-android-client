# refreshHistory must flush the open delta burst before replaceHistory

- **ID:** 062
- **Created:** 2026-07-31
- **Depends on:** none

## Context

Found in the 2026-07-31 tech-debt audit. The socket path flushes pending
coalesced deltas before every non-delta *event*, but the REST refetch path
bypasses the event dispatcher: `ChatViewModel.kt:895-899` calls
`timeline.replaceHistory(page.items, keepLive = ...)` with a possibly-open
delta burst. If the response lands mid-burst (≤100 ms after the leading
edge), the committed page holds the full bubble text while the live tail
holds only the leading-edge fragment. `pruneCoveredLiveRows`
(`ChatTimeline.kt:239-267`) dedups bubbles by **exact** text — the fragment
doesn't match, survives, and the trailing flush appends the rest: a
duplicate bubble below the committed one, until the next clear-live refetch.

The sub-agent store's twin path does this right: `SubagentStreamStore.kt:259-260`
flushes first, with the comment *"Any coalesced text must apply BEFORE the
replace wipes/dedups the live rows"*. The main chat missed the same
precaution — the second drift between the two copies (first: ticket 061).

## Description

- Call `flushPendingDeltas()` before every `timeline.replaceHistory(...)` /
  `replaceTranscript(...)` call in `ChatViewModel` (the audit cites the
  `historyJob` success path at `ChatViewModel.kt:895-899`; check the resync
  path `resyncHistoryAfterDrop` and any other call sites too).
- Regression test: a leading-edge delta is published, then a history response
  containing the full bubble text lands before the trailing flush → exactly
  one bubble with the full text. `streamCoalesceWindowMillis` is injectable
  (0 in unit tests) — follow the ticket-049 recorder pattern to hold the
  burst open deterministically.

## Acceptance criteria

- [ ] The race above cannot produce a duplicate bubble (unit-tested, RED
  before the fix, GREEN after).
- [ ] `./gradlew :app:testDebugUnitTest` green.

## Out of scope

- The structural fix (carrying the flush-before-replace invariant in an
  extracted `DeltaCoalescer` so call sites can't forget it) — ticket 065,
  which depends on this one landing first.

## Notes

- Keep the fix minimal: the extraction in 065 will re-home the call.
