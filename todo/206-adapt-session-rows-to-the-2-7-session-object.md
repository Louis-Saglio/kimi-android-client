# Adapt session rows to the 2.7 session object

- **ID:** 206
- **Created:** 2026-11-19
- **Depends on:** none

## Context

Server 2.7.0 (nightly drift run, 2026-11-19; commits `f538d534` and
`2f33641b`) compacted the session object. Verified live: `busy`,
`main_turn_active`, `last_turn_reason`, `agent_config`, `usage`,
`permission_rules`, `message_count` and `metadata.cwd` are gone; `cwd` is
top-level; runtime state is the enum `activity:"idle"|"turn"|"background"`;
the last outcome is `last_turn_outcome:"completed"|"interrupted"|"failed"`
(absent before the first ended turn).

The app's `SessionDto` still decodes the 2.6 shape (`data/sessions/
SessionDtos.kt`): `busy` defaults to `false`, so every row renders idle even
while a turn runs; `last_turn_reason` is absent, so the interrupted badge
never lights; `metadata.cwd` is gone, so rows lose their workspace label and
the `title → cwd basename → id` fallback always falls through to the id. The
same DTO feeds the chat top bar via `SessionsRepository.getSession`.

This is breakage ticket 206 of the 2.7.0 drift; it deliberately covers only
the session object on the v1 list/get path (the grouped route is 207, the
sockets are 208).

## Description

- In `SessionDto`, replace `busy` with `activity` and `last_turn_reason` with
  `last_turn_outcome`; add top-level `cwd` and `workspace_id`. Keep
  `pending_interaction`, `archived`, `archived_at`, `title`, `updated_at` and
  the custom `metadata` map (no longer the cwd source).
- In `toDomain()` and `toArchivedDomain()`: read `cwd` from the top-level
  field; map `isRunning = activity != "idle"` (both `turn` and `background`
  are live work — `background` is a new visible state the old `busy` could
  not distinguish); map `isAwaitingInteraction` through the existing
  `isAwaitingInteraction(pendingInteraction)` helper, treating an absent
  field as `"none"`; map `isLastTurnInterrupted = lastTurnOutcome ==
  "interrupted"`.
- Update `SessionDto`'s doc comments to the 2.7 field set; delete comments
  that describe `busy`/`last_turn_reason`/`metadata.cwd`.
- Refresh the unit-test fixtures that build session JSON.

## Acceptance criteria

- [ ] A list/get fixture with `"activity":"turn"` maps to `isRunning = true`;
      one with `"activity":"background"` is also `isRunning = true`; one with
      `"idle"` is false.
- [ ] A fixture with `"last_turn_outcome":"interrupted"` sets
      `isLastTurnInterrupted`; `"completed"`, `"failed"` and an absent field
      leave it false.
- [ ] A fixture with top-level `"cwd":"/home/u/project"` and no `metadata`
      maps to `Session.cwd = "/home/u/project"`; the display name falls back
      to `project`.
- [ ] An absent `pending_interaction` maps to `isAwaitingInteraction = false`.
- [ ] The archived mapping (`toArchivedDomain`) also reads the top-level cwd.
- [ ] `:app:testDebugUnitTest` passes; no reference to `busy` or
      `last_turn_reason` remains in the session DTO or its tests.

## Out of scope

- A distinct badge for `last_turn_outcome:"failed"` (the existing badge means
  "interrupted"; a failed-turn badge is a separate UX decision).
- Showing `last_prompt` or `session.git` in the row.
- The grouped v1 route (ticket 207) and the WebSocket frames (ticket 208).

## Notes

- `docs/server-api.md` → Sessions → List.
- The new value space is a small improvement: `background` lets the list show
  detached sub-agent work, and `last_turn_outcome` replaces the `cancelled`
  spelling with `interrupted`.
