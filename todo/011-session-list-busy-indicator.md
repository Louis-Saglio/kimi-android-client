# Fix session-list running indicator for server 0.27.0

- **ID:** 011
- **Created:** 2026-07-19
- **Depends on:** none

## Context

Server 0.27.0 (`backend: v2`) removed the `status` string from the session
object in favour of `busy` / `main_turn_active` / `pending_interaction` /
`last_turn_reason` (docs/server-api.md). `SessionsRepository` maps
`isRunning = status == "running"`, which now always reads false: the session
list never shows a session as busy, even mid-turn. Silent degradation found
during ticket 009; the chat screen's equivalent (`/status` endpoint) was
already fixed there.

## Description

- In `SessionDto`, replace `status: String` with `busy: Boolean = false`.
- In `SessionsRepository.toDomain()`, map `isRunning = busy`.
- Update `SessionsRepositoryTest` / `SessionListViewModelTest` fixtures to the
  0.27.0 session shape (`busy: true/false` instead of `status: "running"`).

## Acceptance criteria

- [ ] A session that is mid-turn shows the running indicator in the list
  (unit-tested: envelope with `"busy": true` maps to `isRunning = true`).
- [ ] `./gradlew :app:assembleDebug` succeeds with all unit tests green.

## Out of scope

Surfacing `pending_interaction` / `last_turn_reason` in the list UI (no design
need identified), the chat-screen message-echo regression (covered by 012).

## Notes

- Verified live 2026-07-19: `GET /api/v1/sessions` returns the new shape; see
  docs/server-api.md "Sessions" section for the full object.
