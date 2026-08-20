# Re-link pending question cards to their tool rows after the 0.38.0 question_id split

- **ID:** 106
- **Created:** 2026-08-20
- **Depends on:** none

## Context

Before server 0.38.0, a pending question's `question_id` was identical to the
`AskUserQuestion` tool call's `tool_call_id`, and the app relied on that
equality. On 0.38.0 (verified live 2026-08-20; docs/server-api.md, Questions)
`question_id` is a dedicated interaction id (`question_<uuid>`) and the
payload carries a **distinct** `tool_call_id` alongside it.

App impact audit:

- Answer/dismiss still work: `ChatApi.answerQuestion`/`dismissQuestion` are
  keyed off the payload's `question_id`, which the server still provides.
- **Broken correlation:** `QuestionWizard.onRowTapped` compares the tapped
  tool row's `toolCallId` with `pendingQuestions.firstOrNull()?.questionId` to
  toggle/reopen the live question card (tickets 088/089). With the ids now
  diverging, tapping the pending question's tool row falls through to the
  resolved-row parser, which no-ops on a pending row — the card can no longer
  be reopened/toggled from its transcript row.
- Stale comment: `ChatDtos.QuestionRequestedPayload`'s docstring still claims
  `question_id` == `tool_call_id`.

The structural fix is to stop relying on the id equality altogether and link
the pending question to its tool row via the `tool_call_id` field the payload
now carries explicitly — not to paper over it by parsing id formats.

## Description

- Add `tool_call_id` to `QuestionRequestedPayload` (`@SerialName("tool_call_id")`,
  default `""`).
- Carry it on the pending-question model (`PendingQuestion` or the wrapper
  used by `QuestionWizard`), so the wizard can match a tapped tool row against
  `pendingQuestions.firstOrNull()?.toolCallId` instead of `questionId`.
- Keep routing `answerQuestion`/`dismissQuestion` by `question_id` (unchanged).
- Update the stale `question_id == tool_call_id` comment in `ChatDtos.kt` to
  describe the 0.38.0 split.

## Acceptance criteria

- [ ] Unit test: a pending question decoded from a verbatim 0.38.0
  `event.question.requested` payload (docs/server-api.md, WS section) exposes
  both `question_id` (`question_<uuid>`) and `tool_call_id` (`tool_…`).
- [ ] Unit test: `onRowTapped(<tool_call_id of the pending question>, …)`
  toggles the live question card (the 088/089 behavior) with divergent ids.
- [ ] Answer and dismiss flows still post to
  `/questions/{question_id}` / `:dismiss` with the `question_<uuid>` id
  (existing tests updated if they assumed equality).

## Out of scope

- Changes to the answer/dismiss REST calls themselves (they already use
  `question_id`).
- Resolved-question read-only cards (ticket 089) — they key off the tool row's
  own `toolCallId` and are unaffected.
- Any server-side workaround; the new shape is the current protocol.

## Notes

- Verbatim 0.38.0 capture: docs/server-api.md, "Core event payloads" →
  Question requested.
- `snapshot.pending_questions` and `/questions?status=pending` items carry the
  same dual-id shape.
