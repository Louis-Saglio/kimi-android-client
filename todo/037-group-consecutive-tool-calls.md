# Group consecutive tool calls into a card

- **ID:** 037
- **Created:** 2026-07-26
- **Depends on:** none

## Context

UX review comparing the app with the web UI. The web client collapses a run of
consecutive tool calls into a single rounded card with a header —
"2 tool calls · done" plus a status dot — while the app renders each `ToolRow`
loose in the timeline. On a phone screen the ungrouped version is noticeably
noisier, and grouping is the main structural difference left between the two.

Decision: group in the timeline reducer (`ChatTimeline`), not at render time,
so live streaming and history refetch share one code path and the grouping is
unit-testable like the rest of the reducer.

## Description

- `ChatItem`: add a `ToolGroup(id, tools: List<ToolActivity>)` item. Derive
  the group id from the first tool call id so `LazyColumn` keys stay stable.
- `ChatTimeline`: coalesce adjacent `ToolActivity` items into a `ToolGroup`,
  in both the committed path (`flatten`) and the live path (`upsertTool`,
  `appendToolProgress`). A non-tool item between tools splits the run into two
  groups. While a turn streams, new tool calls append to the currently open
  live group. `replaceHistory(keepLive = true)` dedup must keep working with
  grouped items.
- `ChatScreen`: render a `ToolGroup` as an M3 `Card` on `surfaceContainerLow`
  with a header row — spinner while any tool runs, success check when all
  done, error mark if any failed; "N tool calls" plus a state label. The body
  is the existing `ToolRow` composables unchanged, per-row expand/collapse
  preserved.

## Acceptance criteria

- [ ] `ChatTimelineTest` covers: adjacent tools coalesce; an assistant message
      between tools splits the group; a live stream appends new calls to the
      open group; `replaceHistory(keepLive = true)` dedup still drops
      committed live rows.
- [ ] Grouped card renders with the correct header state (running / done /
      error) in `ChatContentPreview`.
- [ ] Per-row expand inside a group still works, including live output
      streaming of a running row (ticket 025 behavior preserved).
- [ ] Auto-scroll pinning from ticket 030 still follows the tail while a
      grouped tool run streams (stable keys).

## Out of scope

- Collapsing the whole group behind a single expander — the web shows the
  rows, so we show the rows.
- Restyling `ToolRow` itself; shape-token cleanup (ticket 040).

## Notes

- The list is `reverseLayout = true`; check group ordering against the
  reversed rendering.
