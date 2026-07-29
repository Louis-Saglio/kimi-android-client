# Chat history pagination

- **ID:** 043
- **Created:** 2026-07-27
- **Depends on:** 005

## Context

Ticket 005 shipped the chat screen with a single history fetch:
`GET /api/v1/sessions/{id}/messages?page_size=100`, oldest-first rendering of
the newest-first wire order. Sessions longer than 100 messages silently lose
their oldest history — the user cannot scroll back further, and nothing in the
UI signals the truncation.

As of the Phase 2 data-layer refactor the response DTO (`MessageListData`)
parses the server's `has_more` flag, so the cap is at least detectable: when
`has_more` comes back true, older messages exist that were not loaded. The
wire contract supports cursor paging backwards with `before_id` (same shape
as the session list, ticket 010).

Alternatives considered and rejected:

- **Bump `page_size` further:** still an arbitrary cap and every refetch —
  which happens on every turn end and reconnect — re-downloads the whole
  history. Postpones the problem instead of solving it.
- **Paging 3:** rejected for the session list (010) for the same reasons
  here: remote-only list, simple cursor pagination, boilerplate without
  payoff.

Agreed: cursor-based "load older messages" using the server's `before_id` /
`has_more` contract, mirroring ticket 010's session-list approach.

## Description

- `ChatApi.messages` gains an optional `beforeId: String?` parameter, sent as
  the `before_id` query param (null fetches the newest page). The return type
  gains the `hasMore` flag (e.g. a `MessagePage(items, hasMore)` like
  `SessionPage`) — surfaced from `MessageListData.hasMore`, already parsed.
  Mind the wire order on paged responses too: items stay newest-first and
  must be reversed client-side exactly like the first page.
- `ChatViewModel`:
  - `UiState` gains `hasMoreHistory: Boolean` and `isLoadingMoreHistory: Boolean`.
  - New `onHistoryTopReached()` (or scroll-driven equivalent): no-op while a
    load is in flight or when `hasMore` is false; otherwise fetches the next
    older page with `beforeId` = id of the oldest loaded message and PREPENDS
    it, deduplicating by message id (the live stream and refetches only ever
    touch the newest end).
  - A failed older-page load keeps the loaded history visible; retry on the
    next trigger. Full refetches (turn end, reconnect, ON_START) reset to the
    newest page exactly as today.
  - Re-entrancy guard: the in-flight Job, not a state flag (see ticket 010's
    notes — same trap).
- `ChatScreen`: scrolling near the top of the timeline triggers the load
  (e.g. `derivedStateOf` on the first visible item index) and shows a small
  header spinner while loading. Prepending must not shift the scroll
  position — anchor on the first visible message id before the prepend
  (LazyColumn keys make this tractable).

## Acceptance criteria

- [x] Unit test (MockWebServer): a next-page call sends
  `before_id=<oldest loaded message id>` and parses `has_more`; the first
  call sends no cursor.
  *(`ChatRepositoryTest.messages next page sends before_id and parses has_more`
  asserts the paths `/api/v1/sessions/session_1/messages?page_size=100` (no
  cursor, `hasMore == true`) and `…&before_id=msg_2` (`hasMore == false`,
  wire order reversed to oldest-first).)*
- [x] Unit test: prepending appends older messages before the existing ones,
  deduplicated by message id, and stops when `has_more` is false.
  *(`ChatViewModelTest.onHistoryTopReached prepends the next page deduplicated
  and stops when has_more is false` (one-id overlap, no duplicate row, second
  trigger is a no-op) + `ChatTimelineTest.an older history page prepends
  deduplicated by message id` (id-less rows kept) and
  `a full refetch after a prepend resets the dedup to the newest page`.)*
- [ ] On-device: scrolling to the top of a >100-message session loads older
  messages until the server reports `has_more: false`, without the scroll
  position jumping.
  *(Mechanics unit-tested; LazyColumn re-anchors on the first visible row's
  stable key (same as 046). Release APK published to the file server —
  on-device verification is Louis's.)*
- [ ] On-device: a turn-end refetch resets history to the newest page without
  duplicated rows.
  *(Unit-tested: `ChatViewModelTest.a turn-end refetch after paging resets to
  the newest page` (prepended page dropped, cursor reset to the fresh page's
  oldest) — on-device verification is Louis's.)*
- [x] `./gradlew :app:assembleDebug` succeeds, unit tests green.
  *(2026-07-29: 157 unit tests green across the three touched suites — 7 new
  (1 in `ChatRepositoryTest`, 3 in `ChatViewModelTest`, 3 in
  `ChatTimelineTest`); Firebase Test Lab robo passed on MediumPhone.arm-34;
  release APK published to the file server.)*

## Out of scope

- `after_id` forward paging (live stream covers the newest end).
- `role` filtering (server supports it; no need identified).
- Keeping more than the loaded pages in memory / eviction.
- Jump-to-message or search in history.

## Notes

- Wire contract: `docs/server-api.md`, Messages section — `page_size`,
  `before_id`/`after_id`, `has_more` in `data`; items newest-first.
- Ticket 010 (`done/010-session-list-pagination.md`) is the template: same
  cursor mechanics, but backwards (prepend instead of append) and against the
  message id cursor.
- The steer/splice echoes carry messages without ids (docs/server-api.md);
  the prepend dedup must tolerate id-less live rows — they never come from
  history pages, so dedup by id is still sound.
- 2026-07-29 scroll-jump fix (Louis's on-device report): the loading spinner
  shipped as a removable header row; a top fling overshooting into it made it
  the first visible row, LazyColumn anchored the scroll position on its key,
  and removing it in the same update that prepended the page destroyed the
  anchor — the scroll reset to the top of the new page. The spinner is now an
  overlay above the list (`MessageList`), never a row, so no removable row
  can ever become the anchor. The same latent bug in 046's sub-agent page is
  fixed by the same shared component.
- 2026-07-29 page-boundary crash fix (Louis's on-device report: sessions
  "ticket 043"/"ticket 047" crashed seconds after the older page loaded): a
  tool call at the end of the older page with its result at the start of the
  newer page (105 such split pairs in the 043 session alone) left an orphan
  result row in the newer page, keyed by the tool-call id; the prepend added
  the call's row with the SAME key — duplicate LazyColumn key, hard crash.
  `ChatTimeline.prependHistory` now adopts the orphan result into the
  incoming call row (`adoptOrphanResults`), re-keying survivor groups like
  `pruneCommittedLive` does. Regression tests: `ChatTimelineTest.an older
  page completing a boundary-split tool pair merges the orphan result` and
  `adopting one row of a committed tool group re-keys the survivors`.
