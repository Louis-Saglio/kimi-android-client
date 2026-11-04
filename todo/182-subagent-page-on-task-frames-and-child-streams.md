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
