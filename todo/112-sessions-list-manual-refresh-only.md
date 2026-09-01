# Sessions list: manual refresh only

- **ID:** 112
- **Created:** 2026-09-01
- **Depends on:** 111

## Context

Product discussion with Louis (2026-09-01). The list must not refresh itself:
no fetch on cold start (the 111 cache renders instantly instead), no fetch
when returning from chat or settings. Refresh happens only via pull-to-refresh
or the error state's Retry.

This deliberately reverses the ticket 086 decision (2026-08-02), where Louis
chose the silent ON_START refresh over zero background requests because the
running dot and approval badge would go stale. What changed since: ticket 087
added the live socket — busy dot, needs-attention badge and title patches
arrive live while the list is visible — and ticket 105 drops archived rows
live. The staleness that remains is limited to sessions created or reordered
by other clients, which Louis accepts: they appear on the next manual refresh.
Louis explicitly chose "no fetch on cold start" over "one silent fetch after
the cache renders".

Stays automatic (none of these are list refreshes):

- the 086 local activity bump on return from chat;
- the 086 create-session insert;
- 087 socket patches (work_changed / meta.updated / archived);
- the socket-reconnect healing re-sync: one silent `silentRefresh()` after a
  drop + successful reconnect to close the missed event window. Flagged in the
  discussion and accepted — event-driven, invisible, one request per reconnect;
- the visible initial load when `sessions == null` (no cache for the current
  server: first run, server switch). That is a first load, not a refresh.

## Description

- `SessionListViewModel.onScreenStarted`: keep `bumpPendingActivity()` and
  `startSocket()`; fire `reload()` only when `sessions == null`. The loaded
  case no longer calls `silentRefresh()`.
- `silentRefresh()` stays, now called only from the
  `SessionListEvent.Reconnected` branch.
- `onPullToRefresh` and `onRetry` keep today's explicit behavior (spinner,
  `RefreshFailed` snackbar on failure).
- Update `SessionListViewModelTest`: the 086-era tests asserting an ON_START
  request with a loaded list now assert the opposite.

## Acceptance criteria

- [ ] ON_START with a loaded list fires no HTTP request (ViewModel test:
  MockWebServer request count stays 0 after `onScreenStarted()` with sessions
  present; socket work is faked as in the existing 087 tests).
- [ ] ON_START with `sessions == null` still performs the visible initial
  load (spinner, error state on failure).
- [ ] Pull-to-refresh still reloads with `isRefreshing` and emits
  `RefreshFailed` on failure; Retry still reloads (existing tests stay green).
- [ ] A socket reconnect still triggers exactly one silent refresh (existing
  087 test stays green).
- [ ] The activity bump and the create-session insert still apply without any
  refresh (existing 086 tests adjusted to the no-request expectation).
- [ ] `./gradlew :app:assembleDebug` succeeds with all unit tests green (VPS
  rules: `flock /tmp/kimi-android-gradle.lock`, `--max-workers=1`,
  `--no-daemon` when parallel).

## Out of scope

- The disk cache itself (111).
- Any timer- or TTL-based refresh.
- Changing what the live socket patches (087/105 behavior is untouched).

## Notes

- Key files: `ui/sessions/SessionListViewModel.kt` (`onScreenStarted`,
  `silentRefresh` callers), `ui/sessions/SessionListViewModelTest.kt`.
- Related tickets: 086 (superseded decision), 087 (live socket), 105 (live
  archive), 111 (cache prerequisite).
