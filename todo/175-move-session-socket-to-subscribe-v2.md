# Move the session socket to `subscribe_v2` and transcript ops

- **ID:** 175
- **Created:** 2026-11-04
- **Depends on:** none

## Context

Found by the nightly server drift run from 2.4.1 to 2.5.0 on 2026-11-04
(server 2.5.0, upstream commit `8535bd79`, "merge WebSocket session
subscriptions into subscribe_v2"). The server deleted the raw
`subscribe`/`unsubscribe` path, the per-session seq/epoch journal, cursor
replay, `resync_required`, agent filters and legacy-event suppression.
`subscribe_v2` is now the only session subscription, and the entire per-turn
event family the Android chat is built on is gone: `turn.started`/`ended`,
`assistant.delta`, `thinking.delta`, `tool.call.*`, `tool.progress`,
`tool.result`, `context.spliced`, `prompt.*`, `agent.status.updated`,
`subagent.*`, `task.started`/`terminated`, `compaction.*` and the interaction
legacy families. Zero such frames occurred in the run's captures.

Live evidence: `SessionSocket` sends
`{"type":"subscribe","payload":{"session_ids":[…]}}`; on 2.5.0 that frame is
silently ignored (no ack, no error). A socket that instead sends
`{"type":"subscribe_v2","payload":{"session_id":"…","transcript":{"*":"delta"}}}`
receives `task.list`, `agent`, `usage`, `goal`, `todo`, `transcript.reset`
and the ack, then `transcript.ops` for the turn. Verbatim captures are in
`docs/server-api.md` → WebSocket → Session frames / Transcript protocol.

This ticket is the transport plus the live fold: after it, an open chat
streams a prompt's text, thinking and tool calls again. The cold start
(missing history/state) is ticket 176; the flat state frames are 177 (agent/
usage), 178 (todo), 179 (tasks), 180 (task output), 181 (compaction) and 182
(sub-agents). Approvals and questions stay on tickets 171/172.

## Description

1. Replace `SessionSocket.subscribeFrame` with one `subscribe_v2` frame per
   observed session:
   `{"type":"subscribe_v2","id":"c_N","payload":{"session_id":"…",
   "transcript":{"*":"delta"}}}`. Keep the `client_hello` optional (not
   needed; the server accepts a bare subscribe). Delete the `cursors` map,
   `Cursor`, `seedCursor`, `Reconnected`'s seq/epoch payload and the
   `resync_required`/`ResyncRequired` path. Handle the ack: `accepted` and
   `not_found`; a `not_found` session must surface as a load failure, not
   silence.
2. Decode `transcript.reset` and `transcript.ops`. `reset` carries
   `agent_id` and a `snapshot` (`items: []`, `interactions`, `attachments`,
   `prompts`, `hasMoreOlder`) plus `seq`; `ops` carries `agent_id`, `seq`
   and the op array. Add DTOs for the op union (`prompt.upsert`,
   `turn.upsert`, `step.upsert`, `frame.upsert`, `append`, `marker.upsert`,
   `interaction.upsert`, `attachment.upsert`, `items.remove`).
3. Fold the ops into the same domain events the chat already consumes:
   - `frame.upsert`/`append` of `text` → assistant text deltas (final
     `frame.upsert` replaces the accumulated text);
   - `thinking` → thinking deltas;
   - `tool` → `ToolCallStarted`/`ToolCallResult` (state `running` → started,
     `done`/`error` → result; `inputText` feeds the preview, `input`/
     `display`/`output` feed the expanded row exactly as today);
   - `turn.upsert` state `running`/`completed`/`cancelled`/`failed` →
     turn-started/ended semantics;
   - `step.upsert` state `running`/`completed`/`interrupted` → step/retry
     status;
   - `marker.upsert` `marker:"undo"` → the `ContextSpliced` equivalent (the
     messages array is a splice; user messages are the sent bubbles,
     injections are stripped by the existing `InternalEnvelopes`);
   - `prompt.upsert` → the prompt lifecycle (status `running`/`queued`/
     `completed`/`aborted`/`blocked`/`failed`, `steeredAt`); delete
     `PromptSubmitted`/`PromptQueued`/`PromptStarted`.
   - a `turn.upsert` `state:"failed"` carries an `error` message and its
     step is `interrupted` with `endReason:"error"`/`endMessage`; surface
     that as the turn's error row (the old standalone `error` frame is gone).
4. Route by frame `agent_id`: `main` feeds the main chat; any other id feeds
   the sub-agent stream store (ticket 182 owns the page). The server
   auto-subscribes children; do not send a second `subscribe_v2` for them.
5. Delete the dead frame branches and DTOs (`assistant.delta`, `turn.*`,
   `tool.call.*`, `tool.progress`, `tool.result`, `context.spliced`,
   `prompt.*`, `agent.status.updated`, `subagent.*`, `task.*`,
   `compaction.*`, `permission.approval.*`, `event.approval.*`,
   `event.question.*`). Keep `ping`/`pong`, `server_hello` and the stall
   watchdog. Keep the foreign-`session_id` filter.
6. On reconnect, resubscribe (the subscription does not survive a drop) and
   let ticket 176 supply `transcript_since`; without it the server sends a
   fresh `transcript.reset`, which must not wipe already-rendered history.

## Acceptance criteria

- [ ] An open chat given a live prompt renders streaming assistant text,
      thinking and a tool call without any REST refetch.
- [ ] The socket sends `subscribe_v2` (one frame per session) and never the
      old `subscribe`; the old frame is deleted from the codebase.
- [ ] `transcript.reset` on connect is accepted and does not duplicate or
      clear history already rendered from REST.
- [ ] A sub-agent's `transcript.ops` (its own `agent_id`) never renders in the
      main chat.
- [ ] An abort mid-turn ends the in-flight rows (step interrupted, turn
      cancelled, prompt aborted) from the ops alone.
- [ ] `:app:testDebugUnitTest` passes, with unit tests for the reset/ops
      decoder (each op type), frame folding, and the deletion of the legacy
      branches.

## Out of scope

- Cold start / history seeding — ticket 176.
- `agent`/`usage`/`todo`/`task`/`task.output` frames — tickets 177–181.
- Sub-agent page discovery/roster — ticket 182.
- Approvals and questions — tickets 171/172.
- Reconnect gap recovery (`transcript_since`, `complete:false`) — ticket 176.

## Notes

Relevant code: `data/chat/SessionSocket.kt` (`observe`, `runConnection`,
`handleFrame`, `subscribeFrame`), `data/chat/ChatEvents.kt`,
`data/chat/ChatDtos.kt`, `data/chat/ChatMessageMapper.kt` (the transcript
REST mapper already models turn/step/frame shapes — reuse it for frame
upserts), `ui/chat/ChatViewModel.kt` (event handling). Wire reference:
`docs/server-api.md` → WebSocket → Transcript protocol; the upstream
contract is `packages/transcript/src/contract/schema.ts` at tag 2.5.0.
