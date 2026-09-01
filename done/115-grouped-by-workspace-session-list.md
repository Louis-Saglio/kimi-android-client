# Grouped-by-workspace session list view

- **ID:** 115
- **Created:** 2026-09-01
- **Depends on:** 114

## Context

Product discussion with Louis (2026-09-01). Louis wants the sessions list
groupable by workspace — a toggle between the current flat list and a grouped
view.

Louis challenged the initial assumption that the v2 API drops
pending-interaction state, and asked for evidence from the official web
client. Bundle investigation (0.39.1 `dist-web`, on the VPS) plus live probes
(2026-09-01) established the web client's design:

- Grouped list: `GET /api/v2/sessions?view=by_workspace&group.page_size=5`
  (+ `meta.has_prompt=true` — the web client hides empty sessions; we do not,
  see Out of scope). It then paginates the remaining group pages in the
  background. `page_size` counts *groups* per page (default 50 — Louis has 15
  workspaces, one request covers them).
- **v2 `activity.status` carries the pending-interaction state**: the web
  client treats `running` / `approval` / `question` as live states, and
  `"running"` was verified live today. Our docs claiming "observed only as
  idle" are stale — this ticket fixes them.
- For sessions in a live state the web client additionally fetches the v1
  session detail to heal precise `busy`/`pending_interaction`; our rows need
  nothing beyond `activity.status` (dot and badge), so we skip that fetch.
- Group expansion ("show more") goes through **v1**
  `GET /api/v1/sessions?workspace_id=<id>&before_id=<last id>` — ticket 116.

Client-side grouping of the flat v1 list was considered and rejected: cursor
pagination makes groups incomplete and unstable as pages load — the grouped
view would reshuffle while scrolling.

## Description

- **Toggle**: a top-bar action switches flat ↔ grouped; the choice persists
  via the existing `SettingsRepository` Preferences DataStore and is read at
  start. Flat stays the default.
- **Data**: `SessionsRepository.listSessionGroups(settings)` →
  `GET /api/v2/sessions?view=by_workspace&group.page_size=5`. New v2 DTOs:
  item = `{id, workspace:{id,cwd}, meta:{title, updated_at (epoch ms), …},
  activity:{status}}`; data = `{groups:[{workspace, sessions, total}],
  has_more, next_page_token, total}`. Domain: `SessionGroup(workspaceId, cwd,
  sessions: List<Session>, total: Int)`, items mapped onto the existing
  `Session` (title blank→null, `cwd = workspace.cwd`, `updatedAt` from
  `meta.updated_at` ms, `isRunning = status == "running"`,
  `isAwaitingInteraction = status == "approval" || status == "question"`).
  One request only: if `has_more` is true (>50 groups), the remaining groups
  are simply not shown — see Out of scope.
- **ViewModel**: `UiState` gains the view mode and `groups`. Pull-to-refresh /
  Retry in grouped mode re-fetch the groups. The existing live machinery
  applies by session id regardless of mode: `work_changed` / `meta.updated`
  patch the row inside its group (never moving it), `event.session.archived`
  drops it, the 086 activity bump moves the session to the top of *its* group
  (groups themselves do not reorder), the create-insert prepends to the
  matching group.
- **UI**: the grouped `LazyColumn` emits one header item per group — the 114
  workspace label computed over the groups' cwds, plus the group's `total` —
  followed by its session rows (same `SessionRow`; in grouped mode the
  supporting line keeps the bare date, the workspace label is the header).

## Acceptance criteria

- [x] The toggle switches between flat and grouped and survives process death
  (DataStore-backed; ViewModel test on the persistence read).
- [x] MockWebServer test: the grouped fetch requests exactly
  `/api/v2/sessions?view=by_workspace&group.page_size=5`; group mapping turns
  `activity.status` `running` → running dot state, `approval` / `question` →
  needs-attention badge state, `idle` → neither.
- [x] ViewModel tests: socket `work_changed` / `meta.updated` patch a row
  inside its group without moving it; `SessionArchived` drops the row from its
  group; a create-insert lands at the top of the matching group; the activity
  bump reorders only within the group.
- [ ] On-device: grouped view shows all workspaces with their 5 most recent
  sessions and per-group counts; badges/dots match the web client's.
- [x] `docs/server-api.md` corrected: `activity.status` value space
  (`idle`/`running`/`approval`/`question` — `running` live-verified
  2026-09-01, the rest from the 0.39.1 web bundle) and the `workspace.id`,
  `activity.status`, `meta.has_prompt` query params.
- [x] `./gradlew :app:assembleDebug` succeeds with all unit tests green (VPS
  rules: `flock /tmp/kimi-android-gradle.lock`, `--max-workers=1`,
  `--no-daemon` when parallel).

## Out of scope

- Per-group "show more" expansion — ticket 116.
- Group-page pagination (`has_more` on groups, >50 workspaces) — not needed at
  Louis's scale; revisit if it ever appears.
- Empty-session filtering (`meta.has_prompt` / v1 `exclude_empty`, web
  parity) — not requested; the app keeps showing every session.
- The web client's per-session v1 detail fetch for live sessions —
  `activity.status` already carries everything our dot/badge render.
- Collapsible groups, group reordering, per-group actions.

## Notes

- Key files: `data/sessions/SessionDtos.kt` + `SessionsRepository.kt` (v2
  call), `data/sessions/Session.kt` (`SessionGroup`),
  `ui/sessions/SessionListViewModel.kt` (view mode + grouped state),
  `ui/sessions/SessionsScreen.kt` (toggle + grouped rendering),
  `data/settings/SettingsRepository.kt` (persisted toggle).
- v2 uses epoch-**ms** timestamps and present-but-null fields, unlike v1's ISO
  strings and absent-keys — the DTOs must not be shared between versions.
- Web bundle reference: `listSessionGroupsV2` and the group mapper in the
  0.39.1 `dist-web` bundle (investigation of 2026-09-01).
