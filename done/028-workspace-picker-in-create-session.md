# Workspace picker in create-session dialog

- **ID:** 028
- **Created:** 2026-07-22
- **Depends on:** 008

## Context

Creating a session from the phone currently means typing the full working-directory
path by hand, which is painful on a mobile keyboard and error-prone. The server
already tracks workspaces: `GET /api/v1/workspaces` (verified live 2026-07-22)
returns every known workspace with `id`, `root`, `name`, `last_opened_at`, and
`session_count`. This endpoint is not yet documented in `docs/server-api.md`.

Alternatives considered and rejected: deriving the workspace list from the
existing sessions (misses session-less workspaces, duplicates sorting logic);
server-side filesystem browsing via `fs:*` (much bigger, deferred since ticket
008). The editable-dropdown UI was chosen over a dropdown-plus-custom-mode toggle
to keep the dialog to a single control.

## Description

- Document `GET /api/v1/workspaces` in `docs/server-api.md` (response shape
  above; note it is verified against the currently deployed server version).
- Data layer: add a `Workspace` model + `GET /api/v1/workspaces` call to the
  sessions repository (or a small workspaces repository if that fits the
  existing structure better), returning workspaces sorted by `last_opened_at`
  descending.
- Create-session dialog: replace the plain cwd text field with an editable
  dropdown (Material 3 `ExposedDropdownMenuBox` with an editable text field):
  - menu items: workspace `name` (or `root` when name is empty) as label,
    `root` as supporting text;
  - text remains freely editable for paths with no existing workspace;
  - default content: the root of the most recently used workspace (max
    `last_opened_at`), replacing the hardcoded `/home/ubuntu` default;
  - existing validation (non-empty absolute path) and error handling
    (snackbar, input preserved) unchanged.
- If the workspaces fetch fails or returns empty, fall back to today's behavior:
  plain text field pre-filled with `/home/ubuntu`, no error surfaced.

## Acceptance criteria

- [x] Opening the create-session dialog shows the most recently used workspace's
  root pre-filled (verifiable against `GET /api/v1/workspaces` ordering /
  `last_opened_at`), and the dropdown lists all server workspaces with their
  roots. *(Unit-tested in `SessionListViewModelTest`; verified on-device by
  Louis 2026-07-22.)*
- [x] Selecting a workspace from the dropdown and confirming creates a session
  with that workspace's root as cwd (session appears in the web client).
  *(Unit-tested: select-then-confirm posts the workspace root as cwd;
  verified on-device by Louis 2026-07-22.)*
- [x] Typing a custom absolute path still works; empty/relative input still
  shows the inline validation error and sends no request. *(Existing
  `SessionListViewModelTest` validation/create tests kept green; a typed path
  is never clobbered by the late-arriving workspaces fetch — tested with
  `setBodyDelay`.)*
- [x] With the workspaces endpoint failing (e.g. MockWebServer 500), the dialog
  falls back to the plain text field with the `/home/ubuntu` default.
  *(Unit-tested for both HTTP 500 and empty-list responses.)*
- [x] `docs/server-api.md` documents `GET /api/v1/workspaces`. *(New
  "Workspaces" section, verified against server 0.28.1.)*
- [x] `./gradlew :app:assembleDebug` succeeds with unit tests covering the new
  ViewModel/repository logic (sorting, default selection, fallback).
  *(2026-07-22: 113 unit tests green — 1 new in `SessionsRepositoryTest`, 5 new
  in `SessionListViewModelTest`; release APK published to the file server.)*

## Out of scope

- Creating, renaming, or deleting workspaces from the app.
- Filesystem browsing (`fs:*` endpoints) — still deferred.
- Persisting an app-side "last used" choice independent of the server's
  `last_opened_at`.
- Model picker / default-model handling for phone-created sessions (known
  follow-up from ticket 008's notes).

## Notes

- Live response sample (2026-07-22): items look roughly recency-ordered but do
  not rely on server ordering — sort client-side by `last_opened_at`.
- Observed edge case: a workspace with empty `name` (root `/`) exists on this
  server — label must fall back to `root`.
- Current dialog implementation: `SessionListViewModel` (new-session state,
  `newSessionCwdError`) and `SessionsScreen.kt`.
