# Stream running tool output into its row

- **ID:** 196
- **Created:** 2026-11-12
- **Depends on:** 188

## Context

Server 2.6.0 (nightly drift run) streams a running tool call's output on the
conversation as `item.delta` with `field:"output"`, before the call ends with
`item.ended` and its final `output`. The app's tool row shows a spinner while
the call runs and the output only after it ends, so a long Bash command, a
sub-agent run or a build is opaque on the phone. The job detail screen already
streams task output with `task.watch` (ticket 180); the chat row does not.

## Description

- In the conversation fold, append `item.delta` `field:"output"` chunks to the
  tool item's live output.
- Render the running tool row's output as a short monospace tail: the last few
  lines (for example 4), updated as chunks arrive, with the existing
  expand-to-full-output behaviour after the call ends.
- Bound memory: keep only the last N bytes (for example 8 KiB) of the live
  tail per call; the final `item.ended` output replaces the tail.
- Do not show the live tail for a call in `stage:"input"` (arguments still
  streaming); only for `executing`.
- Keep the existing `task.watch` path for the job detail screen; this ticket
  adds the chat-row view of the same call.

## Acceptance criteria

- [ ] A fake `item.started` (executing) + three `item.delta`
      `field:"output"` chunks + `item.ended` shows a growing tail during the
      call and the final output after it.
- [ ] The live buffer never exceeds the configured cap (unit test with a
      large chunk sequence).
- [ ] The expanded row still shows the full final output, not the tail.
- [ ] A call that ends without deltas renders exactly as today.
- [ ] Live against 2.6.0: a `sleep`/`echo` Bash call shows its output in the
      row before the turn ends.

## Out of scope

- Refactoring the job detail's `task.watch` stream.
- Input-argument streaming display (`input_text`).
- Compaction or notification rows.

## Notes

- `docs/server-api.md` → Conversation → Items (`item.delta`, `tool_call`
  `output`).
