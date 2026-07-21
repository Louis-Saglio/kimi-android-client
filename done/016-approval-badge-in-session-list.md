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

- [x] Envelope with `"pending_interaction": "approval"` maps to the domain
  flag (unit-tested in `SessionsRepositoryTest`).
  *(`envelope items are mapped to domain sessions`: the first fixture item
  carries `"pending_interaction":"approval"` and asserts
  `isAwaitingApproval = true`; the second omits the field and asserts the
  `"none"` default maps to `false`.)*
- [x] A session pending approval shows the badge in the list; one with
  `"none"` does not (unit-tested at ViewModel level, or a semantics test if a
  Compose test harness exists by then).
  *(Both. ViewModel level: `SessionListViewModelTest.approval flag comes from
  the latest fetched payload only`. Semantics: `SessionRowTest` (androidTest)
  — approval-pending row shows the "Approval needed" badge and not the
  running dot, `"none"` shows neither, busy-only shows the running dot.)*
- [x] Badge reflects the latest fetched payload only (no locally latched
  state surviving a refresh).
  *(`approval flag comes from the latest fetched payload only` reloads with
  the approval resolved and asserts the flag clears.)*
- [x] `./gradlew :app:assembleDebug` succeeds with all unit tests green.
  *(2026-07-21: 79 unit tests green; assembleDebug + assembleDebugAndroidTest
  successful; Firebase Test Lab instrumented run passed 7/7 on
  MediumPhone.arm-34.)*

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
- Implementation: `Session.isAwaitingApproval` maps
  `pending_interaction == "approval"`. The badge is an M3 `Badge` with `!`
  (error colour = needs attention) with content description "Approval
  needed"; it takes precedence over the running dot since a session blocked
  on an approval is not making progress. `SessionRow` is `internal` so the
  androidTest can compose it directly.
