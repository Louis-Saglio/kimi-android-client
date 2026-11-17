# Stream a running tool's output into its chat row

- **ID:** 205
- **Created:** 2026-11-17
- **Depends on:** 198

## Context

Server 2.6.3 (nightly drift run, 2026-11-17, after the 2.6.x line was reinstated
from the 2026-11-13 withdrawal) streams a running tool call's output on the
conversation as `item.delta` with `field:"output"`, before the call ends with
`item.ended` and its final `output`. Verified live: a
`for i in 1 2 3 4 5; do echo line-$i; sleep 2; done` Bash call produced one
delta per line while the call ran.

The app's chat tool row shows a spinner while the call runs and the output only
after it ends, so a long build, test run or sub-agent is opaque on the phone.
The job detail screen already streams task output with `task.watch` (ticket
180); the chat row does not. Ticket 198 replaces the transcript fold with the
conversation fold this feature needs.

## Description

- In the conversation fold from ticket 198, append `item.delta` chunks whose
  `field` is `output` to the tool item's live output buffer.
- Render the running row's output as a short monospace tail (the last four
  lines), updated as chunks arrive, while `stage:"executing"`.
- Keep only the last 8 KiB of live output per call; the `item.ended` output
  replaces the tail and the existing expand-to-full-output behavior applies.
- Do not show the tail for `stage:"input"` (arguments still streaming) or for
  a call with no deltas.
- Leave the job detail screen's `task.watch` stream alone; this is the chat
  row's view of the same call.

## Acceptance criteria

- [ ] A fake `item.started` (executing), three `item.delta field:"output"`
      chunks and `item.ended` show a growing tail during the call and the final
      output after it.
- [ ] The live buffer never exceeds 8 KiB under a large chunk sequence.
- [ ] The expanded row shows the full final output, not the tail.
- [ ] A call that ends without deltas renders exactly as today.
- [ ] Live against 2.6.3: a `sleep`/`echo` Bash call shows its output in the row
      before the turn ends.

## Out of scope

- Refactoring `task.watch` or the job detail screen.
- Input-argument streaming (`input_text`).
- Compaction or notification rows.

## Notes

- `docs/server-api.md` → Conversation → Items and Live conversation frames.
- `item.delta` chunks carry no offset; append them in arrival order.
