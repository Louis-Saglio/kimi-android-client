# Approval badge in the session list

- **ID:** 016
- **Created:** 2026-07-20
- **Depends on:** none

## Context

Product discussion 2026-07-20 (parity batch A). The session list payload
already carries `pending_interaction` (`"none"` | `"approval"`, documented in
`docs/server-api.md`). The app maps only `busy`. When several sessions run at
once, Louis has to open each one to find the one blocked on a permission
approval. The web client surfaces this; the phone should too — it is the
cheapest ticket in batch A and needs no spike.

## Description

- `SessionDto` + `SessionsRepository.toDomain()`: map `pending_interaction`
  (default `"none"`) onto the domain `Session`.
- Session list row: when `pending_interaction == "approval"`, show a distinct
  badge/indicator (Material 3, consistent with the existing busy indicator
  from 011 — approval-pending must read as *needs me*, not *working*).
- Ensure the badge clears on the next list load/refresh after the approval is
  resolved (no stale badge; rely on the server value, no local guessing).

## Acceptance criteria

- [ ] Envelope with `"pending_interaction": "approval"` maps to the domain
  flag (unit-tested in `SessionsRepositoryTest`).
- [ ] A session pending approval shows the badge in the list; one with
  `"none"` does not (unit-tested at ViewModel level, or a semantics test if a
  Compose test harness exists by then).
- [ ] Badge reflects the latest fetched payload only (no locally latched
  state surviving a refresh).
- [ ] `./gradlew :app:assembleDebug` succeeds with all unit tests green.

## Out of scope

Question-pending indicators (needs the 013 spike; revisit if 013 reveals a
`pending_interaction` value for questions — a follow-up ticket can extend the
same badge), approving from the list without opening the chat, push
notifications.

## Notes

- `pending_interaction` also appears live in `event.session.work_changed`;
  updating the list row in real time from the WS is *not* required here —
  the list is already refresh-driven. Flag it as a follow-up if it feels
  stale in use.
