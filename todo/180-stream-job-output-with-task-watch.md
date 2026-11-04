# Stream job output with `task.watch` instead of polling

- **ID:** 180
- **Created:** 2026-11-04
- **Depends on:** 179

## Context

Found by the nightly server drift run from 2.4.1 to 2.5.0 on 2026-11-04
(server 2.5.0, upstream commit `ebc2a9f9`). The job screen's live output was
a ~2 s poll of `GET .../tasks/{id}?with_output=true` (`JobDetailViewModel`,
ticket 096); that route is gone. 2.5.0 adds a real streaming channel:

- `task.watch` — a flat control frame
  `{type:"task.watch",request_id,session_id,agent_id,task_id,
  since_offset?,tail_bytes?}`. On success it replies
  `{type:"response",request_id,code:0,task:{…}}`, then sends the last
  `tail_bytes` (32 KiB default, max 1 MiB) as one `task.output` frame
  (`{type:"task.output",session_id,agent_id,task_id,offset,text}`, `offset`
  a UTF-8 byte offset), then live chunks.
- `task.unwatch` — stop the stream.
- The watch is dropped on disconnect; resend with `since_offset` = the end
  of what was already rendered.

Verified live on 2.5.0: a running `sleep 30 && echo watched-ok` task replied
with its state, an empty first `task.output` (`offset:0`), and the watch
survived `task.background`; `task.unwatch` replied `code:0`.

This removes the only polling loop in the chat feature and gives the phone
live output instead of 2-second snapshots. Ticket 179 owns the job list and
the task lifecycle; this ticket owns the job detail's output.

## Description

1. Add `task.watch`/`task.unwatch`/`task.output` handling to
   `SessionSocket` (or a dedicated task-output stream owned by the job
   screen; the socket already owns the WS connection). Requests are flat —
   do not wrap them in `payload`; match replies by `request_id`.
2. In `JobDetailViewModel`:
   - for a running task, send `task.watch` with `tail_bytes` (e.g. 32768)
     and append each `task.output` chunk at its `offset`; render the
     accumulated stdout tail;
   - stop with `task.unwatch` when the screen leaves or the task ends;
   - on reconnect/re-entry, resend with `since_offset` = the number of UTF-8
     bytes already accumulated, so no output is lost or duplicated;
   - a `code` error (`40406` unknown task, `50003` unreadable) falls back to
     the task frame's `result_summary` and shows the error state.
3. Compute offsets in UTF-8 bytes, not Kotlin `String.length` (characters);
   keep a bounded tail so a long-running command cannot grow memory without
   limit (the old `output_preview` cap was 32 KiB; 1 MiB is the server's
   maximum first chunk).
4. Delete the `getTask` poll, its `POLL_INTERVAL_MILLIS` and the
   `with_output` DTO fields; the task frame (ticket 179) is the status
   source and `task.output` the output source.
5. Store-backed mode (a sub-agent task visited from the sub-agent page) has
   no watch target unless it has a `task_id`; keep its current behaviour.

## Acceptance criteria

- [ ] Opening a running job streams output live with no `/tasks` request and
      no 2 s poll (unit test asserts no delay loop and that `task.watch` is
      sent once).
- [ ] Chunks append at their byte offsets; a duplicate chunk is ignored and
      a gap is visible as a missing range rather than silently concatenated.
- [ ] Leaving the screen sends `task.unwatch`; re-entering resends with the
      accumulated `since_offset`.
- [ ] An ended task still shows its final state and last output.
- [ ] `40406`/`50003` replies surface an error without crashing.
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- The job list and task lifecycle — ticket 179.
- Streaming a sub-agent's own transcript — ticket 182.
- Binary/ANSI rendering beyond the existing plain-text tail.
- Task output history after the task ended (the server serves it through
  `task.watch`; no new retention is added).

## Notes

Relevant code: `ui/chat/JobDetailViewModel.kt` (poll loop, `outputPreview`),
`data/chat/ChatApi.kt` (`getTask`), `data/chat/SessionSocket.kt`,
`data/chat/ChatEvents.kt`. Wire reference: `docs/server-api.md` →
Sub-agents → Task frames and controls; WebSocket → Session frames.
