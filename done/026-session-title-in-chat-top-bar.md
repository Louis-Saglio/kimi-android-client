# Session title in the chat top bar

- **ID:** 026
- **Created:** 2026-07-21
- **Depends on:** none

## Context

Product discussion 2026-07-21 (rendering-parity batch, added at Louis's
request while scoping). The chat screen's top bar shows the raw session id
(`session_71408a0f-4957-41c3-b65…`, ellipsized — pure noise). The web client
shows the working directory and the session title. The app already has both:
`Session.title` and `Session.cwd` are parsed from the session list payload
(`SessionDto` → domain `Session`), they just never reach the chat screen.

## Description

- Bring the session's title (and cwd) into the chat screen — via navigation
  arguments from the session list if that stays simple, or by looking the
  session up from `SessionsRepository` by id; whichever is smaller. Do not
  add a new endpoint for this.
- Top bar shows the session title when present; falls back to the cwd
  basename, then to the session id — mirroring the session list row's
  fallback logic.
- Show the cwd as a subtitle line under the title when available (the web
  header shows both).

## Acceptance criteria

- [x] A session with a title shows it in the chat top bar; a titleless
  session falls back per the same rule as the list row (unit-tested at
  ViewModel/mapping level).
  *(`SessionTest` covers title → cwd basename → id, incl. trailing-slash and
  root-cwd edge cases; `ChatViewModelTest` covers the title and titleless
  cases end-to-end against MockWebServer.)*
- [x] The cwd appears as a subtitle when available (visual check).
  *(Subtitle line under the top-bar title, rendered when `sessionCwd` is
  non-null; verified by build + `ChatViewModelTest` asserting the state, not
  on-device.)*
- [x] `./gradlew :app:assembleDebug` succeeds, unit tests green.
  *(`testDebugUnitTest` + `assembleDebug` green on 2026-07-23.)*

## Implementation notes

- Fallback lives in `Session.displayName` (`data/sessions/Session.kt`),
  shared by the list row and the chat top bar. Side effect: the list row now
  shows the cwd **basename** instead of the full path for titleless sessions.
- `SessionsRepository.getSession` uses the existing
  `GET /api/v1/sessions/{id}` — no new endpoint.
- A failed lookup leaves `sessionTitle`/`sessionCwd` null and the top bar
  falls back to the raw session id.
- Follow-up (2026-07-23): title/cwd also ride along as optional navigation
  query args (`chat/{sessionId}?title=…&cwd=…`), seeded into the initial
  `UiState` via `displayNameFor`, so the top bar shows the right label from
  the first frame — the session fetch then confirms it. `SessionCreated`
  carries the full `Session` so the create flow gets the same seeding.

## Out of scope

Renaming sessions, editing titles, a model picker or any other top-bar
actions, syncing the title live if another client renames the session.

## Notes

- Keep the fallback logic in one place shared with the session list row — it
  already implements title → cwd → id; duplicate it if sharing is awkward,
  do not build an abstraction for two call sites.
