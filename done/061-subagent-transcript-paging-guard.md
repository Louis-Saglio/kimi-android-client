# Sub-agent transcript paging can leave a permanent hole on a reconcile race

- **ID:** 061
- **Created:** 2026-07-31
- **Depends on:** none

## Context

Found in the 2026-07-31 tech-debt audit. `ChatViewModel` guards older-page
loads against a racing refetch and documents why
(`ChatViewModel.kt:926-935`): *"a page fetched against the stale cursor would
land on a refetch's fresh page with a hole in between."* It enforces two
rules: `onHistoryTopReached` returns early while the newest-page job is
active, and `refreshHistory` cancels `olderHistoryJob` before starting
(`ChatViewModel.kt:888-893`).

`SubagentViewModel` re-implemented the same paging pattern but lost both
rules: `onHistoryTopReached` (`SubagentViewModel.kt:140-153`) has no
`transcriptJob` check, and `refreshTranscript` (`:107-110`) cancels only
`transcriptJob`, not `olderPageJob`. Concrete race: user scrolls up (older
page in flight) → the sub-agent reaches a terminal state → `maybeReconcile`
(`:99-105`) fires `refreshTranscript(keepLive = false)` → the fresh newest
page replaces committed rows → the stale older-page response prepends turns
that no longer abut the fresh page → a hole in the rendered timeline. Turn-id
dedup prevents duplicates, not holes, and it never self-heals: a reopened
page trusts the store (`SubagentViewModel.kt:89-92` skips the fetch when
`hasTranscript`).

## Description

Port the documented `ChatViewModel` pattern into `SubagentViewModel`:

- `onHistoryTopReached()` returns early when `transcriptJob?.isActive == true`
  (in addition to the existing `olderPageJob` check).
- `refreshTranscript()` cancels `olderPageJob` before launching the fresh
  fetch.
- Regression test: park the older-page response in the MockWebServer
  dispatcher (the `statusResponseGate` pattern from tickets 027/028), trigger
  `refreshTranscript`, then release the gate — the stale page must not
  prepend. `SubagentViewModelTest` exists.

## Acceptance criteria

- [x] The race above cannot prepend a stale page (unit-tested, RED before the
  fix, GREEN after).
  *(`SubagentViewModelTest.a stale older page racing the reconcile refetch
  never prepends` — RED pre-fix (the parked t0 page prepended onto the fresh
  t2 page), GREEN after.)*
- [x] `./gradlew :app:testDebugUnitTest` green.
  *(287 tests, 18 classes, 0 failures.)*

## Implementation notes (2026-07-31)

Both guards ported verbatim from `ChatViewModel`: `refreshTranscript` cancels
`olderPageJob` (and resets `isLoadingMoreHistory`, like `refreshHistory`) before
launching, and `onHistoryTopReached` returns early while `transcriptJob` is
active. The test harness parks the older-page response in the MockWebServer
dispatcher (the `statusResponseGate` pattern), with one adaptation: the
dispatcher reads response bodies at response time, so a parked response must
serve a dedicated `olderTranscriptBody` (the `olderMessagesBody` split in
ChatViewModelTest) — otherwise it would serve the fresher `transcriptBody`
assigned after the request went out, and the turn-id dedup would mask the
prepend. The fresh page (t2) and the stale page (t0) share no turn id for the
same reason.

## Out of scope

- Unifying the two duplicated paging implementations — deferred at filing
  time, then filed as ticket 074 when the diff-size constraint was lifted.

## Notes

- The guard-loss is the second time the duplicated streaming/paging logic
  drifts between the main chat and the sub-agent path; the first is ticket
  062. The coalescing half of the duplication is fixed structurally in ticket
  065.
