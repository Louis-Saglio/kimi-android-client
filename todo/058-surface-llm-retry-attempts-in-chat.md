# Surface LLM retry attempts in chat

- **ID:** 058
- **Created:** 2026-07-30
- **Depends on:** none

## Context

Server 0.31.0 added a new WebSocket event, `turn.step.retrying`, emitted when a
step's LLM call fails and is retried (observed live on provider 429 overloads;
`docs/server-api.md`, WebSocket section, verified 2026-07-30). Payload:
`turnId`, `step`, `stepId`, `failedAttempt`, `nextAttempt`, `maxAttempts`
(10), `delayMs`, `errorName`, `errorMessage`, `statusCode`, `agentId`,
`sessionId`. After each retry a fresh `turn.step.started` with a new stepId
re-fires for the same step number.

The app ignores the event entirely (`SessionSocket`'s `when (frame.type)` has
no branch for it), so during a retry storm — up to 10 attempts with backoff —
the chat looks stalled: no streaming, no error, spinner forever. Louis hits
429 overloads often enough that upstream added this event; surfacing it is the
difference between "server is overloaded, retrying (attempt 3/10)" and
apparent hang.

## Description

1. **Socket.** Add a `turn.step.retrying` branch in `SessionSocket`: decode
   the payload (all fields above, `delayMs` is a double) and emit a
   `SessionEvent.StepRetrying(...)`. Unknown/extra fields must remain ignored.
2. **UI state.** `ChatViewModel` maps it to a transient, non-bubble status on
   the in-flight assistant turn, e.g. a `retry: (attempt, maxAttempts,
   errorMessage)?` field on the streaming state, cleared by the next
   `turn.step.started`/`turn.step.completed`/turn end for that step. Reuse the
   same "status line" affordance already used for streaming/thinking if one
   exists — no new screen, no snackbar per retry.
3. **Main agent only.** Sub-agent-scoped retrying frames route to
   `SubagentStreamStore` like other transcript events; rendering them on the
   045 page is optional — dropping them with the usual `Log.w`-free debug path
   is acceptable for v1.
4. **Tests.** Unit test: a `turn.step.retrying` frame followed by a fresh
   `turn.step.started` leaves the UI in "retrying attempt N/10" then back to
   normal streaming, with no duplicate step rows.

## Acceptance criteria

- [ ] A `turn.step.retrying` WS frame (fixture pinned from the doc's verbatim
      capture) decodes and produces a visible "retrying (attempt N of M)"
      indicator on the affected turn; verified by a new unit test.
- [ ] The indicator clears when the replacement `turn.step.started` (new
      stepId, same step number) or any turn-terminal event arrives; covered by
      the same test.
- [ ] `./gradlew :app:testDebugUnitTest` passes with no changes to existing
      tests (the event is additive; 0.29.x servers never send it).

## Out of scope

- Exponential-backoff countdown UI, cancel-retry actions, per-attempt history.
- Sub-agent page (045) rendering of retry status.
- Mapping specific `errorName`/`statusCode` values to custom messages.

## Notes

- Verbatim payload and the re-fired-`turn.step.started` behavior:
  `docs/server-api.md`, WebSocket section (0.31.0).
- Handler location: `SessionSocket.kt` `when (frame.type)`; mapper pattern:
  `ChatViewModel`'s existing delta/status handling.
