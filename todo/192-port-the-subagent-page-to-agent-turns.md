# Port the sub-agent page to per-agent turns

- **ID:** 192
- **Created:** 2026-11-12
- **Depends on:** 188, 189

## Context

Server 2.6.0 (nightly drift run, 2026-11-12) removed the per-agent transcript
(`GET .../transcript?agent_id=agent-0` is 404) and the transcript roster
(`agents[]`). A sub-agent's conversation is now:
`GET .../agents/{agent_id}/turns`, streamed live by a `subscribe` whose
`agent_ids` names the child (`["main","agent-0"]`). The spawning tool call
carries structured links — `agent_refs:[{agent_id, role, swarm_index?,
task_id?}]` and `task_id` — so the page no longer needs to parse the `Agent`
tool display or its result text to find the child.

The app's `SubagentScreen`, `SubagentViewModel` and `SubagentStreamStore` are
built on the transcript endpoint, the roster and `transcript.reset`; they
render nothing on 2.6.0.

## Description

- Load a sub-agent's history with `GET .../agents/{agent_id}/turns`
  (`before_ordinal` pagination), mapped through the ticket-188 model.
- Stream the child: on the chat socket, discover child ids from `task` frames
  (`child_agent_id`) and `item.agent_refs`, then send a `subscribe` with the
  union of known agents (or a second connection for the child). Route frames by
  `agent_id` into the sub-agent store.
- Replace `applyRoster` with `agent_refs`; the Agent row links to the child by
  `agent_refs[].agent_id` and shows the task by `task_id`, falling back to
  `parent_tool_call_id`.
- Remove `TranscriptAgentDto`, the roster branch of `TranscriptData` and the
  `transcript.reset` child announcement.
- Keep the existing read-only page UX (transcript rows, task dock, spawn
  chips).

## Acceptance criteria

- [ ] Opening a finished `agent-0` page from its Agent row loads its turns and
      shows the same rows the transcript page showed before.
- [ ] A live spawn adds the child's `agent`/`task.list`/`turns` frames to the
      page after the chat's subscribe adds `agent-0`; a child that joins later
      announces itself with `task` frames as the doc describes.
- [ ] The Agent row resolves its child from `agent_refs`, not from the
      `agent_call` display or the tool result text; a call without
      `agent_refs` still renders.
- [ ] No request goes to `/transcript`.
- [ ] No source references `TranscriptAgentDto`, `applyRoster` or
      `transcript.reset`.

## Out of scope

- Swarm UI layout changes (the existing `SwarmScreen` keeps its data source).
- The main chat's cold start (ticket 189).
- The jobs widget's task frames (unchanged since 2.5.0).

## Notes

- `docs/server-api.md` → Sub-agents and Conversation.
