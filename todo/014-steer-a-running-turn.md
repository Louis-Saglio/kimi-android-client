# Steer a running turn from the chat screen

- **ID:** 014
- **Created:** 2026-07-20
- **Depends on:** 013

## Context

Product discussion 2026-07-20 (parity batch A). Today, sending a prompt while
the session is busy just queues/waits; the web client can *steer* — inject a
message into the running turn to redirect the agent without aborting. On the
phone this is the difference between watching a turn go wrong for ten minutes
and correcting it in one sentence. Wire protocol is documented by ticket 013
(`docs/server-api.md`, Steer section).

## Description

- `ChatRepository`: add `steer(sessionId, text)` posting to
  `POST /api/v1/sessions/{id}/prompts:steer` with the body shape from 013,
  returning the sealed `DataError`-aware `Result` like the existing
  `sendPrompt`.
- Chat UI: while the session is busy, the composer keeps accepting input and
  its send action steers instead of prompting (match the web client's
  semantics; if 013 shows steer-only-when-busy, keep the idle path on
  `/prompts`). Make the distinction visible (button label/icon state) so the
  user knows which one will fire.
- Timeline: the steered user message must appear promptly — per 013 it
  likely arrives via `context.spliced`, which ticket 012 already renders;
  verify dedup-by-id covers it, extend if the steer path carries a different
  origin/metadata.

## Acceptance criteria

- [ ] With a turn running, sending text from the composer issues
  `prompts:steer` (unit-tested: repository records a POST to the steer path
  with the 013 body shape).
- [ ] With the session idle, the composer still posts to `/prompts`
  (unit-tested).
- [ ] The steered message shows in the timeline within ~1 s while the turn
  continues (unit-tested at ViewModel level, as in 012).
- [ ] `./gradlew :app:assembleDebug` succeeds with all unit tests green.

## Out of scope

Aborting a specific prompt (`/prompts/{id}:abort`), queued-prompt management,
any change to the existing abort button (009).

## Notes

- Reuse the `context.spliced` handling from 012; do not add a second echo
  path.
- Keep the busy-state source consistent with the existing `busy` flow in
  `ChatViewModel` (session status + `event.session.work_changed`).
