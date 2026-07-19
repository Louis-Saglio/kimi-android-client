# Restore sent-message echo via context.spliced

- **ID:** 012
- **Created:** 2026-07-19
- **Depends on:** none

## Context

006 made a sent prompt appear immediately via the `prompt.submitted` WS event →
history refetch. Server 0.27.0 dropped `prompt.submitted`; the user message now
arrives inline in a `context.spliced` frame right after `turn.started`
(docs/server-api.md). Net effect: a prompt sent from the phone is invisible
until the turn ends and history is refetched — the chat looks dead for the
whole turn.

## Description

- Parse `context.spliced` frames in `ChatRepository` into a new
  `SessionEvent` carrying the spliced messages (id, role, text parts are
  enough; `toolCalls` can be ignored — tool activity renders via the delta
  events).
- In `ChatTimeline`, append spliced `user` messages to the committed rows
  immediately, deduped by message id (the refetch-on-`turn.ended` replaces
  committed rows wholesale, so no long-term duplication is possible).
- Keep `SessionEvent.PromptSubmitted` handling as-is: harmless with older
  servers, simply never emitted by 0.27.0.

## Acceptance criteria

- [ ] A prompt sent from the phone shows the user bubble within ~1 s of the
  prompts POST, while the turn is still running (unit-tested at the ViewModel
  level: socket `context.spliced` frame → item appears, no HTTP refetch
  required).
- [ ] No duplicate user bubble after the `turn.ended` history refetch
  (unit-tested).
- [ ] `./gradlew :app:assembleDebug` succeeds with all unit tests green.

## Out of scope

Rendering assistant/tool messages from `context.spliced` (the delta events +
history already cover them), migrating approvals to the v2
`permission.approval.requested` event (the legacy `event.approval.requested`
is still emitted), removing the now-unneeded WS pong responder.

## Notes

- Verbatim `context.spliced` payload captured 2026-07-19 in
  docs/server-api.md ("Core event payloads"): messages arrive as full objects
  with `content` parts and `origin`.
- An aborted turn commits an assistant message with empty `content`;
  `ChatTimeline.flatten` already skips those (checked 2026-07-19) — no work
  needed there.
