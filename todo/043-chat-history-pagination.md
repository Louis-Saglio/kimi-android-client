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

- [ ] Unit test (MockWebServer): a next-page call sends
  `before_id=<oldest loaded message id>` and parses `has_more`; the first
  call sends no cursor.
- [ ] Unit test: prepending appends older messages before the existing ones,
  deduplicated by message id, and stops when `has_more` is false.
- [ ] On-device: scrolling to the top of a >100-message session loads older
  messages until the server reports `has_more: false`, without the scroll
  position jumping.
- [ ] On-device: a turn-end refetch resets history to the newest page without
  duplicated rows.
- [ ] `./gradlew :app:assembleDebug` succeeds, unit tests green.

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
