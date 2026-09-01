# Workspace label in flat session rows

- **ID:** 114
- **Created:** 2026-09-01
- **Depends on:** none

## Context

Product discussion with Louis (2026-09-01). In the flat list, a row does not
say which workspace the session belongs to, and several of Louis's projects
share directory names. Louis specified the label rule: the last path segment,
unless two displayed workspaces share it — then prepend parent segments until
the labels differ (e.g. `/home/a/app` and `/home/b/app` → `a/app` and
`b/app`).

The server's own workspace `name` (`GET /api/v1/workspaces`) was considered
and rejected as the source: it is often empty server-side, and Louis's path
rule covers every case uniformly. Grouped view (ticket 115) reuses the same
function for its group headers, so it lives in the data layer, pure and
tested.

## Description

- New pure function in `data/sessions` (e.g. `WorkspaceLabels.kt`):
  given the collection of cwds currently in play, compute the label for each
  — last non-empty path segment; while another cwd in the set produces the
  same label, prepend that cwd's next parent segment. Same cwd ⇒ same label;
  trailing slashes trimmed; a cwd of `/` (or null) yields no label.
- `SessionListViewModel`: derive the label map from the loaded sessions' cwds
  (recompute on list change — a page load can force a relabel when a colliding
  cwd arrives; accepted, it is rare and the labels just gain a parent
  segment).
- `SessionRow`: the supporting line becomes `<label> · <date>` (single line,
  ellipsized); sessions without a cwd keep the bare date. Headline and
  trailing content unchanged.

## Acceptance criteria

- [ ] Unit tests for the label function: distinct basenames → basename only;
  collision → parents prepended until unique (`/home/a/app` vs `/home/b/app`
  → `a/app` / `b/app`); identical cwds → identical labels; nested collision
  (`/x/a/app`, `/y/a/app`, `/b/app`) resolves each minimally; trailing slash
  and `/` handled.
- [ ] `SessionRow` renders `<label> · <date>` when the session has a cwd
  (semantics test updated), bare date when it has none.
- [ ] On-device: two workspaces sharing a directory name show disambiguated
  labels in the list.
- [ ] `./gradlew :app:assembleDebug` succeeds with all unit tests green (VPS
  rules: `flock /tmp/kimi-android-gradle.lock`, `--max-workers=1`,
  `--no-daemon` when parallel).

## Out of scope

- The grouped-by-workspace view (115) — it consumes this function for headers.
- Server workspace `name` display.
- Workspace label in the chat top bar (not requested).

## Notes

- Key files: `data/sessions/WorkspaceLabels.kt` (new),
  `ui/sessions/SessionListViewModel.kt` (derived labels),
  `ui/sessions/SessionsScreen.kt` (`SessionRow`).
- The label map belongs with the list state, not inside `Session` — it depends
  on the other rows, so it cannot be a per-session property.
