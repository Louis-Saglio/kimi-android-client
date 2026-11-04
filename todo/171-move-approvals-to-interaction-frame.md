# Move approval cards onto the `interaction` frame

- **ID:** 171
- **Created:** 2026-10-28
- **Updated:** 2026-11-04 for server 2.5.0
- **Depends on:** 175

## Context

Found by the nightly server drift run from 2.3.3 to 2.4.0 on 2026-10-28
(server 2.4.0, upstream commit `461c6e92`). The release replaces the
`event.approval.*` and `event.question.*` families and the
`/approvals` + `/questions` routes with one flat `interaction` frame and
`POST /api/v1/sessions/{id}/interactions/{interaction_id}`.

The Android app cannot see or answer approvals any more:

- `SessionSocket` handles `event.approval.requested`/`.resolved`; those frames
  no longer exist. It deliberately ignores the still-delivered v2
  `permission.approval.*` frames to avoid double cards, so no live event
  reaches `ChatViewModel` at all.
- `ChatApi.postApprovalDecision` posts to
  `/sessions/{id}/approvals/{approvalId}`, which is a plain HTTP 404 now.
- `GET /sessions/{id}/approvals?status=pending` (the fallback poll path) is
  gone too.

The snapshot still carries `pending_approvals` in its old item shape, so
opening a session with a pending approval still seeds a card — but decisions
fail and the card never appears live. Live evidence: the approval surfaced
only as `interaction` (verbatim capture in `docs/server-api.md`, Interactions)
while the attempt to POST the old route answered 404.

**2.5.0 update (nightly drift run 2026-11-04).** The transport this ticket
assumed is gone: the raw `subscribe` is ignored, every legacy event frame is
gone, and `GET /snapshot` is a 404. The `interaction` frame itself is
byte-unchanged, but it now arrives only on a `subscribe_v2` subscription and
the pending list is re-sent **twice** after the ack: inside
`transcript.reset.snapshot.interactions` (full entities) and then as
standalone `interaction` frames. `GET .../transcript` returns `interactions`
+ `pending_interactions` for a cold read. The legacy
`permission.approval.*` frames no longer exist at all, so the old
double-card guard is unnecessary (harmless if kept). This ticket is now
blocked on ticket 175 (the `subscribe_v2` transport); implement it on top of
that, and use the transcript reset/REST for the cold seed instead of the
removed snapshot.

This ticket restores approvals only; questions are ticket 172 and the plan
review rendering is ticket 173.

## Description

1. Add decoding for the flat `interaction` frame. It has **no `payload`
   object and no `seq`**: `{type, session_id, agent_id, event_created_at,
   interaction_id, status, outcome?, ended_at?, tool_call_id?, kind, request,
   response?}`. In `SessionSocket`, dispatch it before the payload-based
   `when` (the existing `WsFrame` decode yields `payload = null`), decode the
   raw text into a new DTO, and ignore question kinds.
2. For `kind:"approval"`:
   - `status:"pending"` → `SessionEvent.ApprovalRequested` with
     `interaction_id` as the card id, `request.tool_name`,
     `ChatMessageMapper.summary` over `request.tool_input_display`/
     `request.action`, and `agent_id` as the asking agent.
   - `status:"ended"` → `SessionEvent.ApprovalResolved` with the same id, for
     every `outcome` (`approved`/`rejected`/`cancelled`). Close on this frame,
     not on the POST response.
3. Replace `ChatApi.postApprovalDecision` with a call to
   `POST /api/v1/sessions/{sessionId}/interactions/{interactionId}` and body
   `{"kind":"approval","decision":"approved"|"rejected"}`. Success is
   `code:0` with `data:{outcome, ended_at}`. Keep the
   `ChatRepository.postApprovalDecision` signature; map `40404` and `40902`
   through the existing `DataError.Api` path.
4. On every `subscribe_v2` ack with `code:0`, clear the session's pending
   approvals: the server re-sends all pending interactions right after the ack
   (docs/server-api.md, Interactions), and the `transcript.reset` that precedes
   it also carries `snapshot.interactions`. Emit a reset event the view model
   applies before the re-sent frames land. Dedupe by `interaction_id` so a
   repeated pending frame cannot double-add.
5. For the cold seed, read `snapshot.interactions` from `transcript.reset` (or
   `GET .../transcript`'s `interactions` + `pending_interactions`) and map the
   approval entities to the same domain card. The removed snapshot's
   `pending_approvals`/`SnapshotApproval` path is deleted, not kept.
6. Delete the dead `event.approval.*` DTO paths; there is no
   `permission.approval.*` duplicate to guard against on 2.5.0.

## Acceptance criteria

- [ ] A live manual Bash prompt surfaces an approval card in an open chat
      without any snapshot refetch.
- [ ] Allow posts `{"kind":"approval","decision":"approved"}` to
      `/sessions/{id}/interactions/{approval_<uuid>}`; the card clears when
      the `status:"ended"` frame arrives, and the tool runs.
- [ ] Deny behaves the same with `decision:"rejected"` and the tool does not
      run.
- [ ] Aborting a turn with a pending approval clears the card via the
      `outcome:"cancelled"` frame and sends no further request.
- [ ] Reconnecting while an approval is pending rebuilds exactly one card
      (from `transcript.reset.snapshot.interactions` and/or the re-send after
      the ack), with no duplicate.
- [ ] A cold open with a pending approval seeds one card from the
      `transcript.reset` interactions (or `GET .../transcript`).
- [ ] Unit tests cover frame decoding (pending/ended, each outcome), the POST
      body and URL, and the reset; `:app:testDebugUnitTest` passes.

## Out of scope

- Questions — ticket 172.
- Rendering the `plan_review` display body — ticket 173.
- Any compatibility path for servers before 2.4.0: the project targets the
  latest server only.
- `feedback`/`scope`/`selected_label` request fields beyond `decision`.
- The `permission.approval.*` frames' other fields.

## Notes

Relevant code: `data/chat/SessionSocket.kt` (frame dispatch ~line 275),
`data/chat/ChatDtos.kt` (`ApprovalRequestedPayload`, `AckPayload`),
`data/chat/ChatApi.kt` (`postApprovalDecision`), `data/chat/ChatRepository.kt`,
`ui/chat/ChatViewModel.kt` (`postDecision`, the `ApprovalRequested`/`Resolved`
branches). Wire reference: `docs/server-api.md` → Interactions.
