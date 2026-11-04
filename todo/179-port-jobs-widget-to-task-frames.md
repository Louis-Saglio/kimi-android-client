# Port the jobs widget to `task.list` / `task` frames

- **ID:** 179
- **Created:** 2026-11-04
- **Depends on:** 175

## Context

Found by the nightly server drift run from 2.4.1 to 2.5.0 on 2026-11-04
(server 2.5.0, upstream commit `ebc2a9f9`, "sync running tasks over
WebSocket and drop the REST task API"). The whole
`/api/v1/sessions/{id}/tasks…` family (`list`, `get`, `:cancel`, `:detach`)
is a plain HTTP 404 now. So are the old `task.started`/`task.terminated` and
`background.task.*` events. The app's jobs surface is built entirely on them:
`JobsStore.seed()` calls `ChatRepository.getTasks`, `cancel()` posts
`/tasks/{id}:cancel`, `detach()` posts `:detach`, and the job screen
(`JobDetailViewModel`) calls `getTask(with_output=true)`.

2.5.0 replaces this with `task.list` (all running tasks when an agent joins)
and `task` (full state on every change):
`{task_id, kind:"shell"|"subagent"|"other", status:"running"|"ended",
outcome:"completed"|"failed"|"timed_out"|"killed"|"lost", phase?,
detached, description?, child_agent_id?, parent_tool_call_id?,
swarm_index?, command?, started_at?, ended_at?, result_summary?, error?,
state_reason?, model?, thinking?}`. Cancelling is the flat `task.stop`
control frame; detaching is `task.background`; both reply
`{type:"response", request_id, code, msg?, task?}`. Verbatim captures are in
`docs/server-api.md` → Sub-agents → Task frames and controls.

The REST vocabulary the app maps (`kind:"subagent"|"bash"`, status
`running`/`completed`/`failed`/`cancelled`, `run_in_background`) is gone;
the frame's own vocabulary is the domain now.

## Description

1. Parse `task.list` and `task` in `SessionSocket` and emit
   `SessionEvent.TaskListed(tasks)` / `SessionEvent.TaskUpdated(task)`.
   Route by `agent_id`: `main` feeds the jobs widget; a child's tasks feed
   ticket 182's page (the app currently only shows main-owned jobs).
2. Replace `JobsStore.seed()` (REST) with the `task.list` arriving at
   subscribe; replace the live `TaskStarted`/`TaskTerminated` handlers with
   `TaskUpdated`. Map the full task state to the existing `Job` record:
   `kind:"shell"` → `Bash`, `kind:"subagent"` → `Subagent`, and
   `kind:"other"` (non-shell/subagent work, e.g. background questions) is
   filtered from the widget; status `ended` + outcome maps to
   Completed/Failed/Cancelled, keeping `timed_out`/`lost` distinct if the
   existing `Job.timedOut` display still needs it.
3. Keep ended jobs client-side after their final frame (the server never
   lists them again); `task.list` is authoritative only for running tasks —
   merge it into the existing list instead of replacing terminal rows.
4. Replace `cancel`/`detach`:
   - `task.stop` flat frame `{type:"task.stop",request_id,session_id,
     agent_id:"main",task_id}`; on `code:0` apply the replied `task` state.
     `40933` means it cannot be detached/stopped; surface it as the existing
     snackbar.
   - `task.background` for the "To background" action; apply the replied
     `task` (`detached:true`) and keep the row.
   - Delete `ChatApi.listTasks`, `getTask`, `cancelTask`, `detachTask` and
     their DTOs. The job screen's live output is ticket 180; until then it
     can build its initial state from the task frame only.
5. Delete the `TaskInfo`/`BackgroundTask` REST mapping paths and the
   `task.started`/`task.terminated` DTOs; keep the `Job` UI model.

## Acceptance criteria

- [ ] A background bash or sub-agent spawn shows in the jobs widget from
      `task` frames with no `/tasks` request (unit test asserts the URL is
      absent).
- [ ] `task.list` seeds running jobs on open; terminal jobs from earlier
      frames linger in the sheet.
- [ ] Cancel sends `task.stop` and flips the job when the ended frame
      arrives; a `40933`/error reply surfaces without changing the job.
- [ ] "To background" sends `task.background` and the row stays; the replied
      state's `detached:true` is reflected.
- [ ] A `kind:"other"` (background question) task is not rendered as a job.
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- Streaming a job's stdout — ticket 180 (`task.watch`/`task.output`).
- The sub-agent page's task rendering — ticket 182.
- Job history/persistence beyond the current session.
- The removed REST task routes; no compatibility layer.

## Notes

Relevant code: `ui/chat/JobsStore.kt`, `data/chat/Tasks.kt`,
`data/chat/ChatApi.kt` (`listTasks`, `getTask`, `cancelTask`, `detachTask`),
`data/chat/ChatRepository.kt`, `ui/chat/JobDetailViewModel.kt`,
`ui/chat/ChatEvents.kt`. Wire reference: `docs/server-api.md` →
Sub-agents → Task frames and controls.
