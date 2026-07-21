# Live tool output streaming (tool.progress)

- **ID:** 025
- **Created:** 2026-07-21
- **Depends on:** 023

## Context

Product discussion 2026-07-21 (rendering-parity batch). When tickets 022/023
were scoped, Louis explicitly asked for live tool output to be its own ticket
after the expandable-output work: the server streams `tool.progress` frames
(volatile, carrying live stdout chunks, documented in `docs/server-api.md`),
and the web client shows output accumulating while a command runs. That is
genuinely useful on the phone for long builds — you see the failure scroll by
instead of staring at a spinner — but it adds socket plumbing and volatile
state that should not destabilize the 023 slice.

## Description

- Parse `tool.progress` frames in `ChatRepository` (payload carries
  `toolCallId` plus the output chunk; exact shape per `docs/server-api.md`)
  and surface a new `SessionEvent` for them.
- `ChatTimeline`: append progress chunks to the matching running
  `ToolActivity` only (ignore progress for unknown or finished calls).
  Progress is volatile: the definitive `tool.result` output replaces the
  accumulated progress text wholesale, and a history refetch remains
  canonical.
- UI: when a running tool row is expanded (023), its output region updates
  live as chunks arrive, pinned to the bottom of the internal scroll.
  Collapsed rows are unaffected (no preview churn).

## Acceptance criteria

- [ ] Unit test: a `tool.progress` frame per the documented payload parses
  into the new event.
- [ ] Unit test: chunks append only to the matching running tool row;
  `tool.result` replaces the accumulated text; refetching history clears
  volatile state.
- [ ] On-device: expanding a running Bash row shows output accumulating live,
  and the final result matches what the web client shows for the same call.
- [ ] `./gradlew :app:assembleDebug` succeeds, unit tests green.

## Out of scope

Persisting progress into history, ANSI escape-sequence rendering (strip or
pass through raw — pick the simpler), progress for non-shell tools beyond
what the frames provide, auto-expanding running rows.

## Notes

- Frames are marked `volatile: true` on the wire — never let them create
  timeline rows on their own; the row must come from
  `tool.call.delta`/`started` or history.
