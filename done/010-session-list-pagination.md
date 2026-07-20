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

- [x] Unit test (MockWebServer): a next-page call sends
  `before_id=<last session id>` and parses `has_more`; first-page call sends
  no cursor.
  *(`SessionsRepositoryTest.next page sends before_id and parses has_more`
  asserts the exact path `/api/v1/sessions?before_id=session_20` and
  `hasMore == false`; the updated `envelope items are mapped to domain
  sessions` asserts the first-page path is `/api/v1/sessions` with no query
  string and `hasMore == true`.)*
- [x] On-device: scrolling to the bottom of a >20-session list appends older
  sessions until the server reports `has_more: false` (verified against the
  web client's session list).
  *(Wire contract re-verified live via curl 2026-07-20 against server 0.27.0:
  `page_size=2` → `has_more: true`; `before_id=<last id of page>` → the next 2
  older sessions. Append + dedup + stop-at-`has_more:false` unit-tested in
  `SessionListViewModelTest.onEndReached appends the next page deduplicated
  and stops when has_more is false`. On-device scroll verified by Louis
  2026-07-20.)*
- [x] On-device: pull-to-refresh / ON_START reload resets to the first page
  without duplicated rows.
  *(Unit-tested: `refresh after paging resets to the first page` (list
  replaced by the fresh first page, third request carries no cursor) and the
  append test above (overlap by one id, no duplicate row). On-device
  pull-to-refresh verified by Louis 2026-07-20.)*
- [x] `./gradlew :app:assembleDebug` succeeds. *(2026-07-20: 45 unit tests
  green — 5 new (1 in `SessionsRepositoryTest`, 4 in
  `SessionListViewModelTest`); Firebase Test Lab robo passed on
  MediumPhone.arm-34; APK published to the file server.)*

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
- The re-entrancy guard for `onEndReached()` is the in-flight `loadMoreJob`,
  not the `isLoadingMore` state flag: the UI triggers on every recomposition
  near the end and state writes are not visible synchronously across
  dispatches, so two triggers could both pass a flag check before either
  writes. `reload()` also cancels an in-flight page load so a refresh never
  appends a stale page onto the fresh first page.
- The end-of-list trigger fires within `LOAD_MORE_THRESHOLD = 3` rows of the
  end via `derivedStateOf` on `LazyListState.layoutInfo`; the ViewModel no-ops
  when nothing more is available, so over-triggering is harmless.
