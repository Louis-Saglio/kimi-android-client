# Expandable tool call output

- **ID:** 023
- **Created:** 2026-07-21
- **Depends on:** 022

## Context

Product discussion 2026-07-21 (rendering-parity batch). Ticket 022 makes tool
rows informative at a glance; this one makes them inspectable. In the web
client, tapping a tool row expands it to reveal the full input and output —
essential when the agent runs a command whose result Louis needs to read (test
failures, error output). The data is on the wire already: history
`tool_result` parts carry `output` (+ `is_error`), and live `tool.result`
frames carry `output`; both are dropped by the app's DTOs today.

## Description

- Data layer: parse `output` on history `tool_result` parts and on live
  `tool.result` frames; carry it (plus the existing `is_error`) through
  `ChatMessage.Part.ToolResult` → `ChatItem.ToolActivity`.
- UI: tapping a tool row expands/collapses it (chevron rotates per web). The
  expanded region shows:
  - the full tool input (monospace, pretty-printed JSON when it is JSON),
  - the output as monospace preformatted text on a distinct surface,
  - error output visually distinct (error color tint).
- The expanded region caps its height and scrolls internally — a 500-line
  test log must not turn the timeline into a tunnel.
- Expansion state is per-row and survives recomposition, but not process
  death (no persistence needed).

## Acceptance criteria

- [ ] Unit test: history `tool_result` output reaches `ToolActivity`,
  including the error flag (fixture per `docs/server-api.md`).
- [ ] Unit test: a live `tool.result` fills the output of its running row.
- [ ] Expanding a row of a history-loaded session shows input + output;
  expanding during a live turn shows output once `tool.result` lands
  (on-device check).
- [ ] A very long output scrolls inside a capped-height region.
- [ ] `./gradlew :app:assembleDebug` succeeds, unit tests green.

## Out of scope

Live streaming of the output while the tool runs — ticket 025. Syntax
highlighting in input/output. Copy-output button (bundle into the follow-up
copy-actions ticket if one is created). Auto-expand on error (revisit in
usage; deliberately not decided now).

## Notes

- Outputs can be large; do not keep them in memory for rows the server
  already paginates away — the current "one page of history" model bounds
  this naturally.
- Keep the tap target M3-compliant (whole row clickable, ≥48dp).
