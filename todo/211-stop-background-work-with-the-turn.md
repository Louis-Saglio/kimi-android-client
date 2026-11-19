# Stop background work with the turn

- **ID:** 211
- **Created:** 2026-11-19
- **Depends on:** 209

## Context

Server 2.7.0 (nightly drift run, 2026-11-19; commit `b9574579`; verified live)
gave `POST /api/v1/sessions/{id}:cancel` an optional `include_background`
flag. With `true`, the call also stops every **detached** task of the
targeted agents (`task.stop` semantics server-side) and still answers
`{"cancelled":true|false}`; the tasks end with a `task` frame
`status:"ended"`, `outcome:"killed"`.

On the phone this closes a real gap. A background `Agent` (or a detached
Bash) survives the end of the main turn: the chat shows the session as
working (`activity:"background"`), the Stop button posts `:cancel {}`, the
server answers `cancelled:false` because no turn is running, and the
background agent keeps going until it finishes and posts its notification.
The user's intent when tapping Stop — "stop this session's work" — is not
met, and the jobs screen still offers only per-task Stop.

The alternative of always sending `include_background:true` was chosen over a
separate "Stop all" confirmation: it is one tap, matches the button's label,
and the per-task Stop in the jobs screen still allows surgical control. The
product-owner discussion (drift run) rejected a confirmation dialog as
unnecessary friction for a reversible action (the work can be re-prompted).

## Description

- `ChatApi.cancelTurn` (ticket 209) gains an `includeBackground: Boolean`
  parameter that adds `"include_background": true` to the `:cancel` body.
- `ChatViewModel.onStop` passes `includeBackground = true` unconditionally
  (the server ignores it when there is nothing detached; `cancelled:false`
  stays a no-op success).
- Keep the jobs screen's per-task `task.stop` unchanged.
- Update the Stop-path unit test to assert the body.

## Acceptance criteria

- [ ] Tapping Stop sends `{"include_background":true}` and no
      `extra`/unknown fields.
- [ ] With a detached sub-agent running and no main turn, Stop answers
      `{"cancelled":true}` and the sub-agent's task ends `status:"ended"`,
      `outcome:"killed"` (fake server in the test; live check in the ticket's
      verification notes).
- [ ] With no background work the same call still succeeds
      (`cancelled:true` if a turn runs, `false` if idle) with no error.
- [ ] The jobs screen still stops one task without touching the turn.
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- A separate "Stop all" button or a confirmation dialog.
- Cancelling a named sub-agent from its page (`agent_id` on `:cancel`).
- Durable background execution / notifications while the app is closed.

## Notes

- `docs/server-api.md` → Cancel and WebSocket → Captured round-trips.
- Depends on 209 for the route migration; the flag itself needs no other
  ticket.
