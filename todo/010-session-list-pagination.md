# Session list pagination

- **ID:** 010
- **Created:** 2026-07-18
- **Depends on:** 004

## Context

Ticket 004 shipped the session list without paging: the repository calls
`GET /api/v1/sessions` with no query params, so the server applies its default
`page_size` of 20 and the app ignores `has_more` (docs/server-api.md, Sessions
section). Louis confirmed on-device that only the 20 most recent sessions
appear; older sessions are unreachable from the app.

Alternatives considered and rejected:

- **Bump `page_size` (e.g. 100):** one line, but still an arbitrary cap and
  every refresh re-downloads the whole list. Postpones the problem instead of
  solving it.
- **Paging 3:** rejected as boilerplate without payoff here — it earns its
  complexity with Room + RemoteMediator; this list is remote-only with simple
  cursor pagination.

Agreed: cursor-based infinite scroll using the server's `before_id` /
`has_more` contract.

## Description

- `SessionsRepository.listSessions` gains an optional `beforeId: String?`
  parameter; when non-null it is sent as the `before_id` query param. Parse
  `has_more` from the response data. Return type changes from
  `Result<List<Session>>` to `Result<SessionPage>` where
  `SessionPage(items: List<Session>, hasMore: Boolean)` — the ViewModel is the
  only caller, so the change stays contained.
- `SessionListViewModel`:
  - `UiState` gains `hasMore: Boolean` and `isLoadingMore: Boolean`.
  - New `onEndReached()`: no-op while loading/refreshing or when `hasMore` is
    false; otherwise fetches the next page with `beforeId` = id of the last
    loaded session and appends it, deduplicating by session id.
  - A failed next-page load keeps the loaded list visible and surfaces an
    inline footer error; full refresh (ON_START, pull-to-refresh, Retry)
    resets to the first page exactly as today.
- `SessionsScreen`: scrolling near the end of the `LazyColumn` triggers
  `onEndReached()` (e.g. via `derivedStateOf` on the last visible item index).
  Footer item below the list: spinner while `isLoadingMore`, error text +
  retry when the last page load failed, nothing otherwise.

## Acceptance criteria

- [ ] Unit test (MockWebServer): a next-page call sends
  `before_id=<last session id>` and parses `has_more`; first-page call sends
  no cursor.
- [ ] On-device: scrolling to the bottom of a >20-session list appends older
  sessions until the server reports `has_more: false` (verified against the
  web client's session list).
- [ ] On-device: pull-to-refresh / ON_START reload resets to the first page
  without duplicated rows.
- [ ] `./gradlew :app:assembleDebug` succeeds.

## Out of scope

- `exclude_empty` and any other list filtering/search (server supports the
  params; no ticket yet — create one if Louis wants it).
- Paging 3 migration.
- Chat message pagination (005 territory).
- Sorting controls (list stays server-ordered, most recently updated first).

## Notes

- Wire contract: `docs/server-api.md` Sessions section — `page_size` (default
  20, fine as the app page size), `before_id`/`after_id` cursors, `has_more`
  flag in `data`.
- Load `android-skills:android-data-layer` for the repository/error-boundary
  pattern, as with 004.
