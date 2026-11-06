# Rebuild the sub-agent page on task frames and child transcript streams

- **ID:** 182
- **Created:** 2026-11-04
- **Depends on:** 175, 179

## Context

Found by the nightly server drift run from 2.4.1 to 2.5.0 on 2026-11-04
(server 2.5.0, upstream commits `8535bd79` and `ebc2a9f9`). The
`subagent.spawned`/`started`/`suspended`/`completed`/`failed`/`cancelled`
events and the snapshot `subagents` roster are gone. The app's sub-agent page
(`SubagentStreamStore`, `SubagentViewModel`, `SubagentScreen`) is built on
them plus the `Agent` tool row's `agent_call` display: it learns a child's
id/type/parent tool call from `subagent.spawned`, status from the lifecycle
events, and history from `GET .../transcript?agent_id=<id>`.

2.5.0 replaces this with two facts, verified live:

- A `task` frame with `kind:"subagent"` is the spawn record:
  `{task_id:"agent-…", status:"running"|"ended", phase?, detached,
  description, child_agent_id:"agent-0", parent_tool_call_id:"call_…",
  swarm_index?, model?, thinking?, result_summary?, error?, started_at?,
  ended_at?}`. `detached:true` is `run_in_background:true`; the ended frame
  carries the outcome.
- A child agent is **automatically subscribed** on the same connection: it
  announces itself with `transcript.reset` (`agent_id:"agent-0"`, `seq:0`),
  `agent` (and its other state frames), then its `transcript.ops` stream.
  `GET .../transcript?agent_id=main`'s `agents` array lists every child
  (`{agentId,type:"sub",parentAgentId:"main"}`), and
  `GET .../transcript?agent_id=agent-0` still serves its history.

There is no `subagent.*` representation of a deliberate stop either: a
cancelled run is a `task` ended frame with `outcome:"killed"`.

## Description

1. Discover children from `task` frames (`kind:"subagent"`) and the
   `agents` roster on the main transcript (cold open): create a store record
   keyed by `child_agent_id`, carrying `task_id`, `parent_tool_call_id`,
   `description`, `detached`, `model`, `thinking`, `swarm_index` and the
   current status.
2. Route every `transcript.reset`/`transcript.ops`/`agent` frame whose
   `agent_id` is not `main` into that record's stream (ticket 175 already
   routes by agent id; this ticket renders it). Reuse the existing
   transcript REST mapper for turns/steps/frames; live ops and the REST page
   must not duplicate rows.
3. Derive status from the task frame, not from lifecycle events:
   `running` + `phase:"working"|"suspended"` → Working/Suspended;
   `ended` + `outcome` → Completed/Failed/Cancelled (`killed`), with
   `result_summary`/`error` as the summary/error text. Remove the
   `subagent.*` DTOs and handlers.
4. Keep the `Agent`/`AgentSwarm` tool row's `display.agent_call` link
   (`parent_tool_call_id`) and the ticket-157 navigation; a background spawn
   (`detached:true`) still opens the job screen from its `task_id:` result
   header (ticket 179 owns the job).
5. On a cold open, seed the page from `GET .../transcript?agent_id=<id>`
   and the `agents` roster; the live reset/ops take over from the watermark.
   Do not depend on the removed snapshot roster.

## Acceptance criteria

- [ ] A live foreground `Agent` spawn opens the sub-agent page and streams
      its text/tool frames from the child transcript ops.
- [ ] A `run_in_background:true` spawn shows `detached:true` and its page
      updates while the main turn is idle.
- [ ] Completion shows the ended task's `result_summary`; a `killed`
      outcome shows Cancelled, not Failed.
- [ ] A cold open of a session with a finished child lists it from the
      `agents` roster and renders its history from the transcript REST.
- [ ] No `subagent.*` frame handling remains; the main chat never renders a
      child's frames.
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- The jobs widget and task controls — ticket 179.
- The live op fold itself (ticket 175) and the main-chat cold start (176).
- Swarm aggregate ordering beyond the existing `swarm_index` handling.
- Child-agent approval/question routing beyond the existing session-level
  interaction cards (tickets 171/172).
- `btw` side agents.

## Notes

Relevant code: `ui/chat/SubagentStreamStore.kt`,
`ui/chat/SubagentViewModel.kt`, `ui/chat/SubagentScreen.kt`,
`data/chat/Snapshot.kt` (`SubagentRosterEntry`), `data/chat/ChatEvents.kt`
(`Subagent*`), `data/chat/SessionSocket.kt`, `data/chat/ChatApi.kt`
(`transcript`). Wire reference: `docs/server-api.md` → Sub-agents and
WebSocket → Transcript protocol.

## Implementation notes

**2026-11-06 —** The sub-agent record is now built from the spawn `task` frame. In
`SubagentStreamStore`, `applySpawnTask` (it replaces 179's `mergeSpawnTask` and the
`spawn`/`onLifecycle` handlers) takes every subagent-kind `task.list`/`task` entry,
whoever owns it, and creates or updates the record keyed by `child_agent_id`. It writes:
- the task id (first writer) and the `parent_tool_call_id` row link, registered in the
  link registry (so ticket 157's navigation is unchanged);
- the owning agent, the description and `swarm_index` (first writer);
- `detached` → `runInBackground`, and the model/effort pair;
- the status, pinned Live: running + `phase:"suspended"` → Suspended (`state_reason` is
  the suspend cause), any other running phase → Working; ended → Completed /
  Cancelled (`killed`) / Failed (`failed`/`timed_out`/`lost`), with
  `result_summary`/`error`. The summary stands in as the final bubble while the timeline
  is empty.

A `task.list` that no longer names a live-pinned running spawn of the same owner releases
the pin, so the `Agent` result / transcript heal lands after a missed ended frame.
`ChatViewModel` forwards main-owned task frames to the store, then re-mines its `Agent`
rows. The new link names the record, and the call args fill the subagent type and the
task prompt.

- Child streams: a child's own frames (ops, reset signal, `todo`, `task`, `agent`)
  create its record when they arrive before the spawn frame; nothing is dropped any more.
  `SessionSocket` now emits a child's `agent` frame tagged with its id. Its model/effort
  update the record; busy/permission render nothing. `usage` stays main-only.
- Live hand-over: when `applyTranscriptPage` replaces the live rows, it seeds the child's
  socket fold and watermark from the page (`ChatRepository.seedTranscript`) and renders a
  running newest turn as live rows (`ChatTimeline.seedRunningTurn`). Replacing happens on
  a reconcile, a cold open with no live rows, a stale flag, or a still-running newest
  turn. The ops after the page extend those rows instead of duplicating the in-flight
  bubble. A child `TranscriptStale` flags the live rows stale and bumps
  `SubagentState.resyncGeneration`; `SubagentViewModel.maybeResync` then refetches the
  open page. New `ChatTimeline.hasLiveRows`.
- Cold open: `applyRoster(sessionId, List<TranscriptAgent>)` creates identity-only
  records from the main transcript's `agents`. The page's transcript fetch and the
  `Agent` result (its `agent_id:` marker) fill history, status and link.
- Deleted: the `subagent.*` events (`SessionEvent.Subagent*`, `SubagentLifecycleEvent`),
  `SubagentRosterEntry`/`SubagentRosterPhase` (`data/chat/SubagentRoster.kt`), the store's
  Roster status tier and the stale `/snapshot` route of `SubagentViewModelTest`.
  `isSubagentScoped` is now "agent-scoped and not main".
- UI: `SubagentViewModel.UiState.runInBackground`; the page's top-bar subtitle shows
  "Background".
- Unrelated pre-existing bug fixed (it made `SubagentStreamStoreKeepAliveTest`'s
  hand-over test fail deterministically on its own, on HEAD too). `ChatViewModel`'s
  socket-gate `init` ran the cold start inline when a sub-agent page was already open,
  before `turnSeedGuard`/`coldStartJob`/`coldStartDone` were initialised: an NPE, or a
  reset by their initialisers. The gate now starts from the class body's last `init`
  block.
- Tests: `SubagentStreamStoreTest` (rebuilt on spawn task frames: creation, every
  outcome, summary bubble, child-frame creation, the child agent frame, the roster cold
  open of a finished child, pin release, the hand-over without duplicates, stale resync),
  `SubagentViewModelTest` (task-frame spawns, background badge, stale refetch),
  `ChatViewModelTest` (a live foreground spawn end to end over the socket: row link,
  child stream, completed summary, killed → Cancelled; a background spawn streaming
  after the main turn ends; the approval badge from the call's type), `SessionSocketTest`
  (child `agent` frame emitted and tagged), and `SwarmViewModelTest`,
  `SubagentStreamStoreKeepAliveTest`, `JobDetailViewModelTest` ported. There is a shared
  `TranscriptFrames.spawnTaskState`/`spawnTaskFrame` builder.
- Left open: the sub-agent page's own task controls (Stop on a child-owned job). Ticket
  179 deferred them here, but this ticket puts task controls out of scope; the stale
  "ticket 182's" cross-references now say "not wired yet".
