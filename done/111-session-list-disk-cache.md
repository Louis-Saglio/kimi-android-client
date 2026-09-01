# Session list disk cache

- **ID:** 111
- **Created:** 2026-09-01
- **Depends on:** none

## Context

Product discussion with Louis (2026-09-01). Today a cold start shows a spinner
until the network answers (`UiState.sessions == null` → `LoadingState`). Louis
wants the last-known list on screen immediately when he opens the app. This is
also the prerequisite for ticket 112 (manual refresh only): without a cache,
removing the automatic refresh would leave cold starts either spinning or
empty.

Alternatives considered and rejected: Room — rejected for this list already in
ticket 010 (remote-only list, simple data; a JSON file is enough); Preferences
DataStore — meant for small key-value settings, not a few hundred serialized
sessions.

## Description

- New `SessionListCache` in `data/sessions` (`@Singleton`): `load(serverKey)`
  and `save(serverKey, sessions, hasMore)`. Storage is one JSON file per server
  in `filesDir/session-list-cache/`, the file name derived from the server base
  URL (e.g. its SHA-256 hex) so switching servers in settings never shows the
  previous server's sessions. Serialization via the injected
  `kotlinx.serialization.Json`, like every other DTO in the data layer. The
  cached session fields mirror the domain `Session`: id, title, cwd,
  updatedAt (epoch ms), isRunning, isAwaitingInteraction. A corrupt or
  undecodable file is deleted and treated as absent — no schema versioning.
- `SessionListViewModel` init: load the cache for the current server
  (`settingsRepository.current()`'s base URL) and populate `sessions` +
  `hasMore` before any network traffic. With a cache present, cold start never
  shows the spinner. Until 112 lands, today's ON_START refresh still runs on
  top and reconciles — that is fine and keeps this ticket independently
  shippable.
- Write-through persistence: a `viewModelScope` collection of
  `uiState.map { it.sessions to it.hasMore }.distinctUntilChanged()`,
  debounced (~500 ms), saving on the IO dispatcher. One writer covering every
  mutation path (initial load, refresh, paging, socket patches, archive drops,
  activity bumps, create-insert) — no per-call-site saves. Skip while
  `sessions == null`.
- Cached `isRunning`/`isAwaitingInteraction` go stale across process death;
  accepted — the 087 socket patches them live as soon as the list is visible.

## Acceptance criteria

- [x] `SessionListCache` round-trip unit test: save → load returns the same
  sessions and `hasMore`; a corrupted file decodes as absent and is deleted.
  (`SessionListCacheTest`)
- [x] ViewModel test (MockWebServer with a delayed response): with a cache
  present, `uiState.sessions` is populated from the cache before the network
  answers — no loading state in between. (`SessionListViewModelTest`)
- [x] ViewModel test: with no cache for the current server, behavior is
  unchanged (spinner → visible initial load). (`SessionListViewModelTest`)
- [x] Cache is keyed per server: two different base URLs keep independent
  caches (unit test on the keying). (`SessionListCacheTest`)
- [ ] On-device: kill the app, enable airplane mode, relaunch → the cached
  list renders instead of the error state.
- [x] `./gradlew :app:assembleDebug` succeeds with all unit tests green (VPS
  rules: `flock /tmp/kimi-android-gradle.lock`, `--max-workers=1`,
  `--no-daemon` when parallel).

## Out of scope

- Removing the automatic ON_START refresh — ticket 112, depends on this one.
- Caching pagination cursors beyond `hasMore`; paging simply continues from
  the cached list's last id.
- Any TTL/expiry logic — the list is revalidated by manual refresh and the
  live socket, not by time.
- Room or Paging 3.

## Notes

- Key files: `data/sessions/SessionListCache.kt` (new),
  `ui/sessions/SessionListViewModel.kt` (init load + write-through).
- The cache file is user-visible app state, so `filesDir`, not `cacheDir`.
- ~400 sessions serialize to well under 100 KB; no size cap needed.
