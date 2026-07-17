# Permission approvals

- **ID:** 007
- **Created:** 2026-07-17
- **Depends on:** 005

## Context

Approving tool calls from the phone is the highest-value mobile flow: it
unblocks the agent while away from the desk. The exact request/decision shapes
come from ticket 001 (WS event and/or `/permission` endpoint). Push
notifications for approvals while the app is closed were explicitly deferred —
not part of this ticket.

## Description

- When the stream surfaces a permission request for the open session, show a
  persistent card above the input row: tool name, a compact summary of the key
  arguments, and Allow / Deny buttons. Extra options ("always allow"-style) only
  if the protocol exposes them.
- POST the decision per `docs/server-api.md`; clear the card on resolution.
- If the request is resolved elsewhere (web UI), clear the card without user
  action (driven by the corresponding stream event).
- Multiple queued requests: show a count and resolve in arrival order — no
  queue-management UI.
- Unit-test the ViewModel: permission event → `UiState` mapping and decision
  POST body.

## Acceptance criteria

- [ ] Triggering a tool that needs approval (from either client) shows the card
  on the phone within ~1 s; Allow unblocks the tool (visible in both clients),
  Deny cancels it.
- [ ] Resolving the same request from the web client clears the card on the
  phone.
- [ ] `./gradlew :app:testDebugUnitTest` passes, including the new ViewModel
  tests.
- [ ] `./gradlew :app:assembleDebug` succeeds.

## Out of scope

Permission-rule management, yolo/auto permission modes, plan-mode approval UX
(deferred), any notification when the app is backgrounded/closed (push —
deferred, no ticket yet).

## Notes

- Load `android-skills:android-testing` for the ViewModel tests.
