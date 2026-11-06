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

- [ ] A live `TodoList` update moves the widget from the `todo` frame, with
      no scan of tool-call history (unit test feeds three frames and asserts
      the widget replaces, not appends).
- [ ] Undo after a todo update restores the previous list when the server
      sends the new `todo` frame (test with two frames).
- [ ] A cold open shows the current list from the frame that precedes the
      subscribe ack; no `TodoList` row is rendered.
- [ ] A child agent's `todo` frame does not touch the main chat widget.
- [ ] Empty `items` clears the widget.
- [ ] `:app:testDebugUnitTest` passes.

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
