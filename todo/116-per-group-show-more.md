# Per-group "show more" in the grouped session list

- **ID:** 116
- **Created:** 2026-09-01
- **Depends on:** 115

## Context

Ticket 115's grouped view shows the first 5 sessions per workspace (web
parity). Louis's biggest workspace has 165 sessions (verified live
2026-09-01), so most sessions are unreachable in grouped mode without
expansion. The official web client expands a group through the **v1** list
endpoint with a workspace filter
(`GET /api/v1/sessions?workspace_id=<id>&before_id=<last loaded id>`); we
follow the same path — the repository already speaks v1 cursor pagination for
the flat list, so this reuses an existing, tested code path instead of growing
a second one.

## Description

- `SessionsRepository.listSessions` gains an optional `workspaceId` parameter,
  sent as the `workspace_id` query param alongside the existing `before_id`
  cursor.
- ViewModel: per-group expansion state — the group footer appears when the
  group's loaded sessions < its `total`; tapping it loads the next page
  (`page_size=20`, matching the flat list's page size) and appends, deduped by
  session id. An in-flight guard per group (job, not a state flag — same
  re-entrancy reasoning as ticket 010). A failed load keeps the group as-is
  and shows an inline footer error with retry, mirroring the flat list's
  footer pattern. The footer disappears once loaded == `total`.
- Socket patches and archive drops keep working on expanded rows — they
  already apply by session id.

## Acceptance criteria

- [ ] MockWebServer test: expansion requests
  `/api/v1/sessions?workspace_id=<id>&before_id=<last id>` with the group's
  last loaded session id; appended sessions dedup by id.
- [ ] ViewModel tests: the footer shows only while loaded < `total`; rapid
  double-tap fires one request; failure keeps the group intact with an inline
  retry; success at `total` removes the footer.
- [ ] On-device: expanding the 165-session workspace pages through its history
  20 at a time until the footer disappears.
- [ ] `./gradlew :app:assembleDebug` succeeds with all unit tests green (VPS
  rules: `flock /tmp/kimi-android-gradle.lock`, `--max-workers=1`,
  `--no-daemon` when parallel).

## Out of scope

- Flat-mode changes (the flat list keeps its own end-of-scroll pagination).
- `exclude_empty` on the expansion call (the web client sends it; we keep
  showing every session, consistent with 115).
- Collapsing an expanded group.

## Notes

- Key files: `data/sessions/SessionsRepository.kt` (`workspaceId` param),
  `ui/sessions/SessionListViewModel.kt` (per-group expansion),
  `ui/sessions/SessionsScreen.kt` (group footer).
- v1 `workspace_id` is documented in `docs/server-api.md` (Sessions, List) and
  is what the web client's per-workspace load-more sends (0.39.1 bundle).
