# Create sessions with workspace_root

- **ID:** 217
- **Created:** 2026-11-25
- **Depends on:** none

## Context

Server 2.8.0 (nightly drift run, 2026-11-25; kap-server commit `13b09d99`)
named the session's project root: `POST /api/v1/sessions` accepts a top-level
`workspace_root`, the session object returns it (plus `environment_id`), and
the wire schema marks the old `cwd` field deprecated. Verified live: create
with `{"workspace_root":"/path/to/project"}` returned
`workspace_root:"/path/to/project"`, `cwd:"/path/to/project"` (deprecated,
echoed for compatibility) and `environment_id:"local"`. The legacy
`{"metadata":{"cwd":…}}` body still works and derives the same workspace, but
when several spellings are present they must agree — a mismatch answers
`40001` (`"cwd: cwd (/x) must equal workspace_root (/y)"`).

The app still creates every session through the legacy shape:
`SessionsRepository.createSession` (`app/src/main/java/fr/louissaglio/kimi/
android/data/sessions/SessionsRepository.kt:114`) builds
`{"metadata":{"cwd": <root>}}`. The repo's rule is to follow the latest server
with no compatibility shims, so the create path should use the canonical field
before the deprecated one is removed.

## Description

- In `SessionsRepository.createSession`, send `workspace_root` instead of the
  nested `metadata.cwd` object; keep the optional `title` exactly as today.
- Delete the now-dead cwd-in-metadata body builder and any comment that
  describes it; the response mapping (`SessionDto.toDomain`) is unchanged
  here (ticket 206 owns reading the new session fields).
- Update the repository unit-test request-body assertions to the new body.

## Acceptance criteria

- [ ] `createSession` posts a body containing `"workspace_root":"<root>"` and
      no `metadata` key; a `title` is still included when supplied.
- [ ] A create against the live server answers `code:0` and the returned
      session's `workspace_root` equals the requested root.
- [ ] The repository test that inspects the outgoing body passes with the new
      shape; `:app:testDebugUnitTest` passes.

## Out of scope

- Reading `workspace_root`/`environment_id` from the session object in the
  list/chat (ticket 206).
- Choosing a remote `environment_id` or sending `environment_cwd` (the app
  only targets the server's local environment).
- The grouped session list (ticket 207) and any create-screen redesign.
- Keeping a fallback to `metadata.cwd` for older servers (the app only runs
  against the latest server).

## Notes

- `docs/server-api.md` → Sessions → Create and the 2.7.3→2.8.0 drift block.
- Live: `metadata.cwd` and `workspace_root` are both accepted and must agree;
  the response carries both, with `cwd` deprecated.
