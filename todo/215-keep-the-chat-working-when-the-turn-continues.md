# Keep the chat working when the turn continues

- **ID:** 215
- **Created:** 2026-11-25
- **Depends on:** 198

## Context

Server 2.8.0 (nightly drift run, 2026-11-25; kap-server commits `ba2491ca`
and `ecc92e33`) replaced the engine-internal `loopEnd` with a public turn
field: every `turn.ended` (and the REST turns page) may now carry
`continuation:"turn"|"background"|"none"`. Verified live:

- a `sleep` turn that a steered prompt followed ended `continuation:"turn"`
  and the next `turn.started` arrived immediately;
- a turn that launched a `run_in_background:true` sub-agent ended
  `continuation:"background"`, with the session frame at
  `activity:"background"` and `last_turn_outcome:"completed"`;
- an ordinary finished turn and a turn that failed before the model answered
  ended `continuation:"none"`.

The app treats every `TurnEnded` as the end of work:
`ChatViewModel.applySessionEvent` (`ui/chat/ChatViewModel.kt:1314`) sets
`isWorking = false`, clears `runningTool` and `agentActiveSince`, and
`ChatTimeline.onTurnEnded` closes the footer. That is correct only for
`continuation:"none"`. With `"turn"` the composer flashes idle between two
chained turns (the Stop affordance disappears and reappears); with
`"background"` the chat looks finished while a detached sub-agent is still
running, and a user who leaves the app has no signal that work continues.
Upstream fixed the same bug for its completion notifications in `ecc92e33`.

## Description

- After ticket 198 decodes `turn.ended`, add `continuation: String? = null`
  to `SessionEvent.TurnEnded` (and the turn header DTO) and carry it into
  `ChatViewModel.applySessionEvent`.
- On `continuation == "turn"`: keep `isWorking = true`; do not clear
  `agentActiveSince`; leave the footer's interrupted action in place until the
  next `turn.started` replaces it. The next turn's frames then update the
  timeline normally.
- On `continuation == "background"`: set `isWorking = false` (the main turn is
  over) but keep a separate "background work" indicator — reuse the existing
  busy derivation from the `agent` frame / `session` activity, which already
  reports `activity:"background"`; do not show the early-stop footer for a
  completed turn.
- On `"none"` or an absent field: existing behaviour (treat absent as
  `"none"` — the field is optional and older sessions do not have it).
- Cover the three values plus the absent case in the ViewModel/timeline unit
  tests.

## Acceptance criteria

- [ ] A `turn.ended` with `continuation:"turn"` leaves the composer's working
      state true and the Stop affordance visible until the following
      `turn.started` (no idle frame in between).
- [ ] A `turn.ended` with `continuation:"background"` clears `isWorking` but
      keeps the session's background-work indicator on; the composer accepts
      a new prompt.
- [ ] `continuation:"none"` and an absent field behave exactly as today.
- [ ] No early-stop footer or interrupted badge is shown for a completed turn
      regardless of its continuation value.
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- Push notifications / completion alerts (the app has no notification
  surface; this ticket only fixes the in-app working state).
- Changing the session list's `activity` rendering (ticket 206 owns the
  session object; `background` already maps to running there).
- Inferring continuation from timing or from the `agent` frame — the field is
  authoritative.
- The turn/item stream rewrite itself (ticket 198).

## Notes

- `docs/server-api.md` → Conversation → Turns (`continuation`) and Live
  conversation frames, plus the 2.7.3→2.8.0 drift block.
- Live capture: the background-spawn turn ended
  `{"outcome":"completed","continuation":"background"}` and the completion
  arrived as a new turn ending `continuation:"none"`; the steered-prompt case
  ended `{"outcome":"completed","continuation":"turn"}`.
- `SessionEvent.TurnEnded` is at `data/chat/ChatEvents.kt:80`.
