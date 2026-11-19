# Move the grouped session list to `/api/v1/sessions`

- **ID:** 207
- **Created:** 2026-11-19
- **Depends on:** 206

## Context

Server 2.7.0 (nightly drift run, 2026-11-19; commit `f538d534`) deleted the
whole `/api/v2` session plane: `GET /api/v2/sessions` is a plain HTTP 404
(verified live), as are the v2 batch archive/restore routes. The grouped
projection moved to the v1 list: `GET /api/v1/sessions?view=by_workspace`
with `group_page_size` instead of `group.page_size`.

`SessionsRepository.listSessionGroups` still calls
`/api/v2/sessions?view=by_workspace&group.page_size=5` and decodes the v2
DTOs (`SessionGroupDto`, `SessionV2Dto`, `SessionV2WorkspaceDto`,
`SessionV2MetaDto`, `SessionV2ActivityDto`), which no longer have a source.
The grouped screen therefore fails its load on 2.7.0.

The v1 grouped shape is simpler: each group is
`{workspace_id, sessions:[<session object>], total, host_id?}` — no nested
workspace object, ISO timestamps, and the same session object ticket 206
adapts.

## Description

- Change `listSessionGroups` to
  `GET /api/v1/sessions?view=by_workspace&group_page_size=5`.
- Replace the v2 group DTOs with v1 ones:
  `SessionGroupListData { groups, total, has_more, next_page_token }` and
  `SessionGroupDto { workspace_id, sessions: List<SessionDto>, total }`;
  decode sessions through the ticket-206 `SessionDto.toDomain()`.
- Derive the domain `SessionGroup.cwd` from the group's first session
  (all sessions of a workspace share its root in practice); fall back to the
  `workspace_id` when the group is empty. Keep `SessionGroup.workspaceId` and
  `total`.
- Delete the now-unused v2 DTOs and their mapping
  (`SessionsRepository.SessionV2Dto.toDomain`).
- Keep the existing error-boundary contract (`Result.failure` of
  `DataError`).

## Acceptance criteria

- [ ] The grouped load issues exactly one request to
      `/api/v1/sessions?view=by_workspace&group_page_size=5` (MockWebServer
      assertion) and no request to `/api/v2/sessions`.
- [ ] A fixture with two groups maps each `workspace_id`, its sessions (title,
      cwd, activity, pending interaction) and its `total`; the group label
      uses the workspace root basename through the existing display rules.
- [ ] An empty group yields `cwd = workspaceId` without crashing.
- [ ] No `SessionV2*` DTO or `/api/v2` path remains in the app.
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- Group pagination beyond the first page (unchanged behavior).
- Batch archive/restore.
- Using the new v1 filters (`activity`, `sort`, `fields`) anywhere.

## Notes

- `docs/server-api.md` → Sessions → List (grouped answer) and Removed: the v2
  session table.
- Ticket 206 must land first: the group items are the 2.7 session object.
