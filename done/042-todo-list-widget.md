# Todo list widget above the chat composer

- **ID:** 042
- **Created:** 2026-07-27
- **Depends on:** none (builds on the tool-call timeline from tickets 022/037)

## Context

The agent's `TodoList` tool calls currently appear in the chat timeline as generic
tool-activity rows showing raw input JSON — unreadable on a phone. The web client
instead surfaces a compact "Todos · N/M" summary near the composer with a tap-to-open
detail list. This ticket brings an M3-native equivalent to the Android app.

Product discussion outcomes:

- The wire protocol has **no dedicated todo event** (`docs/server-api.md`); todos are
  derived from `TodoList` tool calls, whose `input` JSON carries the full
  `todos: [{title, status}]` array. The widget mirrors the *latest* such call.
- Details open in a `ModalBottomSheet` — chosen over the web's inline expanding panel,
  which fights the IME and the chat scroll viewport on phones and doesn't match the
  app's existing transient-detail pattern (question wizard, ticket 035).
- The collapsed widget shows the count only ("Todos · N/M"), no current-task title —
  the user's explicit choice over the more informative variant.
- `TodoList` tool-call rows are **removed from the timeline**; the widget replaces
  them. Rejected alternative: keeping both (web parity). Consequence, accepted by the
  user: history is paginated (ticket 010), so in an old session whose last `TodoList`
  call lives on a not-yet-loaded page, no todos are visible anywhere until the user
  scrolls back far enough to load that page.

## Description

1. **Parse todos from tool calls.** Add a small parser (pure function, unit-testable)
   that takes a tool name + input JSON and returns the todo list when the tool is the
   agent's todo tool: `List<Todo(title: String, status: enum { Pending, InProgress,
   Done })>`. Verify the exact tool name emitted by the server (`TodoList`) and the
   status string values (`pending` / `in_progress` / `done`) against a real session
   before hardcoding them. Malformed or missing input parses to null, never crashes.
2. **Derive widget state.** Expose the latest parsed todo list in the chat `UiState`
   (e.g. `todos: List<Todo>?`, null = widget hidden). It must update from both
   committed history rows and live `ToolCallStarted`/delta events — the natural place
   is alongside the timeline reducer (`ChatTimeline`) or as a selector over its items,
   keeping the ViewModel thin. Each new `TodoList` call replaces the previous list
   wholesale; an empty `todos` array hides the widget again.
3. **Hide the timeline rows.** Filter todo-tool activities out of the flattened
   timeline so they no longer render as tool-activity rows (both in history flattening
   and live upserts).
4. **Collapsed widget.** A compact row pinned directly above the composer inside the
   chat screen's `bottomBar` Column, visible only when `todos != null`: checklist
   icon + "Todos · N/M" (N = done count, M = total) + expand chevron. M3 tokens only
   (`surfaceContainerHigh` container so it reads as attached to the composer,
   `labelLarge`/`titleSmall` text, `MaterialTheme.shapes`), ≥48dp touch target,
   `AnimatedVisibility` slide/fade on appear/disappear, `semantics(mergeDescendants =
   true)` with a content description like "Todos, 2 of 4 done, tap to view".
5. **Detail sheet.** Tapping the widget opens a `ModalBottomSheet`: drag handle,
   "Todos · N/M" as `titleLarge` marked `semantics { heading() }`, then one row per
   todo — status icon (done / in-progress / pending), title, done items struck through
   and tinted `onSurfaceVariant`. Read-only; no reordering or toggling. Rows merged
   for TalkBack.

## Acceptance criteria

- [ ] Unit tests for the parser: valid list with all three statuses, empty list,
      malformed JSON, non-todo tool input → null.
- [ ] Unit tests for the derivation: latest call wins across multiple `TodoList`
      calls; updates from both history flattening and live tool events; empty list
      clears the widget state.
- [ ] Unit tests that todo-tool calls produce no timeline rows.
- [ ] Manual check on a real session: while the agent runs a multi-step task, the
      widget appears above the composer, counts tick up live, and the sheet shows the
      same statuses as the web client's todo panel for the same session.
- [ ] Widget and sheet render correctly in light and dark themes with no hardcoded
      colors, radii, or font sizes (grep checks from the M3 audit stay clean).

## Out of scope

- Editing/reordering todos from the app (the tool is agent-owned; mirror is read-only).
- Current in-progress task title in the collapsed widget.
- Web-parity summary chips for other tools (`Bash (n)`, `Sub Agent (n)`).
- Fetching todo state for pages of history not yet loaded (accepted limitation).

## Notes

- Relevant code: `ui/chat/ChatScreen.kt` (composer `bottomBar` around line 406),
  `ui/chat/ChatTimeline.kt` (flatten + live upsert), `data/chat/ChatItem.kt`
  (`ToolActivity` already carries `name` and `input`), `data/chat/ToolPreview.kt`
  (example of a small pure parser over tool input JSON).
- Follow the M3 patterns already used by the question wizard sheet (ticket 035).
- Keep the diff under ~500 lines; the parser + derivation + two composables + tests
  should fit comfortably.
