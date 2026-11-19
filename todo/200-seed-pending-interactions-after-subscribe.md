# Seed pending interactions after subscribe

- **ID:** 200
- **Created:** 2026-11-17
- **Depends on:** 197

## Context

Server 2.6.3 (nightly drift run, 2026-11-17; commit `725194d7`) removed the
subscribe ack and, with it, the `transcript.reset.snapshot.interactions` list
the app used to rebuild its approval and question queues. The server now sends
every still-pending interaction of the session as standalone `interaction`
frames immediately after the subscribe `response`, before the agent state
frames. There is no end-of-batch marker.

Server 2.7.0 (drift run 2026-11-19; commit `84439d5f`) adds
`GET /api/v1/sessions/{id}/interactions` — `{items:[<pending interaction>]}`
with the same item shape as the frames (verified live). It is a snapshot,
not a stream: use it for the cold seed (and a refresh after a reconnect gap)
and keep the frames as the live truth.

The app's `ApprovalsReset`/`QuestionsReset` events were emitted on the ack and
replaced the queues. Without a replacement, a decision missed while
disconnected leaves a stale card forever, and the cold-start seed from
`TranscriptData.pendingApprovals` (ticket 199) disappears.

## Description

- On a `response` with `code:0` for our subscribe `request_id`, clear the
  session's approval and question queues (they are authoritative again) and
  mark the connection as awaiting the pending batch.
- Apply every `interaction` frame that follows as today (pending adds, ended
  removes). Because the server sends all pending frames before any live frame,
  clearing on the response cannot drop a live interaction.
- Remove `ApprovalsReset`/`QuestionsReset` and the `resetApprovals`/
  `resetQuestions` collectors fed by `transcript.reset`.
- On a cold open, fetch `GET .../interactions` before the socket connects and
  seed both queues from it; the subscribe re-send then reconciles (dedupe by
  `interaction_id`), so a card that ended while disconnected disappears.
- Keep the existing cold-start path: the running turn's `tool_call` items with
  `stage:"awaiting_approval"` and `interaction_id` are a cross-check, not a
  replacement for the frame/list content.
- Dedupe by `interaction_id`: a `pending` frame after an `ended` frame for the
  same id must not resurrect the card.

## Acceptance criteria

- [ ] A fake connection that answers the subscribe with `code:0` then sends
      one pending approval and one pending question shows both cards.
- [ ] Reconnecting after an approval was answered elsewhere clears the old
      card and shows no approval card when the server sends none.
- [ ] An `ended` frame for an id followed by a late `pending` frame for the
      same id leaves the card closed.
- [ ] No source references `transcript.reset`, `ApprovalsReset` or
      `QuestionsReset`.
- [ ] A cold open with one pending approval answers one
      `GET .../interactions` and shows the card before any socket frame.
- [ ] Live against 2.7.0: subscribe while a manual Bash approval waits, answer
      it from another client, reconnect, and the card is gone; the REST list
      is empty at that point.

## Out of scope

- Interaction frame field changes (none on 2.7.0).
- Replacing the post-subscribe frames with polling.
- The response route and its error codes (unchanged).
- Plan-review rendering (ticket 173, done).

## Notes

- `docs/server-api.md` → Interactions → Notes and WebSocket → Subscribing to a
  session.
