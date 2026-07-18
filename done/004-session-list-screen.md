# Session list screen

- **ID:** 004
- **Created:** 2026-07-17
- **Depends on:** 003

## Context

First real screen of the core loop (connect → list → chat → approve). Mirrors a
subset of the web client's session list: title, last activity, running status.
Creation and session actions are deliberately split into tickets 008 and later.

## Description

- `SessionsRepository` using the Hilt `OkHttpClient` and
  `kotlinx.serialization` DTOs matching `docs/server-api.md`; maps failures to a
  sealed error type at the repository boundary (house rule).
- `SessionListViewModel`: immutable `UiState` (loading / sessions / error) as
  `StateFlow`, effects via `Channel`.
- UI replacing the 002 placeholder:
  - `LazyColumn` of sessions: title (fallback: working directory), last-activity
    timestamp, running indicator when the session status is active.
  - Loading spinner, error state with Retry button, empty state text.
  - Pull-to-refresh.
- Tapping a row navigates to a `chat/{sessionId}` route rendering a stub chat
  screen: app bar shows the session id, body is a placeholder (filled by 005).
- Refresh the list on resume (ON_START) — no WebSocket needed for this screen.

## Acceptance criteria

- [x] Real sessions from the live server render with title and timestamp
  (DTO mapping covered by `SessionsRepositoryTest` against the documented wire
  format; rendering manually verified by Louis on-device against the web
  client's session list, 2026-07-18 — modulo the missing pagination, now
  ticket 010).
- [x] Airplane mode or wrong URL → error state; Retry recovers. *(Error mapping
  unit-tested (`Network`, `Auth`, `Api`, `InvalidUrl` → `DataError`); Retry
  path manually verified by Louis on-device, 2026-07-18.)*
- [x] Pull-to-refresh reloads the list. *(Manually verified by Louis
  on-device, 2026-07-18.)*
- [x] Tapping a session opens the stub chat with the correct session id in the
  app bar. *(Manually verified by Louis on-device, 2026-07-18.)*
- [x] `./gradlew :app:assembleDebug` succeeds. *(2026-07-18, plus 5 new unit
  tests green and Firebase Test Lab robo smoke test passed on
  MediumPhone.arm-34.)*

## Out of scope

Creating sessions (008), archive/fork/rename/compact actions (deferred, no
ticket yet), chat content (005), live status updates via WebSocket, search/filter.

## Notes

- Load `android-skills:android-data-layer` for the repository/error-boundary
  pattern.
