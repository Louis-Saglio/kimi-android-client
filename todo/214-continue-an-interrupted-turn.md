# Continue an interrupted turn with the session continue route

- **ID:** 214
- **Created:** 2026-11-25
- **Depends on:** 198

## Context

Server 2.8.0 (nightly drift run, 2026-11-25; kap-server commit `b695ed92`,
`session_continue` capability) added `POST /api/v1/sessions/{id}:continue`,
verified live. It resumes the latest main-agent turn when that turn ended
`interrupted`, or `failed` with an `end_reason` other than `blocked`; the body
is empty and the ACK is `{"continued":true}`. The resumed work opens a new
`agent` turn with a hidden system-reminder user message, so no visible
`user_message` row is appended — the model just carries on from the prior
turn's context. A session with no such turn answers `40940`; a busy one answers
`40901`.

Today the app can stop a turn but cannot restart it. The chat's early-stop
footer (`ui/chat/components/Bubbles.kt:221` `TurnEndFooter`, fed by
`ChatTimeline.onTurnEnded` at `ui/chat/ChatTimeline.kt:498`) explains why the
turn stopped, and the composer then accepts a brand-new prompt. A user whose
turn was cut short by a Stop tap, a lost connection, a server restart or a
provider error has to retype or rephrase the request; there is no one-tap
retry. `ChatApi` has no `:continue` call (`data/chat/ChatApi.kt`), and
`ChatRepository` exposes only prompt/steer/compact / abort.

After ticket 198 the live turn end is fed by `turn.ended`; ticket 209 moves the
Stop button to `:cancel`. This ticket adds the counterpart action on the same
footer.

## Description

- Add `ChatApi.continueSession(settings, sessionId): Result<Unit>`:
  `POST /api/v1/sessions/$sessionId:continue` with an empty JSON body, decoded
  through the same `toAckResult()` path as `cancel` / `compact`. Non-zero
  envelopes (40940, 40901) ride `DataError.Api.serverMessage` like the other
  fire-and-forget actions.
- Add `ChatRepository.continueSession(...)` delegating to it, and call
  `SessionActivityStore.record(sessionId)` on success like `sendPrompt` /
  `steer` so the session list shows the session as live again.
- In the chat UI, give `ChatItem.TurnEnd` an optional `continuable` flag and
  render a "Continue" text action in `TurnEndFooter` when the turn stopped
  with an interrupt reason (an interrupted or failed turn). Tapping it posts
  `:continue`, and the regular `turn.started`/`item.*` stream then takes over —
  do not insert a local user bubble or a fake assistant row.
- While the request is in flight disable the action (one tap only); a
  `40940`/`40901` failure surfaces the server message in the existing
  snackbar path and leaves the footer in place (a retry is still meaningful).
- Keep the footer label and the existing interrupted-turn semantics
  unchanged.

## Acceptance criteria

- [ ] A session whose last turn ended interrupted/failed shows exactly one
      "Continue" action in the turn-end footer; a completed turn shows none.
- [ ] Tapping it issues one `POST /api/v1/sessions/<id>:continue` with an
      empty body and, on `code:0`, clears the footer action once the new
      `turn.started` arrives — no local user or assistant row is inserted.
- [ ] A `40901` (busy) or `40940` (nothing to continue) response shows the
      server message and does not clear the footer action.
- [ ] Two rapid taps produce one request (the action is disabled while in
      flight).
- [ ] `ChatApi`/`ChatRepository` unit tests cover success, `40940` and `40901`;
      `:app:testDebugUnitTest` passes.

## Out of scope

- An automatic retry: the action is user-initiated; no silent re-run after a
  provider error.
- Re-sending the original prompt text as a new prompt (that would duplicate
  the user row and lose the interrupted turn's context).
- A generic "retry last turn" for completed turns (the server refuses those
  with 40940).
- Continuing a sub-agent's turn: `:continue` targets the session's main agent;
  sub-agent retries stay out of scope.
- The Stop→`:cancel` move (ticket 209) and the turn/item stream itself
  (ticket 198).

## Notes

- `docs/server-api.md` → Sessions → Continue, and the 2.7.3→2.8.0 drift block.
- Live: the interrupted turn read `turn_number:4`; the continuation read
  `turn_number:5`, and the prior turn's items stayed untouched. The ACK is
  `{"code":0,"msg":"success","data":{"continued":true}}`.
- `ChatApi` lives at `app/src/main/java/fr/louissaglio/kimi/android/data/chat/
  ChatApi.kt`; `ChatRepository` and the ViewModel action path are in the same
  package and `ui/chat/ChatViewModel.kt`.
