# Stream the chat on turn and item frames

- **ID:** 188
- **Created:** 2026-11-12
- **Depends on:** 187

## Context

Server 2.6.0 (nightly drift run, 2026-11-12; commit `1dd55786`) deleted the
transcript protocol. There is no `transcript.reset`, no `transcript.ops`, no
op union and no `seq`. A subscribed agent now sends a `turns` page and
incremental frames: `turn.started`, `turn.ended`, `turn.updated`,
`item.started`, `item.ended`, `item.updated`, `item.delta`, `item.removed`,
`turns.removed`, `turns.failed` and `notice`.

The app's `TranscriptFold` folds an op stream that no longer exists, so the
chat renders nothing live even after ticket 187 fixes the subscription.

## Description

Replace the op fold with a conversation fold that keeps per-agent state as
turns and items, and maps it to the existing `ChatItem`/`ChatTimeline` rows:

- `turn.started`/`turn.ended` replace the turn header and keep its items;
  `turn.ended` may repeat for a corrected turn.
- `turn.updated` writes `step` into `steps` by `index`; `retry` replaces the
  turn's retry state and an absent `retry` clears it. Drive the existing retry
  status line from `turn.retry` (`{attempt, next_attempt, max_attempts,
  delay_ms, error}`).
- `item.started`/`item.ended` replace the item by `item_id` or append it;
  `item.updated.set` merges shallowly; `item.delta` appends to `text`,
  `input_text` or `output`; `item.removed` removes it.
- `turns.removed` removes turns by `turn_id` (undo/clear).
- `turns.failed` surfaces a load failure without dropping the subscription.
- `notice` surfaces `{level, title, detail?, code?}` as a chat banner; keep it
  out of the timeline (compaction's use is ticket 191).
- Map item types to rows: `user_message` (drop `origin.kind:"injection"`
  reminders and `<system-reminder>` envelopes as today), `assistant_message`,
  `reasoning`, `tool_call` (stage/input/output/display/media/error) and
  `notification`.
- Keep the per-agent routing: main's frames feed the chat, any other
  `agent_id` goes to the sub-agent store (ticket 192).

Reuse the existing row enrichment (`ToolPreview`, `ToolDetailFields`,
`ToolMedia`, `EditDiffs`, `Todo` display) rather than re-deriving it.

## Acceptance criteria

- [ ] A fake frame sequence (turn.started → item.ended user_message →
      item.started/delta/updated/ended tool_call → item.started/delta/ended
      assistant_message → turn.updated → turn.ended) produces one turn with
      the same visible rows the current transcript fold produces for the same
      content.
- [ ] `item.delta` with `field:"text"` appends to the streaming reply and the
      final `item.ended` replaces it exactly once (no duplicate text).
- [ ] `item.updated.set` merges a tool call's `stage`, `input`, `display`,
      `interaction_id`, `task_id` and `agent_refs` without losing earlier
      fields.
- [ ] `turns.removed` deletes the listed turns; `item.removed` deletes one
      item.
- [ ] `turn.retry` shows the retry status, and the next step or turn end
      clears it.
- [ ] `notice` renders as a dismissible banner and never as a timeline row.
- [ ] A finished tool call with `media[]` renders its images through the
      ticket-184 path instead of pretty-printed JSON.
- [ ] `TranscriptFold`, `TranscriptOpsDtos` and the transcript DTOs are gone
      or unreferenced.

## Out of scope

- The cold-start REST read (ticket 189).
- Pending-interaction seeding (ticket 190).
- Compaction-specific UI (ticket 191).
- Sub-agent page and child subscriptions (ticket 192).
- A live tool-output tail (ticket 196).

## Notes

- `docs/server-api.md` → Conversation and WebSocket → Session frames.
- Item ids are stable; `item_id` is the tool-call id for `tool_call`.
