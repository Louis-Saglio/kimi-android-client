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

- [x] Triggering a tool that needs approval (from either client) shows the card
  on the phone within ~1 s; Allow unblocks the tool (visible in both clients),
  Deny cancels it.
  *(Mechanics verified end-to-end, 2026-07-19: the event→UiState mapping and
  both decision POST bodies are unit-tested in `ChatViewModelTest`; the full
  approve and reject cycles were re-verified against the live server (approve →
  `resolved` + tool runs, reject → `resolved` + no tool result for the rejected
  command). Manually verified by Louis on-device, 2026-07-19.)*
- [x] Resolving the same request from the web client clears the card on the
  phone.
  *(Card removal is driven solely by `event.approval.resolved` — same frame for
  local and remote decisions — unit-tested in `ChatViewModelTest`; the frame's
  live shape (`approval_id`, `decision`, `resolved_at`) verified against the
  server 2026-07-19. Manually verified by Louis on-device, 2026-07-19.)*
- [x] `./gradlew :app:testDebugUnitTest` passes, including the new ViewModel
  tests. *(2026-07-19: 25 tests green, 5 new in `ChatViewModelTest`.)*
- [x] `./gradlew :app:assembleDebug` succeeds. *(2026-07-19; Firebase Test Lab
  robo passed on MediumPhone.arm-34; APK published to the file server.)*

## Out of scope

Permission-rule management, yolo/auto permission modes, plan-mode approval UX
(deferred), any notification when the app is backgrounded/closed (push —
deferred, no ticket yet).

## Notes

- Load `android-skills:android-testing` for the ViewModel tests.
- 2026-07-19: the protocol does expose an "always allow"-style option
  (`scope: "session"` in the decision body), but what exactly the remembered
  rule covers (tool? arguments?) is undocumented — deliberately not surfaced in
  the UI; revisit with a dedicated ticket if wanted.
- 2026-07-19: fresh sessions created via bare REST have `agent_config.model`
  empty and turns fail with `model.not_configured`; pass `model` as a
  per-prompt override (as the live verification did) if a future flow needs it.
