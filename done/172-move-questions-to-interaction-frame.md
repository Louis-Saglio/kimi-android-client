# Move the question wizard onto the `interaction` frame

- **ID:** 172
- **Created:** 2026-10-28
- **Updated:** 2026-11-04 for server 2.5.0
- **Depends on:** 171

## Context

Found by the nightly server drift run from 2.3.3 to 2.4.0 on 2026-10-28
(server 2.4.0, upstream commit `461c6e92`, the same change that moved
approvals). The `event.question.*` family and every `/questions` route are
gone: questions now arrive as the flat `interaction` frame with
`kind:"question"`, and are answered or dismissed through
`POST /api/v1/sessions/{id}/interactions/{interaction_id}`.

The app is fully cut off from live questions:

- `SessionSocket` handles `event.question.requested` (and `.answered`/
  `.dismissed`), which no longer exist; questions have no v2 duplicate, so
  nothing at all reaches `QuestionWizard`.
- `ChatApi.answerQuestion` and `dismissQuestion` POST to removed routes and
  now get plain HTTP 404s; `pendingQuestions` GETs the removed
  `?status=pending` route, so the reconnect/turn-end `refresh()` and the
  screen-start `seed()` also fail.
- The snapshot's `pending_questions` still seeds the wizard on a cold open
  (old item shape, unchanged), but the card never updates or clears live and
  cannot be answered.

Live evidence: the question surfaced only as `interaction`; the old answer
route answered 404; the new route returned
`{outcome:"answered", ended_at}` and an `status:"ended"` frame whose
`response.answers` was keyed by question text (verbatim captures in
`docs/server-api.md`, Interactions).

**2.5.0 update (nightly drift run 2026-11-04).** The `interaction` frame is
unchanged, but it now arrives only on a `subscribe_v2` subscription, and
`GET /snapshot` is a 404. Pending questions rebuild from
`transcript.reset.snapshot.interactions` / `GET .../transcript.interactions`
instead of the removed snapshot, and the transcript `interaction.upsert` op
mirrors every state change. This ticket is blocked on ticket 175/171; replace
the snapshot seed (item 4) with that transcript source.

## Description

1. Reuse the interaction-frame decoder introduced in ticket 171. For
   `kind:"question"`:
   - `status:"pending"` → `SessionEvent.QuestionRequested`, mapping
     `interaction_id` to `questionId`, `tool_call_id` to `toolCallId`,
     `agent_id` to the asker, and `request.questions[]` through the existing
     `QuestionItemDto` (the item shape is unchanged).
   - `status:"ended"` → `SessionEvent.QuestionResolved` for every outcome
     (`answered`/`dismissed`/`cancelled`) so the card clears for our own
     decisions and for an abort.
2. Replace the three `ChatApi` question calls with one interaction POST:
   - answer: `{"kind":"question","answers":{"q_0":{"kind":…,...}}}` — the
     existing per-item answer mapping in `QuestionWizard.onSubmit` is
     unchanged; only the URL and the outer body change.
   - dismiss: `{"kind":"question","dismissed":true}` — success is now
     `code:0` with `outcome:"dismissed"`; delete the `40909` special case in
     `ChatApi.dismissQuestion` and in its comments.
3. Delete the removed `GET .../questions?status=pending` call and its
   callers: `QuestionWizard.refresh()` (reconnect and `turn.ended`),
   `QuestionWizard.seed()` (screen start), and the matching test fakes. Pending
   questions now rebuild from (a) the snapshot on cold open/resync and (b) the
   server's pending re-send after every accepted subscribe ack. Apply the ack
   reset from ticket 171 to the question queue too, then let the re-sent
   `interaction` frames refill it; dedupe by `question_id`.
4. Seed pending questions from `transcript.reset.snapshot.interactions` (or
   `GET .../transcript.interactions`) on a cold open; both carry the full
   question `request` entity. The removed snapshot's `pending_questions` path
   is deleted.
5. Keep `POST .../profile`/permission behavior untouched; this is only the
   question transport.

## Acceptance criteria

- [ ] A live `AskUserQuestion` call surfaces the wizard in an open chat with
      no REST poll.
- [ ] Answering a single-select item posts
      `{"kind":"question","answers":{"q_0":{"kind":"single","option_id":…}}}`
      to `/sessions/{id}/interactions/{question_<uuid>}`; the card clears on
      the `status:"ended"` frame with `outcome:"answered"` and the turn
      continues with the same flattened answer text.
- [ ] Multi-select and free-text answers still map to
      `multi`/`other`/`multi_with_other` as today.
- [ ] Dismiss posts `{"kind":"question","dismissed":true}` and clears the
      card on `outcome:"dismissed"` with no error surfaced (no `40909`
      special case remains).
- [ ] Re-answering an ended question surfaces the `40902` message; an
      unknown id surfaces `40404`.
- [ ] Aborting a turn with a pending question clears the card via the
      `outcome:"dismissed"` frame.
- [ ] Reconnecting while a question is pending rebuilds exactly one card from
      the post-ack re-send; no `/questions` request is issued anywhere.
- [ ] A cold open with a pending question seeds one card from the transcript
      reset interactions (or `GET .../transcript`).
- [ ] Unit tests cover question frame decoding, the POST body/URL, the ack
      reset, and removal of the poll; `:app:testDebugUnitTest` passes.

## Out of scope

- Approvals — ticket 171.
- Showing the plan-review body — ticket 173.
- Question-item fields the app never decoded (`body`, `other_label`,
  `other_description`) — unchanged, not part of this release.
- `method`/`note` body fields and the `skipped` answer kind (never observed
  live).
- Any compatibility path for servers before 2.4.0.

## Notes

Relevant code: `data/chat/SessionSocket.kt`, `data/chat/ChatDtos.kt`
(`QuestionRequestedPayload`, `QuestionListData`), `data/chat/ChatApi.kt`
(`pendingQuestions`, `answerQuestion`, `dismissQuestion`),
`data/chat/ChatRepository.kt`, `ui/chat/QuestionWizard.kt` (`onSubmit`,
`onDismiss`, `onRequested`, `onResolved`, `refresh`, `seed`,
`applySnapshot`). Wire reference: `docs/server-api.md` → Interactions.

## Implementation notes

**2026-11-06 —** `SessionSocket` now routes every flat `interaction` frame through
`ChatMessageMapper.toInteractionEvent`, which dispatches on `kind` to ticket 171's
`toApprovalEvent` or the new `toQuestionEvent`: a question `pending` →
`SessionEvent.QuestionRequested` (question id = `interaction_id`, `tool_call_id` links the
`AskUserQuestion` row, `agent_id` is the asker, `request.questions[]` through
`QuestionItemDto`), `ended` → `QuestionResolved` for every outcome. The removed
`event.question.requested` payload DTO became `QuestionRequestDto` (the request of both
the frame and the transcript entity; the asker comes from the frame only), and
`QuestionListData` is deleted. Each connection's `FrameContext` also collects the pending
questions of its `transcript.reset` snapshots (`ChatMessageMapper.pendingQuestions`, shared
with the REST transcript's cold seed), and an accepted ack emits the new
`SessionEvent.QuestionsReset(pending)` after `ApprovalsReset` (empty on a resumed
subscribe). `ChatApi.answerQuestion`/`dismissQuestion` POST
`{"kind":"question","answers":{…}}` / `{"kind":"question","dismissed":true}` to
`/api/v1/sessions/{id}/interactions/{questionId}` through one `postInteraction` helper
(shared with approvals); the `40909` special case is gone, and `40404`/`40902` surface as
`DataError.Api` with the server message.

- `QuestionWizard`: `refresh()` (reconnect + `turn.ended`) and `seed()` (screen start) are
  deleted with `ChatApi.pendingQuestions`/`ChatRepository.getPendingQuestions`; the cold
  seed stays ticket 176's `GET .../transcript` interactions (`applyColdStart`). New
  `onReset` replaces the queue; `onRequested` dedups by question id in place. A resumed
  subscribe's reset is empty, so the head question disappears until its re-send a moment
  later — `onReset` stashes the dropped head's drafts (selections, other texts, step,
  minimized) and `onRequested` restores them when the re-sent frame makes it the head
  again, so a network blip never wipes a half-filled wizard (the old poll-based refresh
  kept drafts the same way).
- Tests: `SessionSocketTest` (pending/ended per outcome with the verbatim shape, sub-agent
  asker + `multi_select` forms, undecodable/foreign/unknown-kind frames ignored, the ack
  question reset from reset snapshots with an answered entity and a child's duplicate
  list, resumed/refused/foreign acks), `ChatMessageMapperTest` (`pendingQuestions`
  filtering, `toQuestionEvent`, `toInteractionEvent` dispatch), `ChatRepositoryTest` (answer
  URL + body for single/other and multi/multi_with_other, 40902/40404, dismiss body + code-0
  success, the socket test expects both resets), `QuestionWizardTest` (live card with no
  REST poll, single-select POST and clear only on the `answered` frame, the multi/other/
  multi_with_other mapping, dismiss without error, 40902 and 40404 messages keep the card,
  abort clears via the `dismissed` frame with the turn end polling nothing, re-subscribe
  rebuilds exactly one card and drops one answered while away, a resumed empty reset keeps
  the head's drafts, cold open + reset + re-send shows one card). The VM harness's
  `/questions` route, `questionsBody`, `questionsResponseGate` and `answersSucceed` are
  gone: `/interactions/` answers questions by body `kind` (`questionFailureBody` overrides
  the success). `TranscriptFrames` gained `questionFrame`/`questionEntity`/`CAKE_OR_PIE`.
- Not verified live (no server access from this run): the acceptance criteria about a real
  `AskUserQuestion` call are covered by the captured frame shapes only.
