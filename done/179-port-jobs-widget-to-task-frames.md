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

## Implementation notes

**2026-11-06 —** `SessionSocket` decodes the flat `task.list`/`task` frames for every
agent (`TaskListFrame`/`TaskFrame`/`TaskStateDto` in `ChatDtos.kt`, mapped by the pure
`TaskStateDto.toTaskState()` in `data/chat/Tasks.kt` into the new domain `TaskState` +
`TaskOutcome` — the frame's own vocabulary; a blank id or unknown status drops the
entry, an unknown kind is `Other`) and emits `SessionEvent.TaskListed(tasks, agentId)` /
`TaskUpdated(task, agentId)`, tagged with the owning agent. It also keeps the latest
state per (agent, task) at instance level (`observeTask`) and sends the flat controls
(`sendTaskControl(sessionId, TaskControl.Stop|Background, agentId, taskId)` on the
session's open connection, reply matched by `request_id`; a code-0 reply's `task` is
emitted as a `TaskUpdated` before the call returns, a non-zero code fails with
`DataError.Api`, no/dropped connection or a 15 s silence with `DataError.Network`).
`ChatRepository.sendTaskControl`/`observeTask` expose both.

- `JobsStore`: `seed()` (REST) and `onStarted`/`onTerminated` are replaced by
  `onListed` (merge — listed running jobs upsert, a running job no longer listed ended
  unseen and is dropped, terminal rows linger) and `onUpdated` (full-state upsert). The
  shared `TaskState.toBackgroundJob()` keeps only `detached:true` shell → Bash and
  subagent → Subagent tasks (`child_agent_id` = page link); `other` (background
  questions) and foreground tasks are no jobs (ticket 162's background-only rule).
  `toJobStatus()`: killed → Cancelled, completed → Completed, failed/timed_out/lost →
  Failed, `timed_out` also sets `Job.timedOut`. `Job` keeps its UI shape with its own
  `Job.Kind`/`Job.Status`. Stop is `task.stop` with no optimistic flip; an error reply
  or no socket raises `JobCancelFailed`. The `SeedGuard` use and the ViewModel's seed
  calls are gone.
- `SubagentStreamStore`: `onTaskListed`/`onTaskUpdated` replace the `task.*` handler and
  `applyTaskList`; a subagent-kind task merges into the record its `child_agent_id`
  names (`mergeSpawnTask`: task id first-writer, `detached` → `runInBackground`,
  model/effort blank-fill, `killed` → Cancelled pin) — no record creation (ticket 182);
  a child's own tasks feed `SubagentRecord.jobs` (list merge + upsert).
- Controls moved off REST: `SwarmViewModel.onStopMember` and `JobDetailViewModel.onStop`
  send `task.stop`, `SubagentViewModel.onDetachToBackground` sends `task.background`
  (40933 snackbars). `JobDetailViewModel` reads `observeTask` in main mode (status,
  times, description/command, `result_summary`/`error` as output until ticket 180);
  its poll, entry fetch, error state and retry are deleted.
- Deleted: `ChatApi.listTasks/getTask/cancelTask/detachTask`, the `ChatRepository`
  wrappers, `TaskListData`/`TaskDto`/`TaskCancelData`/`TaskDetachData`,
  `ChatMessageMapper.toDomain(TaskDto)`, `BackgroundTask`, `TaskInfo`,
  `SessionEvent.TaskStarted/TaskTerminated`, `ChatApiTest` (REST tasks only). The
  transcript task origin now carries a `TaskOutcome?`.
- Tests: `SessionSocketTest` (task frames tagged by owner, the mapping, the task record,
  flat control frame + code-0 reply emitted before return, error reply applies nothing,
  no/dropped connection, unknown request id; the subscribe-seed order includes
  `task.list`), `ChatJobsTest` (new, VM harness: frames show jobs with no `/tasks`
  request, list seed + lingering terminal row, cancel → `task.stop` → flips on the
  killed reply, 40933 surfaces without change, `task.background` reply lists the row
  once, 40933 on background, `other` kind never shown), `JobsStoreTest` (rewritten:
  mapping, merge, no-socket stop), `JobDetailViewModelTest` (rewritten on a real
  socket), `SubagentStreamStoreTest`, `SubagentViewModelTest`, `SwarmViewModelTest`
  (ported to frames/controls). Frame builders: `TranscriptFrames.taskState/taskFrame/
  taskListFrame/taskResponseFrame`. androidTest fixtures moved to `Job.Kind/Status`.
- Docs: `docs/conventions.md` (socket task frames/controls, data layer, JobsStore, job
  screen, sub-agent store carriers, swarm stop, detach). Ticket 180's item 4 is marked
  done here.
