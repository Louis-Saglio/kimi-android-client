# Consume the snapshot's in-flight turn on cold start

- **ID:** 144
- **Created:** 2026-09-16
- **Depends on:** 085

## Context

Louis reported: coming back to a LIVE session, the chat shows the session
history, then a gap, then the currently streamed content.

Root cause confirmed in code. The cold start (ticket 085) works in two legs,
exactly as suspected: the snapshot bundle (history page at a watermark) and
the WS subscribe resuming at that watermark. The replay covers only frames
AFTER the watermark (docs/server-api.md, cursor-resume), and a turn's
messages commit to history only at turn end — so the running turn's
pre-watermark tail (streamed text, thinking, completed AND still-running
tool calls of the current turn) lives nowhere in the rendered timeline. The
server ships exactly that window in the snapshot's `in_flight_turn`
(aggregated `assistant_text`/`thinking_text` + `running_tools`), but the app
decoded it "for completeness only" and never consumed it (ticket 085 scope
cut).

## Description

**1. Model** (`data/chat/Snapshot.kt`, `data/chat/ChatDtos.kt`). `RunningTool`
carries the row renderings (`preview`, `input`) derived at the mapping
boundary like a live `tool.call.started`; `RunningToolDto.display` types as
`ToolInputDisplayDto?` (same wire shape) so `ChatMessageMapper.preview` is
reused as-is.

**2. Timeline seed** (`ui/chat/ChatTimeline.kt`). `seedInFlight(turn)` appends
the aggregated text/thinking as one live assistant bubble, then the
still-running tools as live Running rows (TodoList suppressed into the
widget, same rule as a live call). Bubble first, tools after: the tools run
AT the watermark, so they are the tail's newest activity and a post-watermark
delta opens a fresh bubble — the ordinary live rule.

**3. Apply** (`ui/chat/ChatViewModel.kt`). `applySnapshot` seeds after
`replaceHistory` whenever the live tail is kept (`snapshot.busy || turnStale`).
Seeded rows are ordinary live rows: replayed results/progress heal them in
place (no duplicates), and the turn-end refetch replaces everything with the
canonical committed turn.

**4. Tests** (`ui/chat/SnapshotColdStartTest.kt`). Busy cold open renders
history + seeded bubble + running tool row; post-watermark `tool.result` +
`assistant.delta` heal/extend the seeded tail without duplicates.

## Acceptance criteria

- [x] New unit tests pass; existing suite green.
- [x] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.
- [ ] Observable on device: open a session mid-turn — the current reply's
      beginning shows between history and the live tail.

## Out of scope

- Tool calls that STARTED AND COMPLETED mid-turn before the watermark: the
  server carries them nowhere (not in `running_tools`, not in history, not
  replayed), so their rows stay missing until the turn-end refetch. All text
  is present regardless.
- Pre-watermark stdout of a still-running tool (`running_tools` has no output
  field); post-watermark `tool.progress` appends normally.
- Sub-agent pages: their cold open is transcript-backed (ticket 046), which
  carries the in-flight turn's steps — no such gap.
