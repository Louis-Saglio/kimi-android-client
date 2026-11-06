# Drive the todo widget from the `todo` frame

- **ID:** 178
- **Created:** 2026-11-04
- **Depends on:** 175

## Context

Found by the nightly server drift run from 2.4.1 to 2.5.0 on 2026-11-04
(server 2.5.0, upstream commit `011b1d41`, "push agent, usage, goal and todo
state over WebSocket"). 2.5.0 adds a flat `todo` frame to every session
subscription: `{type:"todo", session_id, agent_id, event_created_at,
items:[{title, status:"pending"|"in_progress"|"done"}]}`. It is sent when the
agent joins and on every change, **including undo and cold restore**, and it
replaces the client's list wholesale. Verified live on 2.5.0: a three-step
prompt produced three `todo` frames (empty → in_progress → done) in step with
the `TodoList` calls.

The app does not use it. `TodoParser` (`data/chat/Todo.kt`) derives the list
by scanning `TodoList` tool calls, and `ChatTimeline` hides those rows in
favour of the summary widget (ticket 042). That derivation has two known
gaps the frame fixes: an undo that rewinds a `TodoList` call leaves the
widget stale, and a cold-open session only shows todos that happened to be
in the scanned history page. The frame is also cheaper: no backward scan and
no `TodoList` input parsing on every update.

## Description

1. Parse the `todo` frame in `SessionSocket` (payload fields beside `type`,
   no `payload` wrapper) and emit a new
   `SessionEvent.TodoUpdated(items: List<Todo>)`. Route it by `agent_id`:
   only `main` updates the chat widget; child agents' lists are ticket 182's.
2. Apply the frame wholesale in the chat state: replace the widget list,
   `[]` clears it. Keep the existing `Todo` domain model and status mapping;
   an unknown status string degrades to Pending as today.
3. Add the frame to the cold-start path by construction: the server sends the
   current list before the subscribe ack, so no `TranscriptColdStart`/transcript
   mining is needed. Remove the `TodoParser` scan from the live event path;
   keep the parser only if the transcript REST page still needs it for
   historical rows (it does not — `TodoList` tool frames remain visible
   history and the widget is live state).
4. Keep hiding `TodoList` tool rows from the timeline (ticket 042 behaviour)
   and keep the existing widget placement/design.

## Acceptance criteria

- [x] A live `TodoList` update moves the widget from the `todo` frame, with
      no scan of tool-call history (unit test feeds three frames and asserts
      the widget replaces, not appends).
- [x] Undo after a todo update restores the previous list when the server
      sends the new `todo` frame (test with two frames).
- [x] A cold open shows the current list from the frame that precedes the
      subscribe ack; no `TodoList` row is rendered.
- [x] A child agent's `todo` frame does not touch the main chat widget.
- [x] Empty `items` clears the widget.
- [x] `:app:testDebugUnitTest` passes.

## Out of scope

- The `TodoList` tool schema/result prose (unchanged).
- Goal tracking; plan/todo cards beyond the existing widget.
- Sub-agent todo display — ticket 182.
- Server-side todo persistence or a todo REST route (none exists).

## Notes

Relevant code: `data/chat/Todo.kt` (`TodoParser`), `data/chat/ChatEvents.kt`,
`data/chat/SessionSocket.kt`, `ui/chat/ChatViewModel.kt` (todo state),
`ui/chat/ChatTimeline.kt` / `ui/chat/components` (widget). Wire reference:
`docs/server-api.md` → Todos and WebSocket → Session frames.

## Implementation notes

**2026-11-06 —** `SessionSocket` decodes the flat `todo` frame (`TodoFrame`/`TodoItemDto`
in `ChatDtos.kt`, read from the frame text like `agent`/`usage`) and maps it with the pure
`TodoFrame.toTodos()` (`data/chat/Todo.kt`: blank titles skipped, an unknown status
degrades to Pending) into the new `SessionEvent.TodoUpdated(items, agentId)` — emitted for
every agent. `ChatViewModel` applies main's wholesale to `UiState.todos` (`[]` clears the
widget); the frame precedes the subscribe ack, so it is the cold-start seed with no
transcript mining, and the server's re-send after an undo/cold restore heals the widget.
A child's frame is sub-agent-scoped (`isSubagentScoped`) and never reaches the chat.

- Deleted: `TodoParser` (and its DI wiring in `ChatViewModel`, `SubagentStreamStore`,
  `ChatTimeline`) and the timeline's `committedTodos`/`liveTodos`/`todos()` derivation;
  `ChatTimeline` keeps only the ticket-042 row/result suppression, keyed by the new
  top-level `TODO_LIST_TOOL_NAME`. History refetches and cold-start applies no longer
  write `UiState.todos`.
- Sub-agent pages: deleting the parser would have left the dock without a feed, so the
  child's agent-tagged frame now replaces `SubagentRecord.todos` (a field outside the
  timeline — no transcript replace touches it). The rest of the sub-agent page rebuild
  stays ticket 182's.
- Tests: `ChatTodosTest` (VM harness: three live frames replace, never append; undo +
  re-sent frame restores; cold open shows the pre-ack frame's list over a stale history
  `TodoList` call, survives the turn-end refetch, no `TodoList` row; a child's frame
  leaves the main widget alone; `[]` clears), `SessionSocketTest` (subscribe-time seed
  order now includes the todo event, per-agent tagging, foreign session dropped, the
  mapping's status/title rules — replacing `TodoParserTest`), `ChatTimelineTest` (row
  suppression for history, live and transcript paths; the derivation tests are gone with
  the behaviour), `SubagentStreamStoreTest`/`SubagentViewModelTest` (the dock list from the
  child's frame, untouched by a transcript page). Shared builder:
  `TranscriptFrames.todoFrame`.
- Docs: `docs/conventions.md` (state frames, cold start, sub-agent dock) and the app note
  at the end of `docs/server-api.md` → Todos, which still described the removed derivation.
