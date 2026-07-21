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

- [ ] A session with a title shows it in the chat top bar; a titleless
  session falls back per the same rule as the list row (unit-tested at
  ViewModel/mapping level).
- [ ] The cwd appears as a subtitle when available (visual check).
- [ ] `./gradlew :app:assembleDebug` succeeds, unit tests green.

## Out of scope

Renaming sessions, editing titles, a model picker or any other top-bar
actions, syncing the title live if another client renames the session.

## Notes

- Keep the fallback logic in one place shared with the session list row — it
  already implements title → cwd → id; duplicate it if sharing is awkward,
  do not build an abstraction for two call sites.
