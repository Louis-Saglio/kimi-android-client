# Drive the compaction UI from system turns

- **ID:** 191
- **Created:** 2026-11-12
- **Depends on:** 188

## Context

Server 2.6.0 (nightly drift run, 2026-11-12; commit `1dd55786`) replaced the
transcript `marker:"compaction"` items with a `system` turn. `:compact` sets
`compacting:true` on the main agent's `agent` frame; the result arrives as
`turn.started` (kind `system`), one `item.ended` of type `system` with
`kind:"compaction"` (`trigger`, `instruction?`, `summary`, `tokens_before`,
`tokens_after`, `compacted_count`), and `turn.ended`. A cancelled compaction
leaves nothing in the conversation and pushes a `notice` with
`code:"compaction.cancelled"`.

The app's compaction UI is driven by the removed markers
(`CompactionMarkerPayload`, `CompactionStarted`/`CompactionCompleted`), so it
will never show again after ticket 188 removes markers.

## Description

- Start the compaction indicator when the main agent's `agent` frame reports
  `compacting:true`; clear it on `compacting:false`, on the `system` turn's
  `turn.ended`, or on an idle `event.session.work_changed` as a fallback.
- On the `system` compaction item, emit `CompactionCompleted` with the summary,
  `tokens_before`, `tokens_after` and `compacted_count`; the divider and the
  summary screen read these.
- On `notice` with `code:"compaction.cancelled"`, clear the indicator and show
  a snackbar with the notice title (no divider row).
- Remove `CompactionMarkerPayload`, `CompactionResultDto`'s marker use and the
  marker branch in the fold; keep the history `compaction_summary` handling
  (the summary is still persisted as a user message with
  `origin.kind:"compaction_summary"`).
- Keep the 40910 messages from `:compact` on the snackbar unchanged.

## Acceptance criteria

- [ ] A fake `agent(compacting:true)` shows the in-progress indicator; the
      following `system` turn/item/`turn.ended` and `agent(compacting:false)`
      clears it and shows the summary divider once.
- [ ] The divider's token delta uses `tokens_before`/`tokens_after`; the
      summary screen shows `summary`.
- [ ] A `notice` with `code:"compaction.cancelled"` clears the indicator and
      shows a snackbar, with no divider row.
- [ ] No source references `marker:"compaction"` or `CompactionMarkerPayload`.
- [ ] Live against 2.6.0: `{}` on an empty session surfaces the 40910 message;
      `{"instruction":…}` on a used session shows the indicator, then the
      divider with the echoed instruction available on the summary.

## Out of scope

- The auto-compaction trigger (server-side).
- Undo of a compaction.
- General notice styling beyond the compaction case.

## Notes

- `docs/server-api.md` → Compact → WebSocket sequence (2.6.0).
